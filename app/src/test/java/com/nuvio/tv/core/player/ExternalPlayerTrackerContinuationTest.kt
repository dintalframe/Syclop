package com.nuvio.tv.core.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
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

    private data class Backfill(
        val entered: CompletableDeferred<Unit>,
        val release: CompletableDeferred<WatchProgress?>
    )

    private class Harness {
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
            every { editor.clear() } answers { values.clear(); editor }
            every { editor.apply() } just Runs
            every { prefs.contains(any()) } answers { values.containsKey(firstArg()) }
            every { prefs.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
            every { prefs.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
            every { profiles.activeProfileId } returns MutableStateFlow(1)
            every { settings.playerSettings } returns flowOf(PlayerSettings(streamAutoPlayNextEpisodeEnabled = false))
            every { repository.getProgress(any(), any()) } returns flowOf(null)
            every { repository.getEpisodeProgress(any(), any(), any(), any()) } returns flowOf(null)
            coEvery { repository.saveProgress(any(), any(), any()) } coAnswers {
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
            mockk(), cloud, cloudProgress, cloudSessions, profiles)

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
