package com.nuvio.tv.core.mediafacts

import com.nuvio.tv.domain.model.VerifiedSourceAudio
import com.nuvio.tv.domain.model.VerifiedSourceAudioCodec
import com.nuvio.tv.domain.model.VerifiedSourceChannelLayout
import com.nuvio.tv.domain.model.VerifiedSourceHdr
import com.nuvio.tv.domain.model.VerifiedSourceMediaFacts
import com.nuvio.tv.domain.model.VerifiedSourceResolution
import com.nuvio.tv.domain.model.VerifiedSourceVideo
import com.nuvio.tv.domain.model.VerifiedSourceVideoCodec
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Locale

/**
 * Consumes only fields read from native MediaExtractor track formats. This helper performs no I/O.
 * Literal keys/numbers match the local compileSdk 36 MediaFormat/AudioFormat constants.
 */
object SourceMediaFormatFacts {
    private const val MAX_FORMATS = 64
    private const val MAX_AUDIO_FACTS = 16
    private const val MAX_DIMENSION = 16_384
    private const val MAX_CHANNELS = 32
    private const val MAX_DURATION_US = 7L * 24L * 60L * 60L * 1_000_000L
    private val dolbyVisionToken = Regex("(?:dvhe|dvh1|dva1|dvav)(?:\\.[0-9a-z]+)*")

    fun from(formats: List<Map<String, Any>>): VerifiedSourceMediaFacts? {
        if (formats.isEmpty() || formats.size > MAX_FORMATS) return null
        var video: VerifiedSourceVideo? = null
        val audio = mutableListOf<VerifiedSourceAudio>()
        var durationUs: Long? = null
        for (format in formats) {
            val mime = (format["mime"] as? String)?.takeIf { it.length <= 128 }
                ?.trim()?.lowercase(Locale.ROOT) ?: continue
            val isVideo = mime.startsWith("video/")
            val isAudio = mime.startsWith("audio/")
            if (!isVideo && !isAudio) continue
            integer(format["durationUs"])?.takeIf { it in 1L..MAX_DURATION_US }?.let {
                durationUs = maxOf(durationUs ?: 0L, it)
            }
            if (isVideo && video == null) {
                val codecs = codecTokens(format["codecs-string"])
                val isDolbyVision = mime == "video/dolby-vision" || codecs.any(dolbyVisionToken::matches)
                val candidate = VerifiedSourceVideo(
                    width = boundedInt(format["width"], 1, MAX_DIMENSION),
                    height = boundedInt(format["height"], 1, MAX_DIMENSION),
                    codec = videoCodec(mime, codecs, isDolbyVision),
                    hdr = hdr(format, isDolbyVision)
                )
                if (candidate != VerifiedSourceVideo()) video = candidate
            }
            if (isAudio && audio.size < MAX_AUDIO_FACTS) {
                val channels = boundedInt(format["channel-count"], 1, MAX_CHANNELS)
                val candidate = VerifiedSourceAudio(
                    codec = audioCodec(mime),
                    channelCount = channels,
                    channelLayout = channelLayout(channels, integer(format["channel-mask"]))
                )
                if (candidate != VerifiedSourceAudio() && candidate !in audio) audio += candidate
            }
        }
        val durationMillis = durationUs?.div(1_000L)?.takeIf { it > 0L }
        return if (video == null && audio.isEmpty() && durationMillis == null) null
            else VerifiedSourceMediaFacts(video, Collections.unmodifiableList(audio.toList()), durationMillis)
    }

    /** Cropped/portrait video keeps its long-edge class; no class is inferred from one missing dimension. */
    fun resolutionClass(video: VerifiedSourceVideo?): VerifiedSourceResolution? =
        resolutionClass(video?.width, video?.height)

    fun resolutionClass(width: Int?, height: Int?): VerifiedSourceResolution? {
        if (width == null || height == null || width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
        val longEdge = maxOf(width, height)
        return when {
            longEdge >= 7_680 -> VerifiedSourceResolution.UHD_8K
            longEdge >= 3_840 -> VerifiedSourceResolution.UHD_4K
            longEdge >= 2_560 -> VerifiedSourceResolution.QHD
            longEdge >= 1_920 -> VerifiedSourceResolution.FULL_HD
            longEdge >= 1_280 -> VerifiedSourceResolution.HD
            else -> VerifiedSourceResolution.SD
        }
    }

    /** Compact facts only; unknown codecs/colour/layout never turn into filename-derived labels. */
    fun summary(facts: VerifiedSourceMediaFacts): String? {
        val parts = mutableListOf<String>()
        val video = facts.video
        resolutionClass(video)?.label?.let(parts::add)
        video?.codec?.label?.let(parts::add)
        video?.hdr?.label?.takeUnless { it == video?.codec?.label }?.let(parts::add)
        facts.audio.asSequence().take(MAX_AUDIO_FACTS).mapNotNull { track ->
            val layout = track.channelLayout?.label ?: track.channelCount?.takeIf { it in 1..MAX_CHANNELS }?.let { "${it}ch" }
            listOfNotNull(track.codec?.label, layout).joinToString(" ").takeIf(String::isNotEmpty)
        }.distinct().take(4).toList().takeIf(List<String>::isNotEmpty)?.joinToString(" / ")?.let(parts::add)
        return parts.joinToString(" · ").takeIf(String::isNotEmpty)
    }

    private fun integer(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> null
    }

    private fun boundedInt(value: Any?, minimum: Int, maximum: Int): Int? =
        integer(value)?.takeIf { it in minimum.toLong()..maximum.toLong() }?.toInt()

    private fun codecTokens(value: Any?): List<String> = (value as? String)?.takeIf { it.length <= 256 }
        ?.lowercase(Locale.ROOT)?.split(Regex("[,\\s]+"))?.take(16)?.filter(String::isNotEmpty).orEmpty()

    private fun videoCodec(mime: String, codecs: List<String>, dolbyVision: Boolean): VerifiedSourceVideoCodec? {
        if (dolbyVision) return VerifiedSourceVideoCodec.DOLBY_VISION
        return when (mime) {
            "video/avc" -> VerifiedSourceVideoCodec.AVC
            "video/hevc" -> VerifiedSourceVideoCodec.HEVC
            "video/av01" -> VerifiedSourceVideoCodec.AV1
            "video/x-vnd.on2.vp9" -> VerifiedSourceVideoCodec.VP9
            "video/x-vnd.on2.vp8" -> VerifiedSourceVideoCodec.VP8
            "video/mpeg2" -> VerifiedSourceVideoCodec.MPEG2
            "video/mp4v-es" -> VerifiedSourceVideoCodec.MPEG4
            "video/3gpp" -> VerifiedSourceVideoCodec.H263
            "video/wvc1", "video/vc1" -> VerifiedSourceVideoCodec.VC1
            "video/x-ms-wmv" -> VerifiedSourceVideoCodec.WMV
            else -> codecs.firstNotNullOfOrNull { token -> when {
                token.matches(Regex("avc[13](?:\\.[0-9a-z]+)*")) -> VerifiedSourceVideoCodec.AVC
                token.matches(Regex("(?:hvc1|hev1)(?:\\.[0-9a-z]+)*")) -> VerifiedSourceVideoCodec.HEVC
                token.matches(Regex("av01(?:\\.[0-9a-z]+)*")) -> VerifiedSourceVideoCodec.AV1
                token.matches(Regex("vp09(?:\\.[0-9a-z]+)*")) -> VerifiedSourceVideoCodec.VP9
                token.matches(Regex("vp08(?:\\.[0-9a-z]+)*")) -> VerifiedSourceVideoCodec.VP8
                else -> null
            } }
        }
    }

    private fun audioCodec(mime: String): VerifiedSourceAudioCodec? = when (mime) {
        "audio/mp4a-latm", "audio/aac" -> VerifiedSourceAudioCodec.AAC
        "audio/ac3" -> VerifiedSourceAudioCodec.AC3
        "audio/eac3" -> VerifiedSourceAudioCodec.EAC3
        "audio/eac3-joc" -> VerifiedSourceAudioCodec.EAC3_JOC
        "audio/true-hd" -> VerifiedSourceAudioCodec.TRUEHD
        "audio/vnd.dts" -> VerifiedSourceAudioCodec.DTS
        "audio/vnd.dts.hd" -> VerifiedSourceAudioCodec.DTS_HD
        "audio/vnd.dts.uhd" -> VerifiedSourceAudioCodec.DTS_UHD
        "audio/ac4" -> VerifiedSourceAudioCodec.AC4
        "audio/opus" -> VerifiedSourceAudioCodec.OPUS
        "audio/vorbis" -> VerifiedSourceAudioCodec.VORBIS
        "audio/flac" -> VerifiedSourceAudioCodec.FLAC
        "audio/alac" -> VerifiedSourceAudioCodec.ALAC
        "audio/raw" -> VerifiedSourceAudioCodec.PCM
        "audio/mpeg" -> VerifiedSourceAudioCodec.MP3
        "audio/mpeg-l2" -> VerifiedSourceAudioCodec.MP2
        "audio/3gpp" -> VerifiedSourceAudioCodec.AMR_NB
        "audio/amr-wb" -> VerifiedSourceAudioCodec.AMR_WB
        "audio/mha1", "audio/mhm1" -> VerifiedSourceAudioCodec.MPEG_H
        "audio/iamf" -> VerifiedSourceAudioCodec.IAMF
        else -> null
    }

    private fun hdr(format: Map<String, Any>, dolbyVision: Boolean): VerifiedSourceHdr? {
        if (dolbyVision) return VerifiedSourceHdr.DOLBY_VISION
        return when (integer(format["color-transfer"])) {
            6L -> if (integer(format["color-standard"]) == 6L && hasHdrStaticInfo(format["hdr-static-info"])) {
                VerifiedSourceHdr.HDR10
            } else VerifiedSourceHdr.PQ
            7L -> VerifiedSourceHdr.HLG
            3L -> VerifiedSourceHdr.SDR
            else -> null
        }
    }

    // Android HDRStaticInfo serializes CTA-861-3 Type 1 as exactly 25 bytes:
    // descriptor ID 0 followed by twelve little-endian uint16 fields. Match the
    // platform parser's length/type checks; arbitrary/nonempty blobs are not proof.
    private fun hasHdrStaticInfo(value: Any?): Boolean = when (value) {
        is ByteArray -> value.size == 25 && value[0] == 0.toByte()
        is ByteBuffer -> value.remaining() == 25 && value.get(value.position()) == 0.toByte()
        else -> false
    }

    /** Exact output speaker masks from AudioFormat, checked against the native channel count. */
    private fun channelLayout(count: Int?, mask: Long?): VerifiedSourceChannelLayout? = when {
        count == 1 && mask in setOf(4L, 16L) -> VerifiedSourceChannelLayout.MONO
        count == 2 && mask == 12L -> VerifiedSourceChannelLayout.STEREO
        count == 3 && mask == 44L -> VerifiedSourceChannelLayout.TWO_POINT_ONE
        count == 6 && mask in setOf(252L, 6_204L) -> VerifiedSourceChannelLayout.FIVE_POINT_ONE
        count == 8 && mask in setOf(6_396L, 1_020L) -> VerifiedSourceChannelLayout.SEVEN_POINT_ONE
        else -> null
    }
}
