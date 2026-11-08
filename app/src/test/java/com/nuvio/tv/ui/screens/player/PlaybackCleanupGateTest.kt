package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCleanupGateTest {
    private val first = PlaybackCleanupSource("https://example.test/a", "video-a", null)
    private val second = PlaybackCleanupSource("https://example.test/b", "video-b", "cloud-session")

    @Test
    fun actualEofCanClaimOnlyOnceForTheCurrentSource() {
        val gate = PlaybackCleanupGate()

        assertTrue(gate.claim(first, validRendererEof = true, positionMs = 179_600, durationMs = 180_000))
        assertFalse(gate.claim(first, validRendererEof = true, positionMs = 180_000, durationMs = 180_000))
    }

    @Test
    fun nonNaturalSignalsDoNotClaimOrConsumeTheSource() {
        val gate = PlaybackCleanupGate()

        assertFalse(gate.claim(first, validRendererEof = false, positionMs = 180_000, durationMs = 180_000))
        assertTrue(gate.claim(first, validRendererEof = true, positionMs = 180_000, durationMs = 180_000))
    }

    @Test
    fun skippingOneSourceDoesNotAllowItsEndOrSuppressTheNextSource() {
        val gate = PlaybackCleanupGate()
        gate.suppress(first)

        assertFalse(gate.claim(first, validRendererEof = true, positionMs = 180_000, durationMs = 180_000))
        assertTrue(gate.claim(second, validRendererEof = true, positionMs = 180_000, durationMs = 180_000))
    }

    @Test
    fun eofFromAnIncompleteStreamDoesNotClaimCleanup() {
        val gate = PlaybackCleanupGate()

        assertFalse(gate.claim(first, validRendererEof = true, positionMs = 95_000, durationMs = 180_000))
        assertFalse(gate.claim(first, validRendererEof = true, positionMs = 95_000, durationMs = 0))
        assertTrue(gate.claim(first, validRendererEof = true, positionMs = 180_000, durationMs = 180_000))
    }
}
