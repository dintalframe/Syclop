package com.nuvio.tv.core.player

import org.junit.Assert.*
import org.junit.Test

class MpvInfoSnapshotTest {
    @Test fun `actual local properties map codec dimensions two rates decoder drops speed and cache separately`() {
        val result = read(mapOf("video-codec" to "h264 (H.264 / AVC)", "video-params/w" to "1920", "video-params/h" to "1080",
            "container-fps" to "59.94", "estimated-vf-fps" to "29.97", "hwdec-current" to "mediacodec-copy",
            "speed" to "1.0", "frame-drop-count" to "17", "decoder-frame-drop-count" to "3",
            "paused-for-cache" to "yes", "demuxer-cache-duration" to "2.5"))
        assertEquals(MpvInfoVideoCodec.AVC, result.videoCodec)
        assertEquals(1920, result.width); assertEquals(1080, result.height)
        assertEquals(59.94, result.sourceFrameRate!!, 0.0001)
        assertEquals(29.97, result.estimatedFrameRate!!, 0.0001)
        assertEquals(MpvInfoActiveDecoder.MEDIACODEC_COPY, result.activeDecoder)
        assertEquals(1.0, result.speed!!, 0.0)
        assertEquals(17L, result.droppedFrames); assertEquals(3L, result.decoderDroppedFrames)
        assertEquals(true, result.waitingForCache); assertEquals(2.5, result.cachedSeconds!!, 0.0)
    }

    @Test fun `configured hardware preference cannot masquerade as the active decoder`() {
        assertNull(read(mapOf("hwdec" to "mediacodec")).activeDecoder)
        assertNull(read(mapOf("hwdec-current" to "auto-safe")).activeDecoder)
        assertEquals(MpvInfoActiveDecoder.SOFTWARE, read(mapOf("hwdec-current" to "no")).activeDecoder)
        assertEquals(MpvInfoActiveDecoder.MEDIACODEC, read(mapOf("hwdec-current" to "mediacodec")).activeDecoder)
    }

    @Test fun `unknown descriptions and private fields cannot escape as diagnostic text or false HDR claims`() {
        val privateValue = "https://private.invalid/account-secret/movie.mkv"
        val result = read(mapOf("video-codec" to privateValue, "hwdec-current" to privateValue,
            "current-tracks/video/decoder-desc" to privateValue, "path" to privateValue, "filename" to privateValue))
        assertEquals(MpvInfoSnapshot(), result)
        assertFalse(result.toString().contains("account-secret"))
        assertEquals("HEVC / H.265", read(mapOf("video-codec" to "hevc (private description)")).videoCodec?.label)
        assertFalse(read(mapOf("video-codec" to "hevc (Dolby Vision output)")).toString().contains("Dolby"))
    }

    @Test fun `nonfinite numbers cannot reach UI formatting`() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "1e999")) {
            val result = read(mapOf("container-fps" to bad, "estimated-vf-fps" to bad, "speed" to bad,
                "demuxer-cache-duration" to bad, "frame-drop-count" to bad, "decoder-frame-drop-count" to bad))
            assertEquals(MpvInfoSnapshot(), result)
        }
    }

    @Test fun `negative fractional and overflowing primitive fields never wrap or become plausible metrics`() {
        for (bad in listOf("-1", "1.5", "2147483648", "9223372036854775808", "1e999")) {
            val result = read(mapOf("video-params/w" to bad, "video-params/h" to bad))
            assertNull(result.width); assertNull(result.height)
        }
        for (bad in listOf("-1", "1.5", "1000000000001", "9223372036854775808")) {
            assertNull(read(mapOf("frame-drop-count" to bad)).droppedFrames)
            assertNull(read(mapOf("decoder-frame-drop-count" to bad)).decoderDroppedFrames)
        }
    }

    @Test fun `zero drop and cache counts are valid while zero fps speed and dimensions are unknown`() {
        val result = read(mapOf("video-params/w" to "0", "video-params/h" to "0", "container-fps" to "0",
            "estimated-vf-fps" to "0", "speed" to "0", "frame-drop-count" to "0",
            "decoder-frame-drop-count" to "0", "demuxer-cache-duration" to "0", "paused-for-cache" to "no"))
        assertNull(result.width); assertNull(result.height); assertNull(result.sourceFrameRate)
        assertNull(result.estimatedFrameRate); assertNull(result.speed)
        assertEquals(0L, result.droppedFrames); assertEquals(0L, result.decoderDroppedFrames)
        assertEquals(0.0, result.cachedSeconds!!, 0.0); assertEquals(false, result.waitingForCache)
    }

    @Test fun `supported canonical track and dimension fallback remain actual MPV properties`() {
        val result = read(mapOf("video-codec" to "unknown", "current-tracks/video/codec" to "vp9",
            "video-params/w" to "0", "video-params/h" to "0", "width" to "1280", "height" to "720"))
        assertEquals(MpvInfoVideoCodec.VP9, result.videoCodec)
        assertEquals(1280, result.width); assertEquals(720, result.height)
    }

    @Test fun `bounded maxima are accepted and larger finite values remain unknown`() {
        val result = read(mapOf("video-params/w" to "32768", "video-params/h" to "32768", "container-fps" to "1000",
            "speed" to "100", "frame-drop-count" to "1000000000000", "demuxer-cache-duration" to "604800"))
        assertEquals(32768, result.width); assertEquals(1000.0, result.sourceFrameRate!!, 0.0)
        assertEquals(100.0, result.speed!!, 0.0); assertEquals(1_000_000_000_000L, result.droppedFrames)
        assertEquals(604800.0, result.cachedSeconds!!, 0.0)
        assertNull(read(mapOf("container-fps" to "1001")).sourceFrameRate)
        assertNull(read(mapOf("speed" to "101")).speed)
        assertNull(read(mapOf("demuxer-cache-duration" to "604801")).cachedSeconds)
    }

    @Test fun `getter errors do not expose exception text or discard other available properties`() {
        val result = MpvInfoSnapshotReader.read { name ->
            if (name == "video-codec") throw IllegalArgumentException("private token and URL")
            if (name == "container-fps") "24" else null
        }
        assertNull(result.videoCodec); assertEquals(24.0, result.sourceFrameRate!!, 0.0)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `unloaded or changed media returns unknown and cannot reuse the previous file snapshot`() {
        var reads = 0
        assertEquals(MpvInfoSnapshot(), MpvInfoSnapshotReader.read({ false }) { reads++; "1920" })
        assertEquals(0, reads)
        var checks = 0
        val result = MpvInfoSnapshotReader.read({ ++checks == 1 }) { name -> if (name == "video-codec") "h264" else null }
        assertEquals(MpvInfoSnapshot(), result)
    }

    @Test fun `one snapshot uses only bounded fixed property keys and no URL title account or path reads`() {
        val names = mutableListOf<String>()
        MpvInfoSnapshotReader.read { names += it; null }
        val allowed = setOf("video-params/w", "video-params/h", "width", "height", "video-codec", "current-tracks/video/codec",
            "hwdec-current", "container-fps", "estimated-vf-fps", "speed", "frame-drop-count",
            "decoder-frame-drop-count", "paused-for-cache", "demuxer-cache-duration")
        assertEquals(allowed, names.toSet()); assertEquals(14, names.size)
    }

    private fun read(values: Map<String, String>) = MpvInfoSnapshotReader.read { values[it] }
}
