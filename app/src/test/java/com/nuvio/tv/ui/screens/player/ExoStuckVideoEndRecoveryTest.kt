package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class ExoStuckVideoEndRecoveryTest {
    @Test fun `only exhausted video is disabled and seek restores it without losing current audio choice`() {
        val h = Harness()
        h.sample()
        h.now = 5_000L
        h.sample()
        assertTrue(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
        h.parameters = h.parameters.buildUpon().setPreferredAudioLanguage("ja").build()
        h.listener.captured.onPositionDiscontinuity(mockk(), mockk(), Player.DISCONTINUITY_REASON_SEEK)
        assertFalse(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
        assertEquals(listOf("ja"), h.parameters.preferredAudioLanguages)
    }
    @Test fun `already disabled video belongs to its caller and is never enabled by recovery`() {
        val h = Harness()
        h.parameters = h.parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        h.poll()
        assertTrue(h.callbacks.isEmpty())
        h.listener.captured.onPositionDiscontinuity(mockk(), mockk(), Player.DISCONTINUITY_REASON_SEEK)
        assertTrue(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
    }
    @Test fun `a late playback-thread reply cannot affect a replacement media visit`() {
        val h = Harness()
        h.poll()
        val old = h.callbacks.last()
        h.identity = "replacement"
        h.now = 5_000L
        h.poll()
        assertEquals(1, h.cancelled)
        old(ExoStuckVideoEndPolicyTest.exhausted())
        assertFalse(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
        h.callbacks.last()(ExoStuckVideoEndPolicyTest.exhausted())
        assertFalse(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
    }
    @Test fun `pause after probe dispatch prevents a delayed reply from completing playback`() {
        val h = Harness()
        h.sample()
        h.now = 5_000L
        h.poll()
        h.context = h.context.copy(userPaused = true, playWhenReady = false)
        h.callbacks.last()(ExoStuckVideoEndPolicyTest.exhausted())
        assertFalse(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
    }
    @Test fun `unresponsive playback thread has bounded pending work and supplies no EOF proof`() {
        val h = Harness()
        h.poll()
        for (now in listOf(500L, 1_000L, 1_500L)) { h.now = now; h.poll() }
        assertEquals(1, h.callbacks.size)
        h.now = 2_000L
        h.poll()
        assertEquals(2, h.callbacks.size)
        assertEquals(1, h.cancelled)
        h.callbacks.first()(ExoStuckVideoEndPolicyTest.exhausted())
        assertFalse(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
        h.recovery.release()
        h.callbacks.last()(ExoStuckVideoEndPolicyTest.exhausted())
        assertFalse(C.TRACK_TYPE_VIDEO in h.parameters.disabledTrackTypes)
    }
    private class Harness {
        val player = mockk<ExoPlayer>()
        val listener = slot<Player.Listener>()
        var parameters = TrackSelectionParameters.Builder().build()
        var now = 0L
        var identity = "original"
        var context = ExoStuckVideoEndPolicyTest.terminal()
        var cancelled = 0
        val callbacks = mutableListOf<(ExoRendererEndSnapshot?) -> Unit>()
        val recovery = ExoStuckVideoEndRecovery({ now }, ExoRendererEndProbe { _, result ->
            callbacks += result
            ExoEndProbeRequest { cancelled++ }
        })
        init {
            every { player.addListener(capture(listener)) } just Runs
            every { player.removeListener(any()) } just Runs
            every { player.isReleased } returns false
            every { player.trackSelectionParameters } answers { parameters }
            every { player.trackSelectionParameters = any() } answers { parameters = firstArg() }
        }
        fun poll() = recovery.poll(player, identity, { context }, { true })
        fun sample() { poll(); callbacks.last()(ExoStuckVideoEndPolicyTest.exhausted()) }
    }
}
