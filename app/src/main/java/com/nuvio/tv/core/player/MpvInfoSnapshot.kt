package com.nuvio.tv.core.player

/** A one-time INFO snapshot. No paths, URLs, account fields, free-form errors or media titles. */
data class MpvInfoSnapshot(
    val videoCodec: MpvInfoVideoCodec? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sourceFrameRate: Double? = null,
    val estimatedFrameRate: Double? = null,
    val activeDecoder: MpvInfoActiveDecoder? = null,
    val speed: Double? = null,
    val droppedFrames: Long? = null,
    val decoderDroppedFrames: Long? = null,
    val waitingForCache: Boolean? = null,
    val cachedSeconds: Double? = null
)

enum class MpvInfoVideoCodec(val label: String) {
    AVC("AVC / H.264"), HEVC("HEVC / H.265"), MPEG2("MPEG-2"), MPEG4("MPEG-4"),
    VC1("VC-1"), WMV3("WMV9 / WMV3"), VP8("VP8"), VP9("VP9"), AV1("AV1"), THEORA("Theora"), MJPEG("MJPEG"), PRORES("ProRes")
}

enum class MpvInfoActiveDecoder { SOFTWARE, MEDIACODEC, MEDIACODEC_COPY }

/** Fixed bounded property reads; getters are local mpv IPC, never network requests. */
internal object MpvInfoSnapshotReader {
    private const val MAX_COUNTER = 1_000_000_000_000L

    fun read(isCurrentMedia: () -> Boolean = { true }, property: (String) -> String?): MpvInfoSnapshot {
        if (!runCatching(isCurrentMedia).getOrDefault(false)) return MpvInfoSnapshot()
        fun raw(name: String): String? = runCatching { property(name) }.getOrNull()
            ?.takeIf { it.length <= 128 }?.trim()?.takeIf(String::isNotEmpty)
        fun integer(name: String, maximum: Long): Long? = raw(name)?.let { value ->
            runCatching { value.toBigDecimal().longValueExact() }.getOrNull()?.takeIf { it in 0..maximum }
        }
        fun number(name: String, minimum: Double, maximum: Double): Double? = raw(name)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in minimum..maximum }
        fun dimension(name: String) = integer(name, 32_768)?.takeIf { it > 0 }?.toInt()
        val width = dimension("video-params/w") ?: dimension("width")
        val height = dimension("video-params/h") ?: dimension("height")
        val codec = codec(raw("video-codec")) ?: codec(raw("current-tracks/video/codec"))
        val decoder = when (raw("hwdec-current")?.lowercase()) {
            "no" -> MpvInfoActiveDecoder.SOFTWARE
            "mediacodec" -> MpvInfoActiveDecoder.MEDIACODEC
            "mediacodec-copy" -> MpvInfoActiveDecoder.MEDIACODEC_COPY
            else -> null // Never use configured hwdec=auto(-safe) as proof of active hardware.
        }
        val snapshot = MpvInfoSnapshot(codec, width, height,
            number("container-fps", 0.001, 1_000.0), number("estimated-vf-fps", 0.001, 1_000.0), decoder,
            number("speed", 0.01, 100.0), integer("frame-drop-count", MAX_COUNTER),
            integer("decoder-frame-drop-count", MAX_COUNTER),
            when (raw("paused-for-cache")?.lowercase()) { "yes", "true" -> true; "no", "false" -> false; else -> null },
            number("demuxer-cache-duration", 0.0, 604_800.0))
        return if (runCatching(isCurrentMedia).getOrDefault(false)) snapshot else MpvInfoSnapshot()
    }

    private fun codec(raw: String?): MpvInfoVideoCodec? {
        // Return a fixed label, never the decoder's free-form description.
        val name = raw?.lowercase()?.substringBefore(" (")?.substringBefore(' ') ?: return null
        return when (name) {
            "h264", "avc", "avc1", "h.264" -> MpvInfoVideoCodec.AVC
            "hevc", "h265", "h.265", "hev1", "hvc1" -> MpvInfoVideoCodec.HEVC
            "mpeg2video", "mpeg2" -> MpvInfoVideoCodec.MPEG2
            "mpeg4" -> MpvInfoVideoCodec.MPEG4
            "vc1", "vc-1" -> MpvInfoVideoCodec.VC1
            "wmv3" -> MpvInfoVideoCodec.WMV3
            "vp8" -> MpvInfoVideoCodec.VP8
            "vp9" -> MpvInfoVideoCodec.VP9
            "av1", "av01" -> MpvInfoVideoCodec.AV1
            "theora" -> MpvInfoVideoCodec.THEORA
            "mjpeg" -> MpvInfoVideoCodec.MJPEG
            "prores" -> MpvInfoVideoCodec.PRORES
            else -> null
        }
    }
}
