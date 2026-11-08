package com.nuvio.tv.ui.screens.player

internal const val CLEANUP_EOF_TOLERANCE_MS = 2_000L

internal data class PlaybackCleanupSource(
    val url: String,
    val videoId: String?,
    val cloudSessionToken: String?
)

internal class PlaybackCleanupGate {
    private var handledSource: PlaybackCleanupSource? = null

    fun suppress(source: PlaybackCleanupSource) {
        handledSource = source
    }

    fun claim(
        source: PlaybackCleanupSource,
        validRendererEof: Boolean,
        positionMs: Long,
        durationMs: Long
    ): Boolean {
        if (!validRendererEof || handledSource == source) return false
        if (positionMs <= 0L || durationMs <= 0L ||
            durationMs - positionMs !in -CLEANUP_EOF_TOLERANCE_MS..CLEANUP_EOF_TOLERANCE_MS
        ) return false
        handledSource = source
        return true
    }
}
