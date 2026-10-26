package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.VerifiedSourceAudio
import com.nuvio.tv.domain.model.VerifiedSourceAudioCodec
import com.nuvio.tv.domain.model.VerifiedSourceChannelLayout
import com.nuvio.tv.domain.model.VerifiedSourceHdr
import com.nuvio.tv.domain.model.VerifiedSourceVideo
import com.nuvio.tv.domain.model.VerifiedSourceVideoCodec

/** Pure native-track projection. A release name cannot override a field on an inspected track. */
internal object VerifiedStreamFilterFacts {
    private val colourTags = setOf(DebridStreamVisualTag.HDR_DV, DebridStreamVisualTag.DV_ONLY,
        DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HDR10,
        DebridStreamVisualTag.DV, DebridStreamVisualTag.HDR, DebridStreamVisualTag.HLG,
        DebridStreamVisualTag.SDR, DebridStreamVisualTag.UNKNOWN)

    fun visual(video: VerifiedSourceVideo?, reported: List<DebridStreamVisualTag>): List<DebridStreamVisualTag> {
        if (video == null) return reported
        val hdr = video.hdr ?: if (video.codec == VerifiedSourceVideoCodec.DOLBY_VISION) VerifiedSourceHdr.DOLBY_VISION else null
        val observed = when (hdr) {
            VerifiedSourceHdr.DOLBY_VISION -> listOf(DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.DV)
            VerifiedSourceHdr.HDR10 -> listOf(DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.HDR)
            VerifiedSourceHdr.PQ -> listOf(DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR)
            VerifiedSourceHdr.HLG -> listOf(DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HLG, DebridStreamVisualTag.HDR)
            VerifiedSourceHdr.SDR -> listOf(DebridStreamVisualTag.SDR)
            null -> listOf(DebridStreamVisualTag.UNKNOWN)
        }
        // Container facts do not inspect release/projection/bit-depth claims such as IMAX or
        // 3D. Keep those separate fields while replacing every advertised colour/HDR claim.
        return (observed + reported.filterNot { it in colourTags }).distinct()
    }

    fun encode(video: VerifiedSourceVideo?, reported: DebridStreamEncode): DebridStreamEncode =
        if (video == null) reported else when (video.codec) {
            VerifiedSourceVideoCodec.AVC -> DebridStreamEncode.AVC
            VerifiedSourceVideoCodec.HEVC -> DebridStreamEncode.HEVC
            VerifiedSourceVideoCodec.AV1 -> DebridStreamEncode.AV1
            // Dolby Vision alone does not establish AVC versus HEVC; MPEG-4 is not proof
            // of a particular XviD/DivX implementation. Other codecs have no filter enum.
            else -> DebridStreamEncode.UNKNOWN
        }

    fun audio(tracks: List<VerifiedSourceAudio>, reported: List<DebridStreamAudioTag>): List<DebridStreamAudioTag> {
        if (tracks.isEmpty()) return reported
        return tracks.flatMap { track -> when (track.codec) {
            VerifiedSourceAudioCodec.AAC -> listOf(DebridStreamAudioTag.AAC)
            VerifiedSourceAudioCodec.AC3 -> listOf(DebridStreamAudioTag.DD)
            VerifiedSourceAudioCodec.EAC3 -> listOf(DebridStreamAudioTag.DD_PLUS)
            VerifiedSourceAudioCodec.EAC3_JOC -> listOf(DebridStreamAudioTag.DD_PLUS, DebridStreamAudioTag.ATMOS)
            VerifiedSourceAudioCodec.TRUEHD -> listOf(DebridStreamAudioTag.TRUEHD)
            VerifiedSourceAudioCodec.DTS -> listOf(DebridStreamAudioTag.DTS)
            VerifiedSourceAudioCodec.DTS_HD -> listOf(DebridStreamAudioTag.DTS_HD)
            VerifiedSourceAudioCodec.OPUS -> listOf(DebridStreamAudioTag.OPUS)
            VerifiedSourceAudioCodec.FLAC -> listOf(DebridStreamAudioTag.FLAC)
            else -> listOf(DebridStreamAudioTag.UNKNOWN)
        } }.distinct()
    }

    fun channels(tracks: List<VerifiedSourceAudio>, reported: List<DebridStreamAudioChannel>): List<DebridStreamAudioChannel> {
        if (tracks.isEmpty()) return reported
        return tracks.map { track -> when {
            track.channelLayout == VerifiedSourceChannelLayout.STEREO && track.channelCount == 2 -> DebridStreamAudioChannel.CH_2_0
            track.channelLayout == VerifiedSourceChannelLayout.FIVE_POINT_ONE && track.channelCount == 6 -> DebridStreamAudioChannel.CH_5_1
            track.channelLayout == VerifiedSourceChannelLayout.SEVEN_POINT_ONE && track.channelCount == 8 -> DebridStreamAudioChannel.CH_7_1
            // A count without its speaker mask cannot establish 2.1/5.1/7.1. Likewise,
            // layouts with no matching preference enum must not borrow a release claim.
            else -> DebridStreamAudioChannel.UNKNOWN
        } }.distinct()
    }
}
