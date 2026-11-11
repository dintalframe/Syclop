package com.nuvio.tv.core.player

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingPlaybackOrigin
import com.nuvio.tv.core.tracking.TrackingPlaybackOriginCapture
import com.nuvio.tv.core.tracking.TrackingPlaybackGrant
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.player.PlayerProgressWriteAuthority
import com.nuvio.tv.ui.screens.player.PlayerProgressWriteCoordinator
import com.nuvio.tv.ui.screens.player.PlayerProgressWriteOwner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Production tracker entry points with synthetic Android/persistence boundaries; no player or HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlayerTrackerContinuationTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkObject(ExternalPlaybackKeepAliveService.Companion, ZidooPlayerMonitor)
        every { ExternalPlaybackKeepAliveService.start(any()) } just Runs
        every { ExternalPlaybackKeepAliveService.stop(any()) } just Runs
        every { ZidooPlayerMonitor.isZidooDevice() } returns false
    }

    @After fun tearDown() {
        unmockkObject(ExternalPlaybackKeepAliveService.Companion, ZidooPlayerMonitor)
        Dispatchers.resetMain()
    }

    @Test fun `delayed completion cannot save advance or clear a replacement launch`() = runTest {
        val h = Harness()
        val navigationEvents = mutableListOf<ExternalAutoNextEpisode>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.tracker.autoPlayNext.collect { navigationEvents += it }
        }
        val old = metadata("tt101", episode = 1)
        val current = metadata("tt202", episode = 7)
        val backfill = h.deferDuration(old)
        h.tracker.startTracking(old, autoNextEnabled = false)
        h.tracker.onActivityResult(ExternalPlayerResult(900_000L, null, endedByUser = false))
        runCurrent()
        assertTrue(backfill.entered.isCompleted)

        h.tracker.startTracking(current, autoNextEnabled = true)
        runCurrent() // The current launch has a real next-episode snapshot from its mocked catalog.
        h.tracker.raiseAutoNextOverlayOnReturn()
        val overlay = h.tracker.autoNextOverlay.value
        assertNotNull(overlay)
        assertEquals(current.profileId, overlay?.profileId)
        val persistedCurrent = h.values.toMap()
        backfill.release.complete(progress(old, 1_000_000L))
        runCurrent()

        assertSame(current, h.tracker.pendingMetadata)
        assertSame(overlay, h.tracker.autoNextOverlay.value)
        assertEquals(persistedCurrent, h.values)
        assertTrue(h.saved.isEmpty())
        assertTrue(navigationEvents.isEmpty())
        coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }
        verify(exactly = 0) { h.cloudProgress.save(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { ExternalPlaybackKeepAliveService.stop(h.context) }
    }

    @Test fun `delayed result cannot replace a newer fully processed result`() = runTest {
        val h = Harness()
        val old = metadata("tt101")
        val current = metadata("tt202")
        val backfill = h.deferDuration(old)
        h.tracker.startTracking(old)
        h.tracker.onActivityResult(ExternalPlayerResult(900_000L, null))
        runCurrent()
        h.tracker.startTracking(current)
        h.tracker.onActivityResult(ExternalPlayerResult(60_000L, 600_000L))
        runCurrent()
        assertEquals(listOf("tt202"), h.saved.map { it.contentId })

        backfill.release.complete(progress(old, 1_000_000L))
        runCurrent()

        assertEquals(listOf("tt202"), h.saved.map { it.contentId })
        assertNull(h.tracker.pendingMetadata)
        assertTrue(h.values.isEmpty())
        coVerify(exactly = 2) { h.scrobbles.scrobble(any(), any()) }
        verify(exactly = 1) { ExternalPlaybackKeepAliveService.stop(h.context) }
    }

    @Test fun `delayed result stays rejected after replacement returns no progress`() = runTest {
        val h = Harness()
        val old = metadata("tt101")
        val backfill = h.deferDuration(old)
        h.tracker.startTracking(old)
        h.tracker.onActivityResult(ExternalPlayerResult(900_000L, null))
        runCurrent()
        h.tracker.startTracking(metadata("tt202"))
        h.tracker.onActivityResult(null)
        backfill.release.complete(progress(old, 1_000_000L))
        runCurrent()

        assertTrue(h.saved.isEmpty())
        assertFalse(h.tracker.isTracking)
        assertTrue(h.values.isEmpty())
        coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }
        verify(exactly = 1) { ExternalPlaybackKeepAliveService.stop(h.context) }
    }

    @Test fun `reusing the exact metadata object still retires an older backfill`() = runTest {
        val h = Harness()
        val sameMetadata = metadata("tt101")
        val backfill = h.deferDuration(sameMetadata)
        h.tracker.startTracking(sameMetadata)
        h.tracker.onActivityResult(ExternalPlayerResult(90_000L, null))
        runCurrent()
        h.tracker.startTracking(sameMetadata)
        val persistedCurrent = h.values.toMap()
        backfill.release.complete(progress(sameMetadata, 600_000L))
        runCurrent()

        assertSame(sameMetadata, h.tracker.pendingMetadata)
        assertEquals(persistedCurrent, h.values)
        assertTrue(h.saved.isEmpty())
        verify(exactly = 0) { ExternalPlaybackKeepAliveService.stop(h.context) }
    }

    @Test fun `current duration backfill persists once after normal result cleanup`() = runTest {
        val h = Harness()
        val current = metadata("tt101")
        val backfill = h.deferDuration(current)
        h.tracker.startTracking(current)
        h.tracker.onActivityResult(ExternalPlayerResult(60_000L, null))
        runCurrent()
        assertTrue(backfill.entered.isCompleted)
        backfill.release.complete(progress(current, 600_000L))
        runCurrent()

        assertEquals(listOf(60_000L), h.saved.map { it.position })
        assertEquals(listOf(600_000L), h.saved.map { it.duration })
        assertEquals(listOf(1), h.savedProfiles)
        assertNull(h.tracker.pendingMetadata)
        assertTrue(h.values.isEmpty())
        coVerify(exactly = 2) { h.scrobbles.scrobble(any(), any()) }
        verify(exactly = 1) { ExternalPlaybackKeepAliveService.stop(h.context) }
    }

    @Test fun `current Just Player end only result still records completion once`() = runTest {
        val h = Harness()
        h.tracker.startTracking(metadata("tt101", episode = 1), autoNextEnabled = false)
        h.tracker.onActivityResult(ExternalPlayerResult(0L, null, endedByUser = false))
        runCurrent()

        assertEquals(1, h.saved.size)
        assertEquals(100f, h.saved.single().progressPercent)
        assertTrue(h.saved.single().isCompleted())
        assertNull(h.tracker.pendingMetadata)
        assertTrue(h.values.isEmpty())
    }

    @Test fun `new tracking start rejects an old fully resolved but still queued save`() = runTest {
        val h = Harness()
        h.tracker.startTracking(metadata("tt101"))
        h.tracker.onActivityResult(ExternalPlayerResult(60_000L, 600_000L))
        assertNull(h.tracker.pendingMetadata) // Normal result cleanup precedes its queued persistence.
        val current = metadata("tt202")
        h.tracker.startTracking(current)
        runCurrent()

        assertTrue(h.saved.isEmpty())
        assertSame(current, h.tracker.pendingMetadata)
        assertEquals("tt202", h.values["contentId"])
        coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }
    }

    @Test fun `new start during an entered scrobble prevents its later stop continuation`() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { h.scrobbles.scrobble(TrackingScrobbleAction.START, any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            emptyList()
        }
        h.tracker.startTracking(metadata("tt101"))
        h.tracker.onActivityResult(ExternalPlayerResult(60_000L, 600_000L))
        runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(1, h.saved.size) // Already-entered persistence is deliberately outside this guard.
        val current = metadata("tt202")
        h.tracker.startTracking(current)
        release.complete(Unit)
        runCurrent()

        assertSame(current, h.tracker.pendingMetadata)
        coVerify(exactly = 1) { h.scrobbles.scrobble(TrackingScrobbleAction.START, any()) }
        coVerify(exactly = 0) { h.scrobbles.scrobble(TrackingScrobbleAction.STOP, any()) }
    }

    @Test fun `recovery binds one current epoch and preserves normal backfill processing`() = runTest {
        val h = Harness()
        val original = metadata("tt101")
        val backfill = h.deferDuration(original)
        h.tracker.startTracking(original)
        val recovered = h.newTracker() // New process-scoped tracker reads the same persisted record.
        recovered.raiseAutoNextOverlayOnReturn()
        assertEquals(original, recovered.pendingMetadata)
        recovered.onActivityResult(ExternalPlayerResult(60_000L, null))
        runCurrent()
        recovered.raiseAutoNextOverlayOnReturn() // Re-reading an already bound session must not retire it.
        backfill.release.complete(progress(original, 600_000L))
        runCurrent()

        assertEquals(listOf("tt101"), h.saved.map { it.contentId })
        assertNull(recovered.pendingMetadata)
        assertTrue(h.values.isEmpty())
    }

    @Test fun `recovered watchdog cannot clear a newer launch`() = runTest {
        val h = Harness()
        h.tracker.startTracking(metadata("tt101"))
        val recovered = h.newTracker()
        recovered.raiseAutoNextOverlayOnReturn()
        val current = metadata("tt202")
        recovered.startTracking(current)
        advanceTimeBy(8_001L)
        runCurrent()

        assertSame(current, recovered.pendingMetadata)
        assertEquals("tt202", h.values["contentId"])
        verify(exactly = 0) { ExternalPlaybackKeepAliveService.stop(h.context) }
    }

    @Test fun `stale queued cloud result cannot borrow a newer token on the same metadata object`() = runTest {
        val h = Harness()
        val cloud = metadata("cloud-fixture").copy(contentType = "cloud")
        h.tracker.startTracking(cloud, cloudSessionToken = "old-native-token")
        h.tracker.onActivityResult(ExternalPlayerResult(60_000L, null))
        h.tracker.startTracking(cloud, cloudSessionToken = "new-native-token")
        runCurrent()

        assertSame(cloud, h.tracker.pendingMetadata)
        assertEquals("new-native-token", h.values["cloudSessionToken"])
        verify(exactly = 0) { h.cloudSessions.load(any()) }
        verify(exactly = 0) { h.cloudProgress.save(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { ExternalPlaybackKeepAliveService.stop(h.context) }
    }

    @Test fun `cancelled old Zidoo callback cannot consume a reused metadata launch`() = runTest {
        val h = Harness()
        every { ZidooPlayerMonitor.isZidooDevice() } returns true
        val callbacks = mutableListOf<Continuation<ZidooPlayerMonitor.ZidooPlaybackResult?>>()
        coEvery { ZidooPlayerMonitor.awaitPlaybackEnd(any(), any()) } coAnswers {
            // A non-cooperative transport callback models completion after the monitor was cancelled.
            suspendCoroutine { callbacks += it }
        }
        val sameMetadata = metadata("tt101")
        h.tracker.startTracking(sameMetadata, startFromBeginning = true)
        runCurrent()
        assertEquals(1, callbacks.size)
        h.tracker.startTracking(sameMetadata, startFromBeginning = true)
        runCurrent()
        assertEquals(2, callbacks.size)
        callbacks[0].resume(ZidooPlayerMonitor.ZidooPlaybackResult(60_000L, 600_000L))
        runCurrent()

        assertSame(sameMetadata, h.tracker.pendingMetadata)
        assertTrue(h.saved.isEmpty())
        verify(exactly = 0) { ExternalPlaybackKeepAliveService.stop(h.context) }
        callbacks[1].resume(null)
        runCurrent()
        assertTrue(h.tracker.isTracking) // Null monitor result still waits for the real ActivityResult.
        h.tracker.onActivityResult(null)
        assertFalse(h.tracker.isTracking)
    }

    @Test fun `entered external completion cannot overwrite a newer internal rewatch`() = runTest {
        val h = Harness()
        val item = metadata("tt101")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        suspend fun commit(value: WatchProgress) {
            if (value.isCompleted()) {
                entered.complete(Unit)
                release.await()
                committed += "external-completion"
            } else committed += "internal-rewatch:${value.position}"
        }
        coEvery { h.repository.savePlaybackProgress(any(), 1, any(), any(), any()) } coAnswers { commit(firstArg()) }
        coEvery { h.repository.saveProgress(any(), 1, true) } coAnswers {
            val value = firstArg<WatchProgress>()
            if (value.isCompleted()) {
                entered.complete(Unit)
                release.await()
                committed += "external-completion"
            } else committed += "internal-rewatch:${value.position}"
        }
        h.tracker.startTracking(item)
        h.tracker.onActivityResult(ExternalPlayerResult(950_000L, 1_000_000L))
        runCurrent()
        assertTrue(entered.isCompleted)
        val (internal, lease) = internalLease(h, item)
        val newSave = launch {
            internal.writeIfCurrent(lease, false) {
                h.repository.saveProgress(progress(item, 1_000_000L).copy(position = 60_000L), 1)
            }
        }
        runCurrent()
        try { assertTrue(committed.isEmpty()) } finally { release.complete(Unit) }
        newSave.join()
        runCurrent()
        assertEquals(listOf("external-completion", "internal-rewatch:60000"), committed)
        coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }
    }

    @Test fun `late external result cannot save or scrobble over a newer internal session`() = runTest {
        val h = Harness()
        val item = metadata("tt101")
        h.tracker.startTracking(item)
        val (internal, lease) = internalLease(h, item)
        internal.writeIfCurrent(lease, false) {
            h.repository.saveProgress(progress(item, 600_000L).copy(position = 60_000L), 1)
        }
        h.tracker.onActivityResult(ExternalPlayerResult(590_000L, 600_000L))
        runCurrent()
        assertEquals(listOf(60_000L), h.saved.map { it.position })
        assertFalse(h.tracker.isTracking)
        coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }
    }

    @Test fun `external duration backfill is retired by an internal rewatch`() = runTest {
        val h = Harness()
        val item = metadata("tt101")
        val backfill = h.deferDuration(item)
        h.tracker.startTracking(item)
        h.tracker.onActivityResult(ExternalPlayerResult(590_000L, null))
        runCurrent()
        assertTrue(backfill.entered.isCompleted)
        val (internal, lease) = internalLease(h, item)
        internal.writeIfCurrent(lease, false) {
            h.repository.saveProgress(progress(item, 600_000L).copy(position = 60_000L), 1)
        }
        backfill.release.complete(progress(item, 600_000L))
        runCurrent()
        assertEquals(listOf(60_000L), h.saved.map { it.position })
        assertFalse(h.tracker.isTracking)
        coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }
    }

    @Test fun `second physical handoff is rejected while a result is outstanding`() = runTest {
        val h = Harness()
        val first = metadata("tt101")
        h.tracker.startTracking(first)
        assertFalse(h.tracker.launchPlayer(metadata("tt202"), "https://example.invalid/file", null, null,
            context = h.context))
        assertSame(first, h.tracker.pendingMetadata)
        h.tracker.onActivityResult(ExternalPlayerResult(60_000L, 600_000L))
        runCurrent()
        assertEquals(listOf("tt101"), h.saved.map { it.contentId })
    }

    @Test fun `rejected Android handoff preserves the current internal progress lease`() = runTest {
        val h = Harness()
        val item = metadata("tt101")
        val (internal, lease) = internalLease(h, item)
        assertFalse(h.tracker.launchPlayer(item, "https://example.invalid/ungranted", null, null,
            startFromBeginning = true, context = h.context))
        assertTrue(h.authority.isCurrent(lease))
        internal.writeIfCurrent(lease, false) { h.repository.saveProgress(progress(item, 600_000L), 1) }
        assertEquals(1, h.saved.size)
    }

    @Test fun `late physical result after watchdog cannot consume a replacement handoff`() = runTest {
        val h = Harness()
        h.tracker.startTracking(metadata("tt101"))
        val oldHandoff = checkNotNull(h.values["externalHandoffId"] as? String)
        val recovered = h.newTracker()
        recovered.raiseAutoNextOverlayOnReturn()
        advanceTimeBy(8_001L)
        runCurrent()
        assertFalse(recovered.isTracking)
        assertTrue(h.values.isEmpty())

        val replacement = metadata("tt202")
        recovered.startTracking(replacement)
        val newHandoff = checkNotNull(h.values["externalHandoffId"] as? String)
        assertNotEquals(oldHandoff, newHandoff)
        val pendingBefore = h.values.toMap()
        recovered.onActivityResult(oldHandoff, ExternalPlayerResult(590_000L, 600_000L))
        runCurrent()
        assertSame(replacement, recovered.pendingMetadata)
        assertEquals(pendingBefore, h.values)
        assertTrue(h.saved.isEmpty())
        coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }

        recovered.onActivityResult(newHandoff, ExternalPlayerResult(60_000L, 600_000L))
        runCurrent()
        assertEquals(listOf("tt202"), h.saved.map { it.contentId })
        assertEquals(listOf(60_000L), h.saved.map { it.position })
    }

    @Test fun `live handoff without a physical return expires without blocking its replacement`() = runTest {
        val h = Harness()
        h.tracker.startTracking(metadata("tt101"))
        val oldHandoff = checkNotNull(h.values["externalHandoffId"] as? String)
        h.tracker.raiseAutoNextOverlayOnReturn()
        advanceTimeBy(8_001L)
        runCurrent()
        assertFalse(h.tracker.isTracking)
        assertTrue(h.values.isEmpty())
        val replacement = metadata("tt202")
        h.tracker.startTracking(replacement)
        val newHandoff = checkNotNull(h.values["externalHandoffId"] as? String)
        h.tracker.onActivityResult(oldHandoff, ExternalPlayerResult(590_000L, 600_000L))
        runCurrent()
        assertSame(replacement, h.tracker.pendingMetadata)
        assertTrue(h.saved.isEmpty())
        h.tracker.onActivityResult(newHandoff, ExternalPlayerResult(60_000L, 600_000L))
        runCurrent()
        assertEquals(listOf("tt202"), h.saved.map { it.contentId })
    }

    @Test fun `stale physical result cannot disturb a newer duration backfill`() = runTest {
        val h = Harness()
        h.tracker.startTracking(metadata("tt101"))
        val oldHandoff = checkNotNull(h.values["externalHandoffId"] as? String)
        val replacement = metadata("tt202")
        val backfill = h.deferDuration(replacement)
        h.tracker.startTracking(replacement)
        val newHandoff = checkNotNull(h.values["externalHandoffId"] as? String)
        h.tracker.onActivityResult(newHandoff, ExternalPlayerResult(60_000L, null))
        runCurrent()
        assertTrue(backfill.entered.isCompleted)
        val pendingBefore = h.values.toMap()
        h.tracker.onActivityResult(oldHandoff, null)
        assertEquals(pendingBefore, h.values)
        assertSame(replacement, h.tracker.pendingMetadata)
        backfill.release.complete(progress(replacement, 600_000L))
        runCurrent()
        assertEquals(listOf("tt202"), h.saved.map { it.contentId })
    }

    @Test fun `recovered physical result retains persisted handoff identity`() = runTest {
        val h = Harness()
        h.tracker.startTracking(metadata("tt101"))
        val handoff = checkNotNull(h.values["externalHandoffId"] as? String)
        val recovered = h.newTracker()
        recovered.onActivityResult(handoff, ExternalPlayerResult(60_000L, 600_000L))
        runCurrent()
        assertEquals(listOf("tt101"), h.saved.map { it.contentId })
        assertTrue(h.values.isEmpty())
    }

    @Test fun `fire and forget return without an activity result has bounded cleanup`() = runTest {
        val h = Harness()
        mockkObject(ConnectedServicePlaybackGrants.registry, ExternalPlayerLauncher)
        try {
            every { ConnectedServicePlaybackGrants.registry.permits(any(), any()) } returns true
            every { ExternalPlayerLauncher.launch(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            assertTrue(h.tracker.launchPlayer(metadata("tt101", episode = 1), "https://example.invalid/fixture", null, null,
                startFromBeginning = true, context = h.context))
            assertEquals(false, h.values["externalResultOutstanding"])
            assertTrue(h.tracker.isTracking)
            h.tracker.raiseAutoNextOverlayOnReturn()
            advanceTimeBy(8_001L)
            runCurrent()
            assertFalse(h.tracker.isTracking)
            assertTrue(h.values.isEmpty())
        } finally {
            h.tracker.stopTracking()
            unmockkObject(ConnectedServicePlaybackGrants.registry, ExternalPlayerLauncher)
        }
    }

    @Test fun `successful Zidoo monitor retires its physical handoff before the next launch`() = runTest {
        val h = Harness()
        every { ZidooPlayerMonitor.isZidooDevice() } returns true
        var monitorCalls = 0
        coEvery { ZidooPlayerMonitor.awaitPlaybackEnd(any(), any()) } coAnswers {
            if (monitorCalls++ == 0) ZidooPlayerMonitor.ZidooPlaybackResult(60_000L, 600_000L)
            else awaitCancellation()
        }
        mockkObject(ConnectedServicePlaybackGrants.registry, ExternalPlayerLauncher)
        try {
            every { ConnectedServicePlaybackGrants.registry.permits(any(), any()) } returns true
            every { ExternalPlayerLauncher.launch(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            h.tracker.startTracking(metadata("tt101"), startFromBeginning = true)
            val oldId = checkNotNull(h.values["externalHandoffId"] as? String)
            runCurrent()
            assertFalse(h.tracker.isTracking)
            assertEquals(listOf("tt101"), h.saved.map { it.contentId })
            val replacement = metadata("tt202")
            assertTrue(h.tracker.launchPlayer(replacement, "https://example.invalid/fixture", null, null,
                startFromBeginning = true, context = h.context))
            assertSame(replacement, h.tracker.pendingMetadata)
            val persisted = h.values.toMap()
            h.tracker.onActivityResult(oldId, ExternalPlayerResult(590_000L, 600_000L))
            runCurrent()
            assertSame(replacement, h.tracker.pendingMetadata)
            assertEquals(persisted, h.values)
        } finally {
            h.tracker.stopTracking()
            unmockkObject(ConnectedServicePlaybackGrants.registry, ExternalPlayerLauncher)
        }
    }

    @Test fun `preparation resuming after another accepted handoff cannot overwrite it`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val checkpoint = object : PlaybackProgressLeaseCheckpoint {
            val values = mutableMapOf<String, String>()
            override suspend fun read(ownerKey: String): String? {
                entered.complete(Unit)
                release.await()
                return values[ownerKey]
            }
            override suspend fun write(ownerKey: String, nonce: String) { values[ownerKey] = nonce }
        }
        val h = Harness(PlayerProgressWriteAuthority(checkpoint))
        val deferred = async { h.tracker.launchPlayer(metadata("tt202"), "https://example.invalid/ungranted", null, null,
            startFromBeginning = true, context = h.context) }
        runCurrent()
        assertTrue(entered.isCompleted)
        val accepted = metadata("tt101")
        h.tracker.startTracking(accepted)
        val persisted = h.values.toMap()
        release.complete(Unit)
        assertFalse(deferred.await())
        runCurrent()
        assertSame(accepted, h.tracker.pendingMetadata)
        assertEquals(persisted, h.values)
        assertTrue(h.saved.isEmpty())
    }

    @Test fun `stopped parsed result survives tracker destruction without foreground progress writes`() = runTest {
        val h = Harness()
        val registry = FixtureRegistry()
        h.tracker.bindActivityResultRegistry(registry)
        h.tracker.onActivityResultRegistryStarted(registry)
        mockkObject(ConnectedServicePlaybackGrants.registry)
        try {
            every { ConnectedServicePlaybackGrants.registry.permits(any(), any()) } returns true
            assertTrue(h.tracker.launchPlayer(metadata("tt101"), "https://example.invalid/fixture", null, null,
                startFromBeginning = true, context = h.context))
            h.tracker.onActivityResultRegistryStopped(registry)
            registry.complete(ExternalPlayerResult(60_000L, 600_000L))
            runCurrent()
            assertEquals(true, h.values["bufferedResultReceived"])
            assertEquals(false, h.values["bufferedResultIsNull"])
            assertTrue(h.saved.isEmpty())
            coVerify(exactly = 0) { h.scrobbles.scrobble(any(), any()) }
            h.tracker.unbindActivityResultRegistry(registry)

            val recovered = h.newTracker()
            val restoredRegistry = FixtureRegistry()
            recovered.bindActivityResultRegistry(restoredRegistry)
            assertTrue(h.saved.isEmpty())
            recovered.onActivityResultRegistryStarted(restoredRegistry)
            runCurrent()
            assertEquals(listOf(60_000L), h.saved.map { it.position })
            assertTrue(h.values.isEmpty())
            recovered.unbindActivityResultRegistry(restoredRegistry)
        } finally { unmockkObject(ConnectedServicePlaybackGrants.registry) }
    }

    @Test fun `stopped null result remains received through recovery and clears without saving`() = runTest {
        val h = Harness()
        val registry = FixtureRegistry()
        h.tracker.bindActivityResultRegistry(registry)
        h.tracker.onActivityResultRegistryStarted(registry)
        mockkObject(ConnectedServicePlaybackGrants.registry)
        try {
            every { ConnectedServicePlaybackGrants.registry.permits(any(), any()) } returns true
            assertTrue(h.tracker.launchPlayer(metadata("tt101"), "https://example.invalid/fixture", null, null,
                startFromBeginning = true, context = h.context))
            h.tracker.onActivityResultRegistryStopped(registry)
            registry.complete(null)
            assertEquals(true, h.values["bufferedResultReceived"])
            assertEquals(true, h.values["bufferedResultIsNull"])
            h.tracker.unbindActivityResultRegistry(registry)
            val recovered = h.newTracker()
            val restoredRegistry = FixtureRegistry()
            recovered.bindActivityResultRegistry(restoredRegistry)
            recovered.onActivityResultRegistryStarted(restoredRegistry)
            runCurrent()
            assertTrue(h.saved.isEmpty())
            assertTrue(h.values.isEmpty())
            recovered.unbindActivityResultRegistry(restoredRegistry)
        } finally { unmockkObject(ConnectedServicePlaybackGrants.registry) }
    }

    @Test fun `stopped null result preserves Zidoo monitor authority and keeps process alive`() = runTest {
        val h = Harness()
        val registry = FixtureRegistry()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ZidooPlayerMonitor.ZidooPlaybackResult?>()
        every { ZidooPlayerMonitor.isZidooDevice() } returns true
        coEvery { ZidooPlayerMonitor.awaitPlaybackEnd(any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        h.tracker.bindActivityResultRegistry(registry)
        h.tracker.onActivityResultRegistryStarted(registry)
        mockkObject(ConnectedServicePlaybackGrants.registry)
        try {
            every { ConnectedServicePlaybackGrants.registry.permits(any(), any()) } returns true
            assertTrue(h.tracker.launchPlayer(metadata("tt101"), "https://example.invalid/fixture", null, null,
                startFromBeginning = true, context = h.context))
            runCurrent()
            assertTrue(entered.isCompleted)
            h.tracker.onActivityResultRegistryStopped(registry)
            registry.complete(null)
            runCurrent()
            assertEquals(true, h.values["bufferedResultIsNull"])
            assertTrue(h.tracker.isTracking)
            verify(exactly = 0) { ExternalPlaybackKeepAliveService.stop(h.context) }
            h.tracker.onActivityResultRegistryStarted(registry)
            runCurrent()
            assertTrue(h.tracker.isTracking)
            assertTrue(h.saved.isEmpty())
            release.complete(ZidooPlayerMonitor.ZidooPlaybackResult(60_000L, 600_000L))
            runCurrent()
            assertEquals(listOf(60_000L), h.saved.map { it.position })
            assertFalse(h.tracker.isTracking)
            assertTrue(h.values.isEmpty())
        } finally {
            release.complete(null)
            h.tracker.stopTracking()
            h.tracker.unbindActivityResultRegistry(registry)
            unmockkObject(ConnectedServicePlaybackGrants.registry)
        }
    }

    @Test fun `explicit task exit retires accepted handoff across reopen and rejects its late result`() = runTest {
        val h = Harness()
        val oldRegistry = FixtureRegistry()
        h.tracker.bindActivityResultRegistry(oldRegistry)
        h.tracker.onActivityResultRegistryStarted(oldRegistry)
        mockkObject(ConnectedServicePlaybackGrants.registry)
        try {
            every { ConnectedServicePlaybackGrants.registry.permits(any(), any()) } returns true
            assertTrue(h.tracker.launchPlayer(metadata("tt101"), "https://example.invalid/fixture", null, null,
                startFromBeginning = true, context = h.context))
            val oldId = h.values["externalHandoffId"] as String
            h.tracker.prepareForAppTaskExit()
            assertTrue(h.values.isEmpty())
            assertFalse(h.tracker.isTracking)
            assertNull(h.tracker.autoNextOverlay.value)
            val reopened = h.newTracker()
            val nextRegistry = FixtureRegistry()
            reopened.bindActivityResultRegistry(nextRegistry)
            reopened.onActivityResultRegistryStarted(nextRegistry)
            assertFalse(reopened.isTracking)
            assertTrue(reopened.launchPlayer(metadata("tt202"), "https://example.invalid/fixture", null, null,
                startFromBeginning = true, context = h.context))
            val current = h.values.toMap()
            oldRegistry.complete(ExternalPlayerResult(90_000L, 100_000L))
            reopened.onActivityResult(oldId, ExternalPlayerResult(90_000L, 100_000L))
            runCurrent()
            assertEquals(current, h.values)
            assertEquals("tt202", reopened.pendingMetadata?.contentId)
            assertTrue(h.saved.isEmpty())
            reopened.prepareForAppTaskExit()
            reopened.unbindActivityResultRegistry(nextRegistry)
        } finally {
            h.tracker.unbindActivityResultRegistry(oldRegistry)
            unmockkObject(ConnectedServicePlaybackGrants.registry)
        }
    }

    @Test fun `external result retains captured local scope and original grant after delayed return`() = runTest {
        val h = Harness()
        var current = true
        val grant = TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, "00000000-0000-0000-0000-000000000001", { current })
        val origin = TrackingPlaybackOrigin(1, mapOf(TrackingProviderId.TRAKT to grant), "trakt-local-v1:captured")
        h.tracker.startTracking(metadata("tt101"), playbackOrigin = origin)
        current = false
        h.tracker.onActivityResult(ExternalPlayerResult(50_000L, 100_000L))
        runCurrent()
        assertEquals("trakt-local-v1:captured", h.saved.single().localPlaybackScope)
        coVerify(exactly = 1) { h.repository.savePlaybackProgress(any(), 1, origin, false, true) }
        coVerify(exactly = 1) { h.scrobbles.scrobble(TrackingScrobbleAction.STOP, any(), origin) }
    }

    @Test fun `same-process stop and result backfill restore the captured playback origin on return`() = runTest {
        val h = Harness()
        val item = metadata("tt101")
        val origin = TrackingPlaybackOrigin(1, emptyMap(), "trakt-local-v1:original")
        val backfill = h.deferDuration(item)
        h.tracker.startTracking(item, playbackOrigin = origin)
        h.tracker.stopTracking() // Ordinary screen lifecycle; the physical result is still owed.
        h.tracker.onActivityResult(ExternalPlayerResult(50_000L, null))
        runCurrent()
        assertTrue(backfill.entered.isCompleted)
        h.tracker.raiseAutoNextOverlayOnReturn()
        backfill.release.complete(progress(item, 100_000L))
        runCurrent()
        assertEquals("trakt-local-v1:original", h.saved.single().localPlaybackScope)
        coVerify(exactly = 1) { h.repository.savePlaybackProgress(any(), 1, origin, false, true) }
    }

    private class FixtureRegistry : ActivityResultRegistry() {
        var requestCode = -1
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { this.requestCode = requestCode }
        fun complete(result: ExternalPlayerResult?) { dispatchResult(requestCode, result) }
    }

    private fun internalLease(h: Harness, metadata: ExternalPlaybackMetadata): Pair<PlayerProgressWriteCoordinator, PlayerProgressWriteAuthority.Request> {
        val owner = PlayerProgressWriteOwner(metadata.profileId, metadata.contentId, metadata.contentType,
            metadata.videoId, metadata.season, metadata.episode)
        val coordinator = PlayerProgressWriteCoordinator(h.authority)
        coordinator.activateForeground(owner)
        assertTrue(coordinator.onPlaybackOwnerChanged(owner))
        return coordinator to checkNotNull(coordinator.capture(owner, false))
    }

    private data class Backfill(
        val entered: CompletableDeferred<Unit>,
        val release: CompletableDeferred<WatchProgress?>
    )

    private class Harness(val authority: PlayerProgressWriteAuthority = PlayerProgressWriteAuthority()) {
        val context = mockk<Context>()
        val repository = mockk<WatchProgressRepository>(relaxed = true)
        val scrobbles = mockk<TrackingScrobbleCoordinator>(relaxed = true)
        val cloudProgress = mockk<CloudLibraryPlaybackProgressStore>(relaxed = true)
        val cloudSessions = mockk<CloudLibraryPlaybackSessionStore>(relaxed = true)
        val values = mutableMapOf<String, Any?>()
        val saved = mutableListOf<WatchProgress>()
        val savedProfiles = mutableListOf<Int>()
        private val prefs = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>()
        private val settings = mockk<PlayerSettingsDataStore>()
        private val meta = mockk<MetaRepository>()
        private val shuffle = mockk<EpisodeShufflePlayback>()
        private val cloud = mockk<CloudLibraryRepository>(relaxed = true)
        private val profiles = mockk<ProfileManager>()

        init {
            every { context.getSharedPreferences("external_playback_pending", Context.MODE_PRIVATE) } returns prefs
            every { prefs.edit() } returns editor
            every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); editor }
            every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); editor }
            every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
            every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
            every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
            every { editor.clear() } answers { values.clear(); editor }
            every { editor.apply() } just Runs
            every { prefs.contains(any()) } answers { values.containsKey(firstArg()) }
            every { prefs.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
            every { prefs.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
            every { prefs.getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { profiles.activeProfileId } returns MutableStateFlow(1)
            every { scrobbles.preparePlaybackOrigin(any()) } answers { TrackingPlaybackOriginCapture(firstArg(), emptyList()) }
            every { settings.playerSettings } returns flowOf(PlayerSettings(streamAutoPlayNextEpisodeEnabled = false,
                externalPlayerSendSkipSegments = false))
            every { repository.getProgress(any(), any()) } returns flowOf(null)
            every { repository.getEpisodeProgress(any(), any(), any(), any()) } returns flowOf(null)
            coEvery { repository.saveProgress(any(), any(), any()) } coAnswers {
                saved += firstArg<WatchProgress>(); savedProfiles += arg<Int>(1)
            }
            coEvery { repository.savePlaybackProgress(any(), any(), any(), any(), any()) } coAnswers {
                saved += firstArg<WatchProgress>(); savedProfiles += arg<Int>(1)
            }
            val catalog = mockk<Meta>()
            every { catalog.videos } returns emptyList()
            every { meta.getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Success(catalog))
            coEvery { shuffle.isEnabled(any()) } returns false
            coEvery { shuffle.externalSnapshot(any(), any(), any()) } coAnswers {
                val item = firstArg<ExternalPlaybackMetadata>()
                ExternalNextEpisodeSnapshot(true, "${item.contentId}:1:8", 1, 8)
            }
        }

        val tracker = newTracker()

        fun newTracker() = ExternalPlaybackTracker(context, repository, scrobbles, meta, shuffle, settings,
            mockk(), cloud, cloudProgress, cloudSessions, profiles, authority)

        fun deferDuration(metadata: ExternalPlaybackMetadata): Backfill {
            val result = Backfill(CompletableDeferred(), CompletableDeferred())
            val delayed = flow { result.entered.complete(Unit); emit(result.release.await()) }
            if (metadata.season != null && metadata.episode != null) {
                every { repository.getEpisodeProgress(metadata.contentId, metadata.season, metadata.episode, metadata.profileId) } returns delayed
            } else {
                every { repository.getProgress(metadata.contentId, metadata.profileId) } returns delayed
            }
            return result
        }
    }

    private fun metadata(id: String, episode: Int? = null) = ExternalPlaybackMetadata(id,
        if (episode == null) "movie" else "series", "Fixture $id", null, null, null,
        if (episode == null) id else "$id:1:$episode", episode?.let { 1 }, episode, null, null, 1)

    private fun progress(metadata: ExternalPlaybackMetadata, duration: Long) = WatchProgress(
        metadata.contentId, metadata.contentType, metadata.contentName, null, null, null,
        metadata.videoId, metadata.season, metadata.episode, null, 30_000L, duration, 1L)
}
