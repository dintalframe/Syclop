package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Player

/** A clock reaching the advertised duration is not, by itself, evidence of an exhausted file. */
internal data class ExoEndOfStreamContext(
    val positionMs: Long,
    val durationMs: Long,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val isLoading: Boolean,
    val isLiveOrDynamic: Boolean,
    val isPlayingAd: Boolean,
    val isFinalPeriod: Boolean,
    val repeatMode: Int,
    val playbackSuppressed: Boolean,
    val hasRenderedFirstFrame: Boolean,
    val hasError: Boolean,
    val userPaused: Boolean,
    val seekPending: Boolean
) {
    fun mayInspectRenderers(): Boolean =
        durationMs >= 121_000L && positionMs >= durationMs &&
            playbackState == Player.STATE_READY && playWhenReady && !isLoading &&
            !isLiveOrDynamic && !isPlayingAd && isFinalPeriod && repeatMode == Player.REPEAT_MODE_OFF &&
            !playbackSuppressed && hasRenderedFirstFrame && !hasError && !userPaused && !seekPending
}

internal data class ExoRendererEndState(
    val trackType: Int,
    val enabled: Boolean,
    val readStreamToEnd: Boolean,
    val currentStreamFinal: Boolean,
    val ended: Boolean
)

internal data class ExoRendererEndSnapshot(
    val renderers: List<ExoRendererEndState>,
    val videoRenderedFrames: Long?,
    val videoOutputBuffers: Long?,
    val videoCounterIdentity: Any? = null
) {
    fun hasOnlyStuckExhaustedVideo(): Boolean {
        val enabled = renderers.filter { it.enabled }
        val video = enabled.filter { it.trackType == C.TRACK_TYPE_VIDEO }
        // Do not interfere with prewarming/multiple video renderers or an unobserved decoder.
        return video.size == 1 && video.single().let {
            it.readStreamToEnd && it.currentStreamFinal && !it.ended
        } && enabled.filter { it.trackType != C.TRACK_TYPE_VIDEO }.all { it.ended } &&
            (videoRenderedFrames ?: 0L) > 0L && videoOutputBuffers != null
    }
}

/** Requires real input EOF and five seconds without video output, at or after the file's end. */
internal class ExoStuckVideoEndPolicy(private val quietPeriodMs: Long = 5_000L) {
    private var quietSinceMs: Long? = null
    private var durationMs: Long? = null
    private var outputBuffers: Long? = null
    private var counterIdentity: Any? = null
    private var recovered = false

    fun reset() {
        quietSinceMs = null
        durationMs = null
        outputBuffers = null
        counterIdentity = null
        recovered = false
    }

    fun shouldRecover(context: ExoEndOfStreamContext, renderers: ExoRendererEndSnapshot, nowMs: Long): Boolean {
        if (recovered) return false
        if (!context.mayInspectRenderers() || !renderers.hasOnlyStuckExhaustedVideo()) {
            quietSinceMs = null
            durationMs = null
            outputBuffers = null
            counterIdentity = null
            return false
        }
        if (durationMs != context.durationMs || outputBuffers != renderers.videoOutputBuffers ||
            counterIdentity !== renderers.videoCounterIdentity ||
            quietSinceMs == null || nowMs < checkNotNull(quietSinceMs)) {
            durationMs = context.durationMs
            outputBuffers = renderers.videoOutputBuffers
            counterIdentity = renderers.videoCounterIdentity
            quietSinceMs = nowMs
            return false
        }
        if (nowMs - checkNotNull(quietSinceMs) < quietPeriodMs) return false
        recovered = true
        return true
    }
}
