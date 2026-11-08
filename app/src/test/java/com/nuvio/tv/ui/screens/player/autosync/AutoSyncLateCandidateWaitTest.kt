package com.nuvio.tv.ui.screens.player.autosync

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSyncLateCandidateWaitTest {
    @Test fun `late provider candidate joins after the initial pool is exhausted`() = runTest {
        var available = false
        val result = async { AutoSyncLateCandidateWait(1_000, 100).awaitNext { available } }
        runCurrent(); assertFalse(result.isCompleted)
        advanceTimeBy(400); available = true; runCurrent()
        assertTrue(result.await())
        assertEquals(400L, testScheduler.currentTime)
    }

    @Test fun `the same wait budget is finite across repeated pool exhaustions`() = runTest {
        val window = AutoSyncLateCandidateWait(1_000, 100)
        var available = false
        val first = async { window.awaitNext { available } }
        advanceTimeBy(400); available = true; runCurrent(); assertTrue(first.await())
        available = false
        val second = async { window.awaitNext { available } }
        advanceTimeBy(601); runCurrent()
        assertFalse(second.await())
        assertEquals(1_001L, testScheduler.currentTime)
        assertFalse(window.awaitNext { false })
        assertEquals(1_001L, testScheduler.currentTime)
    }

    @Test fun `switching subtitle or leaving playback cancels the idle wait promptly`() = runTest {
        var reads = 0
        val result = async { AutoSyncLateCandidateWait().awaitNext { reads++; false } }
        runCurrent(); result.cancelAndJoin()
        val readsAfterCancel = reads
        advanceTimeBy(20_000); runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(readsAfterCancel, reads)
    }

    @Test fun `already available candidates do not add a wait`() = runTest {
        assertTrue(AutoSyncLateCandidateWait().awaitNext { true })
        assertEquals(0L, testScheduler.currentTime)
    }
}
