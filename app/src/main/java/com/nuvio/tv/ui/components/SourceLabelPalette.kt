package com.nuvio.tv.ui.components

/** Opaque backgrounds chosen for readable white TV text. */
internal object SourceLabelPalette {
    fun background(name: String, style: String = ""): Long {
        val service = style.removePrefix("native-service:")
        if (style.startsWith("native-service:")) return when (service) {
            "realdebrid" -> 0xFF9F2637
            "torbox" -> 0xFF087067
            "alldebrid" -> 0xFF925009
            "premiumize" -> 0xFF6540A5
            else -> 0xFF245C9C
        }
        val text = name.uppercase(java.util.Locale.ROOT)
        return when {
            listOf("HDR", "DOLBY VISION", "DV", "HLG", "10BIT").any { it in text } -> 0xFF703B91
            listOf("ATMOS", "DTS", "AAC", "AUDIO", "TRUEHD", "DDP", "5.1", "7.1").any { it in text } -> 0xFF744B17
            listOf("HEVC", "AV1", "H.264", "H.265", "X264", "X265", "AVC").any { it in text } -> 0xFF176C50
            listOf("4K", "2160", "1440", "1080", "720", "RESOLUTION").any { it in text } -> 0xFF245C9C
            listOf("GB", "MB", "SIZE", "FILE").any { it in text } -> 0xFF665710
            else -> 0xFF7C384E
        }
    }
}
