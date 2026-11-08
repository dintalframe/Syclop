package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.Renderer

internal fun interface ExoEndProbeRequest { fun cancel() }
internal fun interface ExoRendererEndProbe {
    fun read(player: ExoPlayer, result: (ExoRendererEndSnapshot?) -> Unit): ExoEndProbeRequest
}

/** Renderer state belongs to the playback thread. Never block the application thread for it. */
private fun readExoRendererEndState(player: ExoPlayer, result: (ExoRendererEndSnapshot?) -> Unit): ExoEndProbeRequest {
    val applicationHandler = Handler(player.applicationLooper)
    val renderers = (0 until player.rendererCount).map(player::getRenderer)
    val counters = player.videoDecoderCounters
    val message = player.createMessage(PlayerMessage.Target { _, _ ->
        val snapshot = runCatching {
            counters?.ensureUpdated()
            ExoRendererEndSnapshot(
                renderers.map { renderer ->
                    ExoRendererEndState(renderer.trackType, renderer.state != Renderer.STATE_DISABLED,
                        renderer.hasReadStreamToEnd(), renderer.isCurrentStreamFinal, renderer.isEnded)
                }, counters?.renderedOutputBufferCount?.toLong(), counters?.let {
                    it.renderedOutputBufferCount.toLong() + it.skippedOutputBufferCount + it.droppedBufferCount
                }, counters
            )
        }.getOrNull()
        applicationHandler.post { result(snapshot) }
    }).setLooper(player.playbackLooper).send()
    return ExoEndProbeRequest { message.cancel() }
}

/** A one-off video deselection lets Media3 emit its own ENDED event through the normal app path. */
internal class ExoStuckVideoEndRecovery(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val probe: ExoRendererEndProbe = ExoRendererEndProbe(::readExoRendererEndState),
    private val policy: ExoStuckVideoEndPolicy = ExoStuckVideoEndPolicy()
) {
    private var player: ExoPlayer? = null
    private var mediaIdentity: Any? = null
    private var generation = 0L
    private var request: ExoEndProbeRequest? = null
    private var pendingTicket: Long? = null
    private var requestedAtMs = 0L
    private var nextTicket = 0L
    private var videoDisabledByRecovery = false
    val hasSynthesizedEnd: Boolean
        get() = videoDisabledByRecovery

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            invalidate(restoreVideo = true)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { invalidate(restoreVideo = true) }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { invalidate() }
        override fun onTracksChanged(tracks: Tracks) { invalidate() }
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) { invalidate() }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) invalidate()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_READY) invalidate()
        }
    }

    fun poll(player: ExoPlayer, mediaIdentity: Any, context: () -> ExoEndOfStreamContext, isCurrent: () -> Boolean) {
        if (this.player !== player) {
            release()
            this.player = player
            this.mediaIdentity = mediaIdentity
            player.addListener(listener)
        } else if (this.mediaIdentity != mediaIdentity) {
            invalidate(restoreVideo = true)
            this.mediaIdentity = mediaIdentity
        }
        if (!isCurrent() || !context().mayInspectRenderers()) {
            invalidate()
            return
        }
        if (videoDisabledByRecovery || C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes) return
        val now = nowMs()
        // A playback thread that cannot answer is not evidence of EOF. Keep the queue bounded.
        if (pendingTicket != null && now - requestedAtMs < 2_000L) return
        if (pendingTicket != null) invalidate()
        val generation = this.generation
        val ticket = ++nextTicket
        pendingTicket = ticket
        requestedAtMs = now
        val nextRequest = try {
            probe.read(player) { snapshot ->
                if (this.player !== player || this.generation != generation || pendingTicket != ticket) return@read
                pendingTicket = null
                request = null
                if (!isCurrent() || snapshot == null) { invalidate(); return@read }
                val latest = context()
                if (!policy.shouldRecover(latest, snapshot, nowMs())) return@read
                val parameters = player.trackSelectionParameters
                if (C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes) return@read
                videoDisabledByRecovery = true
                player.trackSelectionParameters = parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                Log.i("PlayerEndRecovery", "Exhausted video renderer did not finish; allowing normal playback end")
            }
        } catch (_: RuntimeException) {
            invalidate()
            null
        }
        // A test/probe may deliver synchronously; do not resurrect an already consumed request.
        if (pendingTicket == ticket) request = nextRequest
    }

    private fun invalidate(restoreVideo: Boolean = false) {
        generation++
        pendingTicket = null
        request?.cancel()
        request = null
        policy.reset()
        if (restoreVideo && videoDisabledByRecovery) {
            videoDisabledByRecovery = false
            player?.let { current ->
                if (!current.isReleased && C.TRACK_TYPE_VIDEO in current.trackSelectionParameters.disabledTrackTypes) {
                    // Restore only our false->true change, preserving current audio/text overrides.
                    current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                }
            }
        }
    }

    fun release() {
        invalidate()
        runCatching { player?.removeListener(listener) }
        player = null
        mediaIdentity = null
        videoDisabledByRecovery = false
    }
}

internal fun PlayerRuntimeController.maybeRecoverExoEndOfStream(player: ExoPlayer) {
    val source = currentStreamUrl
    val videoId = currentVideoId
    exoEndOfStreamRecovery.poll(player, source to videoId, context = {
        val timeline = player.currentTimeline
        val window = Timeline.Window()
        val finalPeriod = !timeline.isEmpty && player.currentMediaItemIndex in 0 until timeline.windowCount &&
            timeline.getWindow(player.currentMediaItemIndex, window).lastPeriodIndex == player.currentPeriodIndex &&
            player.currentMediaItemIndex == timeline.windowCount - 1
        ExoEndOfStreamContext(player.currentPosition, player.duration, player.playbackState, player.playWhenReady,
            player.isLoading, player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic,
            player.isPlayingAd, finalPeriod, player.repeatMode,
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            hasRenderedFirstFrame, player.playerError != null || !_uiState.value.error.isNullOrBlank(),
            userPausedManually, pendingSeekFlush || isScrubbingModeActive || pendingPreviewSeekPosition != null ||
                _uiState.value.pendingSeekPosition != null || pendingSeekTelemetryAwaitingFirstFrame)
    }, isCurrent = {
        _exoPlayer === player && !isReleasingPlayer && !isUsingMpvEngine() &&
            source == currentStreamUrl && videoId == currentVideoId && !player.isReleased
    })
}
