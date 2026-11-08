package com.nuvio.tv.ui.screens.player

private val regionalLanguageTag = Regex("[a-z]{2,3}(?:[-_][a-z0-9]{2,8})+", RegexOption.IGNORE_CASE)
private val unknownLanguageTokens = setOf("none", "und", "unknown", "unk", "zxx")

internal fun playbackMatchText(info: PlayerMediaInfo, languages: List<String>): String = buildList {
    addAll(resolutionTokens(info))
    addAll(videoCodecTokens(info.videoCodec))
    if ("DV" in info.hdr) addAll(listOf("DV", "DoVi", "Dolby Vision"))
    if ("HDR" in info.hdr) add("HDR")
    if ("HDR10" in info.hdr) addAll(listOf("HDR", "HDR10"))
    if ("HDR10+" in info.hdr) addAll(listOf("HDR", "HDR10+"))
    if ("HLG" in info.hdr) addAll(listOf("HDR", "HLG"))
    info.bitDepth?.takeIf { it > 0 }?.let { add("${it}bit") }
    addAll(audioTokens(info.audioCodec, info.audioProfile))
    channelsToken(info.audioChannels)?.let(::add)
    addAll(languageTokens(languages))
}.distinct().joinToString(" ")

private fun resolutionTokens(info: PlayerMediaInfo): List<String> {
    val width = info.videoWidth?.takeIf { it > 0 } ?: return emptyList()
    val height = info.videoHeight?.takeIf { it > 0 } ?: return emptyList()
    return when {
        width >= 7600 || height >= 4200 -> listOf("4320p", "8K")
        width >= 3800 || height >= 2100 -> listOf("2160p", "4K", "UHD")
        width >= 2500 || height >= 1400 -> listOf("1440p", "2K")
        width >= 1900 || height >= 1000 -> listOf("1080p", "FHD")
        width >= 1260 || height >= 700 -> listOf("720p")
        else -> listOf("${height}p", "SD")
    }
}

private fun videoCodecTokens(codec: String?): List<String> = when (codec?.uppercase()) {
    null -> emptyList()
    "HEVC", "DOLBY VISION" -> listOf("HEVC", "H.265", "H265", "x265")
    "AVC" -> listOf("AVC", "H.264", "H264", "x264")
    else -> listOf(codec)
}

private fun audioTokens(codec: String?, profile: String?): List<String> {
    val label = codec?.uppercase() ?: return emptyList()
    val profileLabel = profile.orEmpty().uppercase()
    return buildList {
        when {
            label.startsWith("E-AC-3") -> addAll(listOf("DD+", "DDP", "EAC3"))
            label == "AC-3" -> addAll(listOf("DD", "AC3"))
            label.startsWith("DTS") -> {
                add("DTS")
                if (label.contains("HD") || profileLabel.contains("DTS-HD")) add("DTS-HD")
                if (label.contains("DTS-HD MA") || profileLabel.contains("DTS-HD MA") ||
                    profileLabel == "MA" || profileLabel.contains("MASTER AUDIO")) {
                    add("DTS-HD MA")
                }
                if (label.contains("DTS:X") || profileLabel.contains("DTS:X")) add("DTS:X")
            }
            else -> add(codec)
        }
        if (label.endsWith("JOC") || profileLabel.contains("ATMOS")) add("Atmos")
    }
}

private fun languageTokens(languages: List<String>): List<String> {
    val names = languages.map(String::trim).filter {
        it.isNotEmpty() && it.lowercase() !in unknownLanguageTokens
    }
    val isBrazilianPortuguese = names.any {
        val tag = it.lowercase().replace('_', '-')
        tag == "pt-br" || tag == "pob"
    }
    if (isBrazilianPortuguese) {
        // The editor's generic Portuguese rule looks ahead for this token.
        return names.filterNot { it.equals("pt-br", ignoreCase = true) } + "pt-br"
    }
    return names.flatMap { language ->
        if (regionalLanguageTag.matches(language)) {
            val base = language.replace('_', '-').substringBefore('-')
            if (base.lowercase() in unknownLanguageTokens) emptyList() else listOf(language, base)
        } else {
            listOf(language)
        }
    }
}

private fun channelsToken(channels: Int?): String? = when (channels) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    else -> null
}
