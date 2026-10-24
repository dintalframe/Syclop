package com.nuvio.tv.domain.model

/** Native-only selected-file facts; never populated from an addon size/season-pack claim. */
data class StreamFileMetadata(val selectedFileBytes: Long? = null,
    val durationMillis: Long? = null, val durationOrigin: StreamDurationOrigin? = null,
    val sourceIdentity: String? = null)
enum class StreamDurationOrigin { CATALOG_MOVIE, CATALOG_EPISODE }
