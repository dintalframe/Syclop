package com.nuvio.tv.domain.model

/** Native container facts, not proof of rendered output. No URLs, filenames, labels or credentials. */
data class VerifiedSourceMediaFacts(
    val video: VerifiedSourceVideo? = null,
    val audio: List<VerifiedSourceAudio> = emptyList(),
    val durationMillis: Long? = null
)

/** One coherent video track, never a combination of fields from different tracks. */
data class VerifiedSourceVideo(
    val width: Int? = null,
    val height: Int? = null,
    val codec: VerifiedSourceVideoCodec? = null,
    val hdr: VerifiedSourceHdr? = null
)

data class VerifiedSourceAudio(
    val codec: VerifiedSourceAudioCodec? = null,
    val channelCount: Int? = null,
    val channelLayout: VerifiedSourceChannelLayout? = null
)

enum class VerifiedSourceVideoCodec(val label: String) {
    AVC("AVC"), HEVC("HEVC"), DOLBY_VISION("Dolby Vision"), AV1("AV1"), VP9("VP9"), VP8("VP8"),
    MPEG2("MPEG-2"), MPEG4("MPEG-4"), H263("H.263"), VC1("VC-1"), WMV("WMV")
}

enum class VerifiedSourceAudioCodec(val label: String) {
    AAC("AAC"), AC3("AC-3"), EAC3("E-AC-3"), EAC3_JOC("E-AC-3 JOC"), TRUEHD("TrueHD"),
    DTS("DTS"), DTS_HD("DTS-HD"), DTS_UHD("DTS-UHD"), AC4("AC-4"),
    OPUS("Opus"), VORBIS("Vorbis"), FLAC("FLAC"), ALAC("ALAC"), PCM("PCM"),
    MP3("MP3"), MP2("MP2"), AMR_NB("AMR-NB"), AMR_WB("AMR-WB"), MPEG_H("MPEG-H"), IAMF("IAMF")
}

enum class VerifiedSourceHdr(val label: String) {
    DOLBY_VISION("Dolby Vision"), HDR10("HDR10"), PQ("HDR (PQ)"), HLG("HLG"), SDR("SDR")
}

/** These labels require an actual matching Android speaker mask, not just a channel count. */
enum class VerifiedSourceChannelLayout(val label: String) {
    MONO("Mono"), STEREO("Stereo"), TWO_POINT_ONE("2.1"), FIVE_POINT_ONE("5.1"), SEVEN_POINT_ONE("7.1")
}

/** Display/ranking class derived from native dimensions; exact dimensions remain in [VerifiedSourceVideo]. */
enum class VerifiedSourceResolution(val label: String, val rank: Int) {
    SD("SD", 480), HD("720p", 720), FULL_HD("1080p", 1080), QHD("1440p", 1440), UHD_4K("4K", 2160), UHD_8K("8K", 4320)
}
