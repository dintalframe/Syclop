package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.StreamDurationOrigin
import com.nuvio.tv.domain.model.StreamFileMetadata

/** Never transfer measured facts from an earlier provider/file after a fresh service resolve. */
internal fun freshNativeResolvedFileMetadata(previous: StreamFileMetadata?, successfulFileBytes: Long?,
    sourceIdentity: String): StreamFileMetadata {
    val catalog = previous?.takeIf { it.durationOrigin in setOf(StreamDurationOrigin.CATALOG_MOVIE, StreamDurationOrigin.CATALOG_EPISODE) }
    val duration = catalog?.durationMillis?.takeIf { it > 0L }
    return StreamFileMetadata(selectedFileBytes = successfulFileBytes?.takeIf { it > 0L },
        durationMillis = duration,
        durationOrigin = catalog?.durationOrigin?.takeIf { duration != null },
        sourceIdentity = sourceIdentity)
}
