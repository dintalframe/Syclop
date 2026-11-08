package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class ExoStuckVideoEndPolicyTest {
    @Test fun `exhausted stuck video recovers once only after five quiet seconds past actual duration`() {
        val policy = ExoStuckVideoEndPolicy()
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 0L))
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 4_999L))
        assertTrue(policy.shouldRecover(terminal(), exhausted(), 5_000L))
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 10_000L))
    }
    @Test fun `normal last frames and near-end time cannot trigger recovery`() {
        val policy = ExoStuckVideoEndPolicy()
        val beforeEnd = terminal().copy(positionMs = 179_999L)
        assertFalse(policy.shouldRecover(beforeEnd, exhausted(), 0L))
        assertFalse(policy.shouldRecover(beforeEnd, exhausted(), 60_000L))
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 60_001L))
    }
    @Test fun `continuing video output restarts the complete quiet period`() {
        val policy = ExoStuckVideoEndPolicy()
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 0L))
        assertFalse(policy.shouldRecover(terminal(), exhausted().copy(videoOutputBuffers = 2L), 4_999L))
        assertFalse(policy.shouldRecover(terminal(), exhausted().copy(videoOutputBuffers = 2L), 9_998L))
        assertTrue(policy.shouldRecover(terminal(), exhausted().copy(videoOutputBuffers = 2L), 9_999L))
    }
    @Test fun `buffering paused seeking loading and suppressed states cancel accumulated evidence`() {
        val states = listOf(
            terminal().copy(playbackState = Player.STATE_BUFFERING),
            terminal().copy(playbackState = Player.STATE_IDLE),
            terminal().copy(playWhenReady = false), terminal().copy(userPaused = true),
            terminal().copy(seekPending = true), terminal().copy(isLoading = true),
            terminal().copy(playbackSuppressed = true)
        )
        for (state in states) {
            val policy = ExoStuckVideoEndPolicy()
            assertFalse(policy.shouldRecover(terminal(), exhausted(), 0L))
            assertFalse(policy.shouldRecover(state, exhausted(), 4_999L))
            assertFalse(policy.shouldRecover(terminal(), exhausted(), 5_000L))
            assertFalse(policy.shouldRecover(terminal(), exhausted(), 9_999L))
            assertTrue(policy.shouldRecover(terminal(), exhausted(), 10_000L))
        }
    }
    @Test fun `live dynamic ads nonterminal periods repeat and unknown durations never complete`() {
        val states = listOf(terminal().copy(isLiveOrDynamic = true), terminal().copy(isPlayingAd = true),
            terminal().copy(isFinalPeriod = false), terminal().copy(repeatMode = Player.REPEAT_MODE_ONE),
            terminal().copy(durationMs = C.TIME_UNSET), terminal().copy(durationMs = 0L))
        for (state in states) {
            val policy = ExoStuckVideoEndPolicy()
            assertFalse(policy.shouldRecover(state, exhausted(), 0L))
            assertFalse(policy.shouldRecover(state, exhausted(), 60_000L))
        }
    }
    @Test fun `missing first frame fatal errors and placeholders do not mark content watched`() {
        for (state in listOf(terminal().copy(hasRenderedFirstFrame = false), terminal().copy(hasError = true),
            terminal().copy(durationMs = 120_999L))) {
            val policy = ExoStuckVideoEndPolicy()
            assertFalse(policy.shouldRecover(state, exhausted(), 0L))
            assertFalse(policy.shouldRecover(state, exhausted(), 60_000L))
        }
    }
    @Test fun `input EOF final stream and other renderers ended are all required`() {
        val video = exhausted().renderers.first()
        val variants = listOf(
            exhausted().copy(renderers = listOf(video.copy(readStreamToEnd = false))),
            exhausted().copy(renderers = listOf(video.copy(currentStreamFinal = false))),
            exhausted().copy(renderers = listOf(video.copy(ended = true))),
            exhausted().copy(renderers = listOf(video, audio(ended = false))),
            exhausted().copy(renderers = listOf(video, video)),
            exhausted().copy(renderers = listOf(video.copy(enabled = false))),
            exhausted().copy(videoRenderedFrames = null), exhausted().copy(videoRenderedFrames = 0L),
            exhausted().copy(videoOutputBuffers = null)
        )
        for (snapshot in variants) {
            val policy = ExoStuckVideoEndPolicy()
            assertFalse(policy.shouldRecover(terminal(), snapshot, 0L))
            assertFalse(policy.shouldRecover(terminal(), snapshot, 60_000L))
        }
    }
    @Test fun `duration change and counter reset require fresh evidence`() {
        val policy = ExoStuckVideoEndPolicy()
        val beforeReset = exhausted().copy(videoRenderedFrames = 10L, videoOutputBuffers = 10L)
        assertFalse(policy.shouldRecover(terminal(), beforeReset, 0L))
        val changed = terminal().copy(durationMs = 181_000L, positionMs = 181_000L)
        assertFalse(policy.shouldRecover(changed, beforeReset, 4_999L))
        assertFalse(policy.shouldRecover(changed, exhausted(), 9_999L))
        assertFalse(policy.shouldRecover(changed, exhausted(), 14_998L))
        assertTrue(policy.shouldRecover(changed, exhausted(), 14_999L))
    }
    @Test fun `a replacement decoder with equal counters cannot inherit earlier drain evidence`() {
        val policy = ExoStuckVideoEndPolicy()
        val first = exhausted().copy(videoCounterIdentity = Any())
        val replacement = exhausted().copy(videoCounterIdentity = Any())
        assertFalse(policy.shouldRecover(terminal(), first, 0L))
        assertFalse(policy.shouldRecover(terminal(), replacement, 5_000L))
        assertFalse(policy.shouldRecover(terminal(), replacement, 9_999L))
        assertTrue(policy.shouldRecover(terminal(), replacement, 10_000L))
    }
    @Test fun `explicit reset permits a separate playback visit without retaining old quiet time`() {
        val policy = ExoStuckVideoEndPolicy()
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 0L))
        assertTrue(policy.shouldRecover(terminal(), exhausted(), 5_000L))
        policy.reset()
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 6_000L))
        assertTrue(policy.shouldRecover(terminal(), exhausted(), 11_000L))
    }
    @Test fun `monotonic clock reset does not manufacture elapsed quiet time`() {
        val policy = ExoStuckVideoEndPolicy()
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 10_000L))
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 1L))
        assertFalse(policy.shouldRecover(terminal(), exhausted(), 5_000L))
        assertTrue(policy.shouldRecover(terminal(), exhausted(), 5_001L))
    }
    companion object {
        internal fun terminal() = ExoEndOfStreamContext(180_000L, 180_000L, Player.STATE_READY, true, false,
            false, false, true, Player.REPEAT_MODE_OFF, false, true, false, false, false)
        internal fun audio(ended: Boolean) = ExoRendererEndState(C.TRACK_TYPE_AUDIO, true, true, true, ended)
        internal fun exhausted() = ExoRendererEndSnapshot(listOf(
            ExoRendererEndState(C.TRACK_TYPE_VIDEO, true, true, true, false), audio(true)), 1L, 1L)
    }
}
