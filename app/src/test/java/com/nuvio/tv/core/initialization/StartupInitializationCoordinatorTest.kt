package com.nuvio.tv.core.initialization

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupInitializationCoordinatorTest {
    @Test
    fun `retry restarts failed import while saved account warmup is still running`() = runBlocking {
        val store = MemoryInitializationStore()
        val preferences = InitializationPreferences(mockk<ProfileDataStoreFactory> {
            every { get(1, any()) } returns store
        })
        val saved = InitializationMarkerKey.fromAccount(1, InitializationProvider.TRAKT, "saved-viewer")!!
        val failed = InitializationMarkerKey.fromAccount(1, InitializationProvider.MAL, "retry-viewer")!!
        assertTrue(preferences.complete(saved) { true })
        val warming = CompletableDeferred<Unit>()
        val trakt = mockk<TraktInitializationParticipant> {
            every { provider } returns InitializationProvider.TRAKT
            every { accountChanges } returns emptyFlow()
            coEvery { capture(1, 0) } returns session(saved) {
                warming.complete(Unit)
                awaitCancellation()
            }
        }
        val simkl = mockk<SimklInitializationParticipant> {
            every { provider } returns InitializationProvider.SIMKL
            every { accountChanges } returns emptyFlow()
            coEvery { capture(1, 0) } returns null
        }
        var attempts = 0
        val mal = mockk<MalInitializationParticipant> {
            every { provider } returns InitializationProvider.MAL
            every { accountChanges } returns emptyFlow()
            coEvery { capture(1, 0) } returns session(failed) {
                if (attempts++ == 0) throw InitializationReadException(InitializationFailure.NETWORK)
                InitializationReadResult(2)
            }
        }
        val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
            every { activeProfileSelectionRevision } returns 0L
        }
        val coordinator = StartupInitializationCoordinator(profiles, preferences, trakt, simkl, mal)
        try {
            coordinator.onHomeEntry(1, 0)
            withTimeout(5_000) { warming.await() }
            assertEquals(InitializationPhase.NEEDS_ATTENTION, coordinator.state.value.phase)
            assertFalse(preferences.contains(failed))

            coordinator.retry()

            withTimeoutOrNull(2_000) { coordinator.state.first { it.phase == InitializationPhase.READY } }
            assertEquals("Retry must run while an optional saved-account warmup is pending",
                InitializationPhase.READY, coordinator.state.value.phase)
            assertEquals(2, attempts)
            assertTrue(preferences.contains(saved))
            assertTrue(preferences.contains(failed))
        } finally {
            coordinator.prepareForAppRestart()
        }
    }

    @Test
    fun `leaving and returning to a profile cannot revive its previous selection import`() = runBlocking {
        val key = marker("same-profile-viewer")
        val old = HeldRead(key, 99)
        val current = HeldRead(key, 2)
        val f = Fixture { profile, selection ->
            when (profile to selection) {
                1 to 0L -> old.session
                1 to 2L -> current.session
                else -> null
            }
        }
        try {
            f.coordinator.onHomeEntry(1, 0)
            withTimeout(5_000) { old.entered.await() }
            val outgoing = f.work()

            f.coordinator.onLeaveHome()
            f.activeProfile.value = 2
            f.selection.set(1)
            f.coordinator.onHomeEntry(2, 1)
            f.ready(2, 1)
            f.coordinator.onLeaveHome()
            f.activeProfile.value = 1
            f.selection.set(2)
            f.coordinator.onHomeEntry(1, 2)
            withTimeout(5_000) { current.entered.await() }

            old.release.complete(Unit)
            withTimeout(5_000) { outgoing.join() }

            assertEquals(1, f.coordinator.state.value.profileId)
            assertEquals(2L, f.coordinator.state.value.selectionRevision)
            assertEquals(InitializationPhase.IMPORTING, f.coordinator.state.value.phase)
            assertFalse("A stale read must not mark the returning profile complete", f.preferences.contains(key))

            current.release.complete(Unit)
            f.ready(1, 2)
            assertEquals(2, f.coordinator.state.value.stages.single().count)
            assertTrue(f.preferences.contains(key))
        } finally {
            old.release.complete(Unit)
            current.release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun `account replacement starts a new import and rejects a noncooperative old completion`() = runBlocking {
        val oldKey = marker("old-account")
        val newKey = marker("replacement-account")
        val old = HeldRead(oldKey, 99)
        val current = HeldRead(newKey, 3)
        val revision = AtomicInteger()
        val f = Fixture { _, _ -> if (revision.get() == 0) old.session else current.session }
        try {
            f.coordinator.onHomeEntry(1, 0)
            withTimeout(5_000) { old.entered.await() }
            val outgoing = f.work()

            revision.incrementAndGet()
            f.accountEvents.send("replacement-account-event")
            withTimeout(5_000) { current.entered.await() }
            old.release.complete(Unit)
            withTimeout(5_000) { outgoing.join() }

            assertEquals(InitializationPhase.IMPORTING, f.coordinator.state.value.phase)
            assertFalse(f.preferences.contains(oldKey))
            assertFalse(f.preferences.contains(newKey))

            current.release.complete(Unit)
            f.ready(1, 0)
            assertEquals(3, f.coordinator.state.value.stages.single().count)
            assertTrue(f.preferences.contains(newKey))
            assertFalse(f.preferences.contains(oldKey))
        } finally {
            old.release.complete(Unit)
            current.release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun `Continue survives same-owner Home reentry and a noncooperative outgoing response`() = runBlocking {
        val key = marker("continued-viewer")
        val old = HeldRead(key, 99)
        val f = Fixture { _, _ -> old.session }
        try {
            f.coordinator.onHomeEntry(1, 0)
            withTimeout(5_000) { old.entered.await() }
            val outgoing = f.work()

            f.coordinator.continueWithSavedData()
            f.coordinator.onLeaveHome()
            f.coordinator.onHomeEntry(1, 0)
            old.release.complete(Unit)
            withTimeout(5_000) { outgoing.join() }

            assertEquals(InitializationPhase.CONTINUED, f.coordinator.state.value.phase)
            assertEquals(1, f.coordinator.state.value.profileId)
            assertEquals(0L, f.coordinator.state.value.selectionRevision)
            assertFalse(f.coordinator.state.value.blocksHome)
            assertFalse(f.coordinator.shouldDeferForegroundRefresh)
            assertFalse(f.preferences.contains(key))
        } finally {
            old.release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun `a saved import stays ready when a fresh coordinator optional warmup fails offline`() = runBlocking {
        val key = marker("saved-offline-viewer")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture { _, _ -> session(key) {
            entered.complete(Unit)
            release.await()
            throw InitializationReadException(InitializationFailure.NETWORK)
        } }
        try {
            assertTrue(f.preferences.complete(key) { true })
            f.coordinator.onHomeEntry(1, 0)
            withTimeout(5_000) { entered.await() }
            val warming = f.work()

            assertEquals(InitializationPhase.READY, f.coordinator.state.value.phase)
            assertFalse(f.coordinator.state.value.blocksHome)
            release.complete(Unit)
            withTimeout(5_000) { warming.join() }

            assertEquals(InitializationPhase.READY, f.coordinator.state.value.phase)
            assertEquals(InitializationStageStatus.COMPLETE, f.coordinator.state.value.stages.single().status)
            assertFalse(f.coordinator.shouldDeferForegroundRefresh)
            assertTrue(f.preferences.contains(key))
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    private class HeldRead(key: InitializationMarkerKey, count: Int) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = object : InitializationSession {
            override val marker = key
            override val scope = InitializationReadScope(key.provider, "held-synthetic") { true }
            override suspend fun read(progress: (InitializationStep) -> Unit) = withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                InitializationReadResult(count)
            }
        }
    }

    private class Fixture(captureSession: suspend (Int, Long) -> InitializationSession?) {
        val activeProfile = MutableStateFlow(1)
        val selection = AtomicLong()
        val accountEvents = Channel<String>(Channel.UNLIMITED)
        private val stores = mapOf(1 to MemoryInitializationStore(), 2 to MemoryInitializationStore())
        val preferences = InitializationPreferences(mockk<ProfileDataStoreFactory> {
            every { get(any(), "first_use_initialization") } answers { stores.getValue(firstArg()) }
        })
        private val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns this@Fixture.activeProfile
            every { activeProfileSelectionRevision } answers { selection.get() }
        }
        private val trakt = mockk<TraktInitializationParticipant> {
            every { provider } returns InitializationProvider.TRAKT
            every { accountChanges } returns accountEvents.receiveAsFlow()
            coEvery { capture(any(), any()) } coAnswers { captureSession(firstArg(), secondArg()) }
        }
        private val simkl = mockk<SimklInitializationParticipant> {
            every { provider } returns InitializationProvider.SIMKL
            every { accountChanges } returns flowOf("disconnected")
            coEvery { capture(any(), any()) } returns null
        }
        private val mal = mockk<MalInitializationParticipant> {
            every { provider } returns InitializationProvider.MAL
            every { accountChanges } returns flowOf("disconnected")
            coEvery { capture(any(), any()) } returns null
        }
        val coordinator = StartupInitializationCoordinator(profiles, preferences, trakt, simkl, mal)
        private val observedWork = mutableListOf<Job>()

        fun work(): Job = (StartupInitializationCoordinator::class.java.getDeclaredField("work")
            .apply { isAccessible = true }.get(coordinator) as Job).also(observedWork::add)

        suspend fun ready(profile: Int, selection: Long) {
            withTimeout(5_000) { coordinator.state.first {
                it.profileId == profile && it.selectionRevision == selection && it.phase == InitializationPhase.READY
            } }
        }

        suspend fun close() {
            coordinator.prepareForAppRestart()
            accountEvents.close()
            observedWork.forEach { withTimeout(5_000) { it.join() } }
        }
    }

    private fun marker(identity: String) =
        requireNotNull(InitializationMarkerKey.fromAccount(1, InitializationProvider.TRAKT, identity))

    private fun session(key: InitializationMarkerKey, read: suspend () -> InitializationReadResult) =
        object : InitializationSession {
            override val marker = key
            override val scope = InitializationReadScope(key.provider, "synthetic") { true }
            override suspend fun read(progress: (InitializationStep) -> Unit) = read()
        }
}
