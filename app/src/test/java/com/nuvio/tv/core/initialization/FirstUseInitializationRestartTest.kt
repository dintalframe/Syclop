package com.nuvio.tv.core.initialization

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstUseInitializationRestartTest {
    @Test
    fun `restart keeps durable completion and warms account history again`() = runTest {
        val markers = MemoryMarkers()
        val initialization = FirstUseInitialization(markers)
        var reads = 0
        val participant = Participant { reads++; InitializationReadResult(8) }

        initialization.reset(1, 0)
        initialization.run(1, 0, listOf(participant)) { true }
        assertEquals(1, reads)
        assertEquals(1, markers.completions)
        val savedMarkers = markers.keys.toSet()

        initialization.resetForAppRestart()
        assertEquals(savedMarkers, markers.keys)
        initialization.reset(1, 0)
        initialization.run(1, 0, listOf(participant)) { true }

        assertEquals(InitializationPhase.READY, initialization.state.value.phase)
        assertEquals(2, reads)
        assertEquals(1, markers.completions)
        assertEquals(savedMarkers, markers.keys)
        assertEquals(0, markers.invalidations)
    }

    @Test
    fun `old noncooperative import cannot replace same profile restart results`() = runTest {
        val markers = MemoryMarkers()
        val initialization = FirstUseInitialization(markers)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldParticipant = Participant {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            InitializationReadResult(99)
        }
        initialization.reset(1, 0)
        val oldRun = launch {
            try {
                initialization.run(1, 0, listOf(oldParticipant)) { true }
            } catch (_: InitializationReadException) {
                // The previous activity's import is no longer eligible to commit.
            }
        }
        entered.await()

        initialization.resetForAppRestart()
        initialization.reset(1, 0)
        initialization.run(1, 0, listOf(Participant { InitializationReadResult(4) })) { true }
        release.complete(Unit)
        oldRun.join()

        assertEquals(InitializationPhase.READY, initialization.state.value.phase)
        assertEquals(4, initialization.state.value.stages.single().count)
        assertEquals(1, markers.completions)
        assertEquals(1, markers.keys.size)
        assertTrue(markers.keys.single().startsWith("v1:TRAKT:"))
    }

    private class MemoryMarkers : InitializationMarkerStore {
        val keys = mutableSetOf<String>()
        var completions = 0
        var invalidations = 0
        override suspend fun contains(key: InitializationMarkerKey): Boolean = key.stored in keys
        override suspend fun complete(key: InitializationMarkerKey, current: () -> Boolean): Boolean {
            if (!current()) return false
            completions++
            keys += key.stored
            return true
        }
        override suspend fun invalidateProvider(
            profileId: Int,
            provider: InitializationProvider,
            current: () -> Boolean
        ) {
            if (current()) invalidations++
        }
    }

    private class Participant(
        private val read: suspend () -> InitializationReadResult
    ) : InitializationParticipant {
        override val provider = InitializationProvider.TRAKT
        override val accountChanges = flowOf("synthetic")
        private val marker = InitializationMarkerKey.fromAccount(1, provider, "synthetic-user")!!
        override suspend fun capture(profileId: Int, selectionRevision: Long): InitializationSession =
            object : InitializationSession {
                override val marker = this@Participant.marker
                override val scope = InitializationReadScope(provider, "synthetic") { true }
                override suspend fun read(progress: (InitializationStep) -> Unit): InitializationReadResult =
                    this@Participant.read()
            }
    }
}
