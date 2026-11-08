package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMatchTextTest {
    private val filters = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(
                        filter("4K", "(?i)\\b(2160p|4k)\\b"),
                        filter("WebDL", "(?i)\\bweb[-. ]?dl\\b"),
                        filter("Vision", "(?i)\\b(dv|dovi|dolby[ .]?vision)\\b"),
                        filter("HDR", "(?i)\\bhdr(10)?\\b"),
                        filter("10bit", "(?i)\\b10[ -]?bit\\b"),
                        filter("HEVC", "(?i)\\b(hevc|[xh][ .]?265)\\b"),
                        filter("Atmos", "(?i)\\batmos\\b"),
                        filter("English", "(?i)\\b(eng|english)\\b")
                    )
                )
            )
        )
    )

    // Minimal matching rules from the editor's exported English player pack.
    private val editorFilters = StreamBadgeMatcher.compile(
        StreamBadgeRules(imports = listOf(StreamBadgeImport(
            sourceUrl = "https://example.invalid/editor-player.json",
            filters = listOf(
                filter("8K", """(?i)\b(4320p|8k)\b"""),
                filter("4K", """(?i)\b(2160p|4k)\b"""),
                filter("HDR10+", """(?i)^(?!.*\b(DV|DoVi|Dolby[\s.]?Vision)\b).*\bHDR10\+"""),
                filter("HDR10", """(?i)^(?!.*\b(DV|DoVi|Dolby[\s.]?Vision)\b)(?!.*HDR10\+).*\bHDR10\b"""),
                filter("DTS:X", """(?i)\bDTS:X\b"""),
                filter("DTS-HD MA", """(?i)^(?!.*\bDTS:X\b).*\bDTS-HD MA\b"""),
                filter("DTS-HD", """(?i)^(?!.*\bDTS:X\b)(?!.*\bDTS-HD MA\b).*\bDTS-HD\b"""),
                filter("English", """(?i)(?:^| )(en|eng|english)(?= |$)"""),
                filter("Japanese", """(?i)(?:^| )(ja|jpn|japanese)(?= |$)"""),
                filter("Portuguese", """(?i)(?:^| )(pt|por|portuguese)(?= |$)(?!.*\bpt-br\b)"""),
                filter("Brazilian Portuguese", """(?i)(?:^| )(pt-br|pob|portuguese[\s(]+brazil)""")
            )
        )))
    )

    private fun filter(name: String, pattern: String) =
        StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://example.com/$name.png")

    private fun matchedNames(info: PlayerMediaInfo, languages: List<String> = emptyList()): List<String> =
        StreamBadgeMatcher.matchedBadges(listOf(playbackMatchText(info, languages)), filters).map { it.name }

    private fun editorNames(info: PlayerMediaInfo = PlayerMediaInfo(), languages: List<String> = emptyList()): List<String> =
        StreamBadgeMatcher.matchedBadges(listOf(playbackMatchText(info, languages)), editorFilters).map { it.name }

    @Test
    fun `matches what is playing`() {
        val info = PlayerMediaInfo(
            videoCodec = "HEVC",
            videoWidth = 3840,
            videoHeight = 1600,
            audioCodec = "E-AC-3-JOC",
            audioChannels = 6,
            hdr = listOf("DV", "HDR10"),
            bitDepth = 10
        )
        assertEquals(
            listOf("4K", "Vision", "HDR", "10bit", "HEVC", "Atmos", "English"),
            matchedNames(info, listOf("eng"))
        )
    }

    @Test
    fun `skips badges the playback does not have`() {
        val info = PlayerMediaInfo(videoCodec = "AVC", videoWidth = 1920, videoHeight = 1080, audioCodec = "AAC", audioChannels = 2)
        assertEquals(emptyList<String>(), matchedNames(info))
    }

    @Test
    fun `reads atmos from mpv audio profile`() {
        val info = PlayerMediaInfo(audioCodec = "TrueHD", audioProfile = "Dolby TrueHD + Dolby Atmos")
        assertEquals(listOf("Atmos"), matchedNames(info))
    }

    @Test
    fun `matches exact editor patterns at the text edges and for regional track codes`() {
        assertEquals(listOf("English"), editorNames(languages = listOf("en")))
        assertEquals(listOf("Japanese"), editorNames(PlayerMediaInfo(audioCodec = "AAC"), listOf("ja-JP")))
        assertEquals(listOf("English"), editorNames(languages = listOf("en_US")))
    }

    @Test
    fun `Brazilian Portuguese gets its own editor badge without generic Portuguese`() {
        assertEquals(listOf("Brazilian Portuguese"), editorNames(languages = listOf("pt-BR", "Portuguese (Brazil)")))
        assertEquals(listOf("Portuguese"), editorNames(languages = listOf("pt-PT")))
    }

    @Test
    fun `explicit 8K and HDR10 plus match their editor badges without guessing bit depth`() {
        val info = PlayerMediaInfo(videoWidth = 7680, videoHeight = 3200, hdr = listOf("HDR10+"))
        assertEquals(listOf("8K", "HDR10+"), editorNames(info))
        assertEquals(listOf("HDR"), matchedNames(PlayerMediaInfo(hdr = listOf("HDR"))))
        assertEquals("", playbackMatchText(PlayerMediaInfo(bitDepth = -1), listOf("und", "Unknown")))
    }

    @Test
    fun `editor DTS variants require specific decoder labels`() {
        assertEquals(listOf("DTS:X"), editorNames(PlayerMediaInfo(audioCodec = "DTS:X")))
        assertEquals(listOf("DTS-HD MA"), editorNames(PlayerMediaInfo(audioCodec = "DTS-HD MA")))
        assertEquals(listOf("DTS-HD"), editorNames(PlayerMediaInfo(audioCodec = "DTS-HD", audioProfile = "Matrix")))
    }

    @Test
    fun `uses the pack chosen for the player`() {
        val streamPack = StreamBadgeImport(
            sourceUrl = "https://example.com/stream.json",
            filters = listOf(filter("Stream4K", "\u2063\u200b\u2063"))
        )
        val playerPack = StreamBadgeImport(
            sourceUrl = "https://example.com/player.json",
            filters = listOf(filter("Player4K", "(?i)\\b2160p\\b"))
        )
        val rules = StreamBadgeRules(imports = listOf(streamPack, playerPack)).normalized()
        val text = playbackMatchText(PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160), emptyList())
        fun namesFor(source: String) =
            StreamBadgeMatcher.matchedBadges(listOf(text), StreamBadgeMatcher.compile(rules.forPlayer(source))).map { it.name }

        assertEquals(emptyList<String>(), namesFor(""))
        assertEquals(listOf("Player4K"), namesFor("https://example.com/player.json"))
        assertEquals(emptyList<String>(), namesFor("https://example.com/removed.json"))
    }

    @Test
    fun `shows nothing before playback info loads`() {
        assertEquals(emptyList<String>(), matchedNames(PlayerMediaInfo()))
    }
}
