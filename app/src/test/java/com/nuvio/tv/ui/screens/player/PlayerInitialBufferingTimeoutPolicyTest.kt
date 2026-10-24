package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class PlayerInitialBufferingTimeoutPolicyTest {
    private fun input(duration: Long = C.TIME_UNSET, buffered: Long = 0L, elapsed: Long = 60_000L) =
        PlayerStallWatchdogPolicy.Input(buffered, 0L, duration, stalledForMs = 60_000L,
            hasRenderedFirstFrame = false, initialBufferingElapsedMs = elapsed)

    @Test fun `unknown duration zero bytes reaches bounded initial timeout`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.TimeoutInitialBuffering, PlayerStallWatchdogPolicy.evaluate(input()))
    }
    @Test fun `known duration no buffered data reaches initial timeout`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.TimeoutInitialBuffering, PlayerStallWatchdogPolicy.evaluate(input(duration=600_000L)))
    }
    @Test fun `some buffered progress does not bypass absolute initial deadline`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.TimeoutInitialBuffering,
            PlayerStallWatchdogPolicy.evaluate(input(duration=600_000L,buffered=2000L).copy(stalledForMs=0L)))
    }
    @Test fun `before deadline unknown duration never requests unsafe seek`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            PlayerStallWatchdogPolicy.evaluate(input(elapsed=59_999L)))
    }
    @Test fun `manual pause does not produce startup timeout`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            PlayerStallWatchdogPolicy.evaluate(input().copy(userPausedManually=true)))
    }
    @Test fun `known buffered edge at fifteen seconds cannot seek and terminate initial deadline`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            PlayerStallWatchdogPolicy.evaluate(input(duration=600_000L,buffered=2000L,elapsed=15_000L).copy(stalledForMs=15_000L)))
    }
    @Test fun `post frame rebuffer behavior keeps existing safe seek`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(2250L),
            PlayerStallWatchdogPolicy.evaluate(input(duration=600_000L,buffered=2000L).copy(hasRenderedFirstFrame=true)))
    }
}
