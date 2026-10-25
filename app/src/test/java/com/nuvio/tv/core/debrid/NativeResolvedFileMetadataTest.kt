package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class NativeResolvedFileMetadataTest {
    @Test fun `new service bytes cannot inherit previous service measured duration or tracks`() {
        val previous = StreamFileMetadata(999L, 5000L, StreamDurationOrigin.NATIVE_CONTAINER, "source",
            VerifiedSourceMediaFacts(video = VerifiedSourceVideo(width = 3840, height = 2160, hdr = VerifiedSourceHdr.DOLBY_VISION)))
        val fresh = freshNativeResolvedFileMetadata(previous, 123L, "source")
        assertEquals(123L, fresh.selectedFileBytes)
        assertNull(fresh.durationMillis); assertNull(fresh.durationOrigin); assertNull(fresh.verifiedTracks)
    }
    @Test fun `unknown successful file size never promotes earlier service size`() {
        val fresh = freshNativeResolvedFileMetadata(StreamFileMetadata(999L), null, "source")
        assertNull(fresh.selectedFileBytes)
    }
    @Test fun `matching catalog movie or episode duration may remain an explicitly estimated average input`() {
        for (origin in listOf(StreamDurationOrigin.CATALOG_MOVIE, StreamDurationOrigin.CATALOG_EPISODE)) {
            val fresh = freshNativeResolvedFileMetadata(StreamFileMetadata(999L, 5000L, origin), 123L, "source")
            assertEquals(123L, fresh.selectedFileBytes); assertEquals(5000L, fresh.durationMillis)
            assertEquals(origin, fresh.durationOrigin); assertNull(fresh.verifiedTracks)
        }
    }
}
