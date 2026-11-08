package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeRulesParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamBadgePatternSafetyTest {
    @Test(timeout = 2_000)
    fun `nested repetition cannot stall badge matching`() {
        val filters = StreamBadgeMatcher.compile(rules("^(a+)+$"))
        assertEquals(1, filters.size)
        assertTrue(StreamBadgeMatcher.matchedBadges(listOf("a".repeat(20_000) + "!"), filters).isEmpty())
    }

    @Test
    fun `unsupported assertions and backreferences reject new imports`() {
        for (pattern in listOf("(a)\\1", "(?<=a)b", "a(?=b)b", "^(?!x).*HDR", "^(?!.*DV).*HDR|DV", "(?U)foo", "(?iU:foo)")) {
            assertRejected(pattern)
        }
    }

    @Test(timeout = 2_000)
    fun `repeated matches cannot multiply suffix assertion work without a bound`() {
        val filters = StreamBadgeMatcher.compile(rules("a(?!.*b)"))
        assertEquals(1, filters.size)
        assertTrue(StreamBadgeMatcher.matchedBadges(listOf("a".repeat(20_000) + "b"), filters).isEmpty())
    }

    @Test
    fun `legacy unsupported rules cannot broaden their badge match`() {
        val filters = StreamBadgeMatcher.compile(rules("(?<=HDR)DV"))
        assertTrue(filters.isEmpty())
        assertTrue(StreamBadgeMatcher.matchedBadges(listOf("HDRDV"), filters).isEmpty())
    }

    @Test
    fun `editor exclusions retain HDR and audio priorities`() {
        val hdr = "(?i)^(?!.*\\b(DV|DoVi|Dolby[\\s.]?Vision)\\b)(?!.*HDR10\\+).*\\bHDR10\\b"
        val filters = StreamBadgeMatcher.compile(rules(hdr))
        assertEquals(listOf("Badge"), StreamBadgeMatcher.matchedBadges(listOf("Movie HDR10"), filters).map { it.name })
        for (candidate in listOf("Movie DV HDR10", "Movie HDR10+", "Movie Dolby.Vision HDR10")) {
            assertTrue(StreamBadgeMatcher.matchedBadges(listOf(candidate), filters).isEmpty())
        }
    }

    @Test
    fun `editor language boundaries and suffix exclusions retain the entire token`() {
        val english = StreamBadgeMatcher.compile(rules("(?i)(?:^| )(en|eng|english)(?= |$)"))
        for (candidate in listOf("english", " eng ", "Video en")) {
            assertEquals(1, StreamBadgeMatcher.matchedBadges(listOf(candidate), english).size)
        }
        assertTrue(StreamBadgeMatcher.matchedBadges(listOf("englishdub"), english).isEmpty())
        val portuguese = StreamBadgeMatcher.compile(rules("(?i)(?:^| )(pt|por|portuguese)(?= |$)(?!.*\\bpt-br\\b)"))
        assertEquals(1, StreamBadgeMatcher.matchedBadges(listOf("Video portuguese pt"), portuguese).size)
        assertTrue(StreamBadgeMatcher.matchedBadges(listOf("Video pt pt-br"), portuguese).isEmpty())
    }

    @Test
    fun `regex size nesting and repeat expansion are bounded at import and for saved rules`() {
        for (pattern in listOf("a".repeat(2_049), "(".repeat(33) + "x" + ")".repeat(33), "(a{1000}){1000}")) {
            assertRejected(pattern)
            assertTrue(StreamBadgeMatcher.compile(rules(pattern)).isEmpty())
        }
    }

    private fun assertRejected(pattern: String) {
        val payload = """{"filters":[{"name":"Badge","pattern":${Json.encodeToString(pattern)}}]}"""
        try {
            StreamBadgeRulesParser.parse("https://example.com/badges.json", payload)
            fail("Unsafe or unsupported badge pattern was admitted")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("badge", ignoreCase = true))
        }
    }

    private fun rules(pattern: String) = StreamBadgeRules(imports = listOf(StreamBadgeImport(
        sourceUrl = "https://example.com/badges.json",
        filters = listOf(StreamBadgeFilter(name = "Badge", pattern = pattern))
    )))
}
