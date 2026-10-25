package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerResumeRequestGateTest {
    @Test
    fun `start over during authorization prevents a not yet started resume read`() = runTest {
        val gate = PlayerResumeRequestGate()
        // Production captures this token before authorization and track-preference awaits.
        val preparation = gate.beginRequest()
        val authorization = CompletableDeferred<Unit>()
        val authorizationStarted = CompletableDeferred<Unit>()
        var reads = 0
        val result = async {
            authorizationStarted.complete(Unit)
            authorization.await()
            gate.readIfCurrent(preparation) { reads++; 816_000L }
        }

        authorizationStarted.await()

        gate.invalidate() // The user's explicit Start from beginning action.
        authorization.complete(Unit)

        assertNull(result.await())
        assertEquals(0, reads)
    }

    @Test
    fun `start over during an active saved progress read discards its old result`() = runTest {
        val gate = PlayerResumeRequestGate()
        val preparation = gate.beginRequest()
        val readStarted = CompletableDeferred<Unit>()
        val savedProgress = CompletableDeferred<Long>()
        val result = async {
            gate.readIfCurrent(preparation) {
                readStarted.complete(Unit)
                savedProgress.await()
            }
        }
        readStarted.await()

        gate.invalidate()
        savedProgress.complete(816_000L)

        assertNull(result.await())
    }

    @Test
    fun `current preparation restores the saved position normally`() = runTest {
        val gate = PlayerResumeRequestGate()
        val preparation = gate.beginRequest()

        assertEquals(816_000L, gate.readIfCurrent(preparation) { 816_000L })
    }

    @Test
    fun `start from beginning rejects an earlier delayed resume result`() {
        val gate = PlayerResumeRequestGate()
        val readingOldPosition = gate.beginRequest()
        assertTrue(gate.isCurrent(readingOldPosition))

        gate.invalidate()

        assertFalse(gate.isCurrent(readingOldPosition))
    }

    @Test
    fun `switching episode accepts only the latest resume request`() {
        val gate = PlayerResumeRequestGate()
        val firstEpisode = gate.beginRequest()
        val secondEpisode = gate.beginRequest()

        assertFalse(gate.isCurrent(firstEpisode))
        assertTrue(gate.isCurrent(secondEpisode))
    }

    @Test
    fun `a later deliberate resume remains possible after starting over`() {
        val gate = PlayerResumeRequestGate()
        val obsolete = gate.beginRequest()
        gate.invalidate()
        val newPlayback = gate.beginRequest()

        assertFalse(gate.isCurrent(obsolete))
        assertTrue(gate.isCurrent(newPlayback))
    }
}
