package com.nuvio.tv.core.initialization

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    private fun session(key: InitializationMarkerKey, read: suspend () -> InitializationReadResult) =
        object : InitializationSession {
            override val marker = key
            override val scope = InitializationReadScope(key.provider, "synthetic") { true }
            override suspend fun read(progress: (InitializationStep) -> Unit) = read()
        }
}
