package com.nuvio.tv.core.mediafacts

import com.nuvio.tv.domain.model.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SourceMediaFormatFactsTest {
    private val facts = SourceMediaFormatFacts

    @Test fun `native dimensions and MIME identify cropped 4K HEVC without filename evidence`() {
        val result = facts.from(listOf(mapOf("mime" to "video/hevc", "width" to 3840, "height" to 1600)))!!
        assertEquals(3840, result.video?.width)
        assertEquals(1600, result.video?.height)
        assertEquals(VerifiedSourceVideoCodec.HEVC, result.video?.codec)
        assertEquals(VerifiedSourceResolution.UHD_4K, facts.resolutionClass(result.video))
        assertEquals("4K · HEVC", facts.summary(result))
    }

    @Test fun `missing colour information stays unknown and explicit SDR is required`() {
        val unknown = facts.from(listOf(video()))!!
        assertNull(unknown.video?.hdr)
        assertFalse(facts.summary(unknown).orEmpty().contains("SDR"))
        assertEquals(VerifiedSourceHdr.SDR, facts.from(listOf(video("color-transfer" to 3)))?.video?.hdr)
        assertNull(facts.from(listOf(video("color-transfer" to 1)))?.video?.hdr)
    }

    @Test fun `PQ only becomes HDR10 with BT2020 and serialized Type 1 static metadata`() {
        assertEquals(VerifiedSourceHdr.PQ, facts.from(listOf(video("color-transfer" to 6)))?.video?.hdr)
        assertEquals(VerifiedSourceHdr.PQ, facts.from(listOf(video("color-transfer" to 6, "color-standard" to 6)))?.video?.hdr)
        assertEquals(VerifiedSourceHdr.PQ, facts.from(listOf(video("color-transfer" to 6, "color-standard" to 1,
            "hdr-static-info" to ByteArray(25))))?.video?.hdr)
        assertEquals(VerifiedSourceHdr.HDR10, facts.from(listOf(video("color-transfer" to 6, "color-standard" to 6,
            "hdr-static-info" to ByteArray(25))))?.video?.hdr)
        assertEquals(VerifiedSourceHdr.HLG, facts.from(listOf(video("color-transfer" to 7)))?.video?.hdr)
    }

    @Test fun `native static HDR buffer is inspected without modifying its position`() {
        val buffer = ByteBuffer.wrap(ByteArray(27).apply { this[0] = 127; this[1] = 127 }).asReadOnlyBuffer().apply { position(2) }
        assertEquals(VerifiedSourceHdr.HDR10, facts.from(listOf(video("color-transfer" to 6, "color-standard" to 6,
            "hdr-static-info" to buffer)))?.video?.hdr)
        assertEquals(2, buffer.position())
        for (invalid in listOf(ByteArray(0), ByteArray(1_025), "HDR10+")) {
            assertEquals(VerifiedSourceHdr.PQ, facts.from(listOf(video("color-transfer" to 6, "color-standard" to 6,
                "hdr-static-info" to invalid)))?.video?.hdr)
        }
    }

    @Test fun `malformed static HDR lengths cannot upgrade PQ to HDR10`() {
        for (length in listOf(1, 24, 26, 1_024)) {
            for (blob in listOf<Any>(ByteArray(length), ByteBuffer.wrap(ByteArray(length)))) {
                assertEquals(VerifiedSourceHdr.PQ, facts.from(listOf(video("color-transfer" to 6,
                    "color-standard" to 6, "hdr-static-info" to blob)))?.video?.hdr)
            }
        }
    }

    @Test fun `unsupported static HDR descriptor types cannot upgrade PQ to HDR10`() {
        for (type in listOf(1, 2, 127, 255)) {
            val bytes = ByteArray(25).apply { this[0] = type.toByte() }
            for (blob in listOf<Any>(bytes, ByteBuffer.wrap(bytes).asReadOnlyBuffer())) {
                assertEquals(VerifiedSourceHdr.PQ, facts.from(listOf(video("color-transfer" to 6,
                    "color-standard" to 6, "hdr-static-info" to blob)))?.video?.hdr)
            }
        }
    }

    @Test fun `Dolby Vision requires a real MIME or complete codec token and suppresses duplicate HDR10 labels`() {
        val direct = facts.from(listOf(mapOf("mime" to "video/dolby-vision", "width" to 1920, "height" to 1080,
            "color-transfer" to 6, "color-standard" to 6, "hdr-static-info" to ByteArray(25))))!!
        assertEquals(VerifiedSourceHdr.DOLBY_VISION, direct.video?.hdr)
        assertEquals("1080p · Dolby Vision", facts.summary(direct))
        for (codec in listOf("dvhe.08.06", "dvh1.05.06", "dva1.09.03", "dvav.01.01", "hvc1.2.4.L153.B0, DVHE.07.06")) {
            assertEquals(VerifiedSourceHdr.DOLBY_VISION, facts.from(listOf(video("codecs-string" to codec)))?.video?.hdr)
        }
        for (claim in listOf("Movie.DV.2160p.mkv", "fake-dvhe.08.06", "https://provider.test/dvhe.08.06", "dvhe.08.06?secret=private")) {
            assertNull(facts.from(listOf(video("codecs-string" to claim)))?.video?.hdr)
        }
    }

    @Test fun `native audio counts alone remain Nch and cannot invent speaker layouts`() {
        val result = facts.from(listOf(2, 3, 6, 8).map { audio(it) })!!
        assertTrue(result.audio.all { it.channelLayout == null })
        assertEquals("AAC 2ch / AAC 3ch / AAC 6ch / AAC 8ch", facts.summary(result))
    }

    @Test fun `exact Android speaker masks and agreeing counts prove stereo and surround layouts`() {
        val cases = listOf(Triple(2, 12, VerifiedSourceChannelLayout.STEREO),
            Triple(3, 44, VerifiedSourceChannelLayout.TWO_POINT_ONE),
            Triple(6, 252, VerifiedSourceChannelLayout.FIVE_POINT_ONE),
            Triple(6, 6204, VerifiedSourceChannelLayout.FIVE_POINT_ONE),
            Triple(8, 6396, VerifiedSourceChannelLayout.SEVEN_POINT_ONE),
            Triple(8, 1020, VerifiedSourceChannelLayout.SEVEN_POINT_ONE))
        for ((count, mask, layout) in cases) {
            assertEquals(layout, facts.from(listOf(audio(count, mask)))?.audio?.single()?.channelLayout)
        }
    }

    @Test fun `wrong counts missing LFE extra speaker bits and index masks cannot claim named layouts`() {
        val cases = listOf(2 to 44, 3 to 28, 6 to (252 and 32.inv()), 8 to (6396 or 8192), 6 to 63)
        for ((count, mask) in cases) {
            assertNull(facts.from(listOf(audio(count, mask)))?.audio?.single()?.channelLayout)
        }
        assertNull(facts.from(listOf(mapOf("mime" to "audio/aac", "channel-mask" to 44)))?.audio?.single()?.channelLayout)
    }

    @Test fun `canonical codec labels come from MIME and never expose raw unsupported format strings`() {
        val cases = mapOf("audio/eac3-joc" to VerifiedSourceAudioCodec.EAC3_JOC,
            "audio/true-hd" to VerifiedSourceAudioCodec.TRUEHD, "audio/vnd.dts.hd" to VerifiedSourceAudioCodec.DTS_HD,
            "audio/opus" to VerifiedSourceAudioCodec.OPUS, "audio/flac" to VerifiedSourceAudioCodec.FLAC)
        for ((mime, codec) in cases) {
            assertEquals(codec, facts.from(listOf(mapOf("mime" to mime)))?.audio?.single()?.codec)
        }
        val unknown = facts.from(listOf(mapOf("mime" to "audio/private-provider-value", "channel-count" to 2)))!!
        assertNull(unknown.audio.single().codec)
        assertEquals("2ch", facts.summary(unknown))
        assertFalse(unknown.toString().contains("private-provider-value"))
    }

    @Test fun `duration uses maximum valid audio or video microseconds and ignores subtitle duration`() {
        val result = facts.from(listOf(video("durationUs" to 1_234_567L),
            audio(2) + ("durationUs" to 2_345_678L), mapOf("mime" to "text/vtt", "durationUs" to 9_999_999L)))!!
        assertEquals(2_345L, result.durationMillis)
        assertEquals(604_800_000L, facts.from(listOf(video("durationUs" to 604_800_000_000L)))?.durationMillis)
        for (duration in listOf<Any>(0L, -1L, 999L, 604_800_000_001L, Long.MAX_VALUE, "120000000", 1.5)) {
            assertNull(facts.from(listOf(video("durationUs" to duration)))?.durationMillis)
        }
    }

    @Test fun `malformed numeric dimensions and channels remain unknown rather than overflow or parse claims`() {
        for (invalid in listOf<Any>(-1, 0, 16_385, Long.MAX_VALUE, "3840", 3840.0)) {
            val result = facts.from(listOf(video("width" to invalid, "height" to invalid)))!!
            assertNull(result.video?.width)
            assertNull(result.video?.height)
            assertNull(facts.resolutionClass(result.video))
        }
        for (invalid in listOf<Any>(-1, 0, 33, Long.MAX_VALUE, "6", 6.0)) {
            assertNull(facts.from(listOf(audio(2) + ("channel-count" to invalid)))?.audio?.single()?.channelCount)
        }
    }

    @Test fun `video fields stay on the first coherent native track without borrowing later track facts`() {
        val result = facts.from(listOf(mapOf("mime" to "video/avc", "width" to 1920),
            mapOf("mime" to "video/hevc", "width" to 3840, "height" to 2160, "color-transfer" to 7)))!!
        assertEquals(VerifiedSourceVideoCodec.AVC, result.video?.codec)
        assertEquals(1920, result.video?.width)
        assertNull(result.video?.height)
        assertNull(result.video?.hdr)
    }

    @Test fun `format and audio cardinality limits keep native metadata bounded`() {
        assertNull(facts.from(List(65) { video() }))
        val result = facts.from((1..32).map { audio(it) })!!
        assertEquals(16, result.audio.size)
        assertEquals(4, facts.summary(result)!!.split(" / ").size)
        assertNull(facts.from(emptyList()))
        assertNull(facts.from(listOf(mapOf("mime" to "text/vtt", "label" to "4K HDR10 7.1 99Mbps"))))
    }

    @Test fun `summary preserves native evidence only and portrait resolution classes use long edge`() {
        val result = facts.from(listOf(video("width" to 1080, "height" to 1920,
            "filename" to "private.8K.HDR10.7.1.mkv", "label" to "Dolby Vision", "bitrate" to "999 Mbps")))!!
        assertEquals(VerifiedSourceResolution.FULL_HD, facts.resolutionClass(result.video))
        assertEquals("1080p · HEVC", facts.summary(result))
        assertFalse(result.toString().contains("private"))
        assertNull(facts.summary(VerifiedSourceMediaFacts()))
        assertNull(facts.resolutionClass(3840, null))
    }

    private fun video(vararg entries: Pair<String, Any>): Map<String, Any> = mapOf("mime" to "video/hevc") + entries
    private fun audio(count: Int, mask: Int? = null): Map<String, Any> =
        mapOf<String, Any>("mime" to "audio/mp4a-latm", "channel-count" to count) +
            if (mask == null) emptyMap() else mapOf("channel-mask" to mask)
}
