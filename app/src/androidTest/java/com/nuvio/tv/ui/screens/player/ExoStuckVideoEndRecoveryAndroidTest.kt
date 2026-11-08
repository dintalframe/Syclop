package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual bundled Media3 API/looper/ENDED integration; synthetic owned bytes, no codec/network/account. */
@RunWith(AndroidJUnit4::class)
class ExoStuckVideoEndRecoveryAndroidTest {
    @Test fun inputEofWithoutVideoEndedRecoversThroughNativeEndedAndRestoresVideoOnSeek() {
        assumeTrue("Synthetic renderer fixture runs only on an isolated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val renderer = AtomicReference<FixtureVideoRenderer>()
        val player = onMain { createPlayer(renderer, stuck = true) }
        val recovery = ExoStuckVideoEndRecovery(policy = ExoStuckVideoEndPolicy(100L))
        try {
            await { onMain { player.playbackState == Player.STATE_READY && player.currentPosition >= DURATION_MS &&
                renderer.get().readEof.get() && player.videoDecoderCounters != null && !player.isLoading } }
            // Reproduce the original defect on the real engine before enabling the recovery.
            SystemClock.sleep(250L)
            onMain { assertEquals(Player.STATE_READY, player.playbackState); assertNull(player.playerError) }
            await {
                onMain {
                    recovery.poll(player, "owned-eof-fixture", { context(player) }, { !player.isReleased })
                    player.playbackState == Player.STATE_ENDED
                }
            }
            onMain {
                assertTrue(C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes)
                player.pause()
                player.playbackParameters = PlaybackParameters(1f)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage("ja").build()
                player.seekTo(10_000L)
            }
            await { onMain { C.TRACK_TYPE_VIDEO !in player.trackSelectionParameters.disabledTrackTypes && renderer.get().enabled.get() } }
            onMain {
                assertEquals(listOf("ja"), player.trackSelectionParameters.preferredAudioLanguages)
                player.play()
            }
            SystemClock.sleep(250L)
            onMain {
                recovery.poll(player, "owned-eof-fixture", { context(player) }, { !player.isReleased })
                assertEquals(Player.STATE_READY, player.playbackState)
                assertTrue(player.currentPosition < DURATION_MS)
                assertTrue(renderer.get().enabled.get())
                assertFalse(C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes)
            }
        } finally { onMain { recovery.release(); player.release() } }
    }

    @Test fun ordinaryRendererEofStillReachesNativeEndedWithoutRecovery() {
        assumeTrue("Synthetic renderer fixture runs only on an isolated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val renderer = AtomicReference<FixtureVideoRenderer>()
        val player = onMain { createPlayer(renderer, stuck = false) }
        try {
            await { onMain { player.playbackState == Player.STATE_ENDED } }
            onMain {
                assertTrue(renderer.get().readEof.get())
                assertFalse(C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes)
                assertNull(player.playerError)
                player.pause()
                player.playbackParameters = PlaybackParameters(1f)
                player.seekTo(10_000L)
            }
            await { onMain { player.playbackState == Player.STATE_READY && player.currentPosition < DURATION_MS } }
            onMain { assertTrue(renderer.get().enabled.get()); assertFalse(C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes) }
        } finally { onMain { player.release() } }
    }

    private fun createPlayer(reference: AtomicReference<FixtureVideoRenderer>, stuck: Boolean): ExoPlayer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val factory = RenderersFactory { handler, video, _, _, _ ->
            val renderer = FixtureVideoRenderer(VideoRendererEventListener.EventDispatcher(handler, video), stuck)
            reference.set(renderer)
            arrayOf<Renderer>(renderer)
        }
        return ExoPlayer.Builder(instrumentation.targetContext, factory).build().apply {
            // A single locally owned sample, consumed by our renderer rather than a hardware codec.
            // The long declared duration preserves the production placeholder guard; accelerated
            // playback reaches it quickly without weakening the production terminal test.
            val source = SingleSampleMediaSource.Factory(DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) })
                .createMediaSource(MediaItem.SubtitleConfiguration.Builder(Uri.parse("memory://owned-eof-fixture"))
                    .setMimeType(MimeTypes.VIDEO_H264).build(), DURATION_MS * 1_000L)
            setMediaSource(source)
            playbackParameters = PlaybackParameters(1_000f)
            prepare()
            play()
        }
    }

    private fun context(player: ExoPlayer) = ExoEndOfStreamContext(player.currentPosition, player.duration,
        player.playbackState, player.playWhenReady, player.isLoading, player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic,
        player.isPlayingAd, !player.hasNextMediaItem(), player.repeatMode,
        player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        hasRenderedFirstFrame = true, hasError = player.playerError != null, userPaused = false, seekPending = false)

    private class FixtureVideoRenderer(private val events: VideoRendererEventListener.EventDispatcher,
        private val stuck: Boolean) : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private val counters = DecoderCounters()
        val readEof = AtomicBoolean(false)
        val enabled = AtomicBoolean(false)
        override fun getName() = "OwnedEofFixtureRenderer"
        override fun supportsFormat(format: Format) = if (format.sampleMimeType == MimeTypes.VIDEO_H264)
            C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
        override fun isReady() = true
        override fun isEnded() = !stuck && hasReadStreamToEnd() && isCurrentStreamFinal
        override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) { enabled.set(true); events.enabled(counters) }
        override fun onDisabled() { enabled.set(false); events.disabled(counters) }
        override fun onPositionReset(positionUs: Long, joining: Boolean) { readEof.set(false) }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            repeat(3) {
                input.clear()
                val result = readSource(formatHolder, input, 0)
                if (hasReadStreamToEnd()) readEof.set(true)
                when (result) {
                    C.RESULT_FORMAT_READ -> formatHolder.format?.let { events.inputFormatChanged(it, null) }
                    C.RESULT_BUFFER_READ -> if (input.isEndOfStream) readEof.set(true) else {
                        counters.renderedOutputBufferCount++
                    }
                    else -> return
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20L)
        }
        fail("Synthetic Media3 EOF fixture timed out")
    }
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    companion object { private const val DURATION_MS = 180_000L }
}
