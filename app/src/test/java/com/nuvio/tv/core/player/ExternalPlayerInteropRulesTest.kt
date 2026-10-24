package com.nuvio.tv.core.player

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class ExternalPlayerInteropRulesTest {
    private val rules = ExternalPlayerInteropRules

    @Test fun `VLC resume uses Long milliseconds while Just and generic use bounded Int milliseconds`() {
        val position = Int.MAX_VALUE.toLong() + 500L
        val vlc = rules.resumePlan("org.videolan.vlc", position, false)
        val just = rules.resumePlan("com.brouken.player", position, false)
        val generic = rules.resumePlan("com.example.player", 90_123L, false)
        assertTrue(vlc.useLongPosition)
        assertEquals(position, vlc.positionMs)
        assertFalse(just.useLongPosition)
        assertEquals(Int.MAX_VALUE, just.intPositionMs)
        assertEquals(90_123, generic.intPositionMs)
    }

    @Test fun `explicit start from beginning overrides saved resume and negative positions stay absent`() {
        val beginning = rules.resumePlan("org.videolan.vlc", 42_000L, true)
        assertTrue(beginning.sendPosition)
        assertEquals(0L, beginning.positionMs)
        assertEquals(0, beginning.intPositionMs)
        assertTrue(beginning.startFromBeginning)
        assertFalse(rules.resumePlan(null, -1L, false).sendPosition)
        assertFalse(rules.resumePlan(null, 0L, false).sendPosition)
    }

    @Test fun `MX header format preserves alternating names and values including colons in values`() {
        assertArrayEquals(arrayOf("Authorization", "Bearer synthetic", "Referer", "https://example.test/a:b"),
            rules.headerPairs("com.mxtech.videoplayer.ad", linkedMapOf(
                "Authorization" to "Bearer synthetic", "Referer" to "https://example.test/a:b")))
    }

    @Test fun `known players without intent header support reject required headers with fixed explanation`() {
        listOf("com.brouken.player", "com.brouken.player.debug", "org.videolan.vlc", "org.videolan.vlc.debug").forEach { target ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                rules.headerPairs(target, mapOf("Authorization" to "private-synthetic-value"))
            }
            assertEquals(ExternalPlayerInteropRules.UNSUPPORTED_HEADERS_MESSAGE, failure.message)
            assertFalse(failure.toString().contains("private-synthetic-value"))
            assertTrue(rules.headerPairs(target, emptyMap()).isEmpty())
        }
    }

    @Test fun `invalid or unbounded header payloads are rejected before another app receives them`() {
        listOf(mapOf("Bad\nName" to "x"), mapOf("X-Test" to "line\r\nbreak"),
            mapOf("X-Test" to "x".repeat(8_193)), (0..64).associate { "X-$it" to "x" }).forEach { headers ->
            assertThrows(IllegalArgumentException::class.java) { rules.headerPairs("com.example.player", headers) }
        }
    }

    @Test fun `subtitle handoff only grants app owned cached subtitle files and retains format`() {
        val valid = SubtitleInput("content://com.syclop.test.fileprovider/cache/subtitles/eng_track.ass", "English", "en")
        val rejected = listOf(
            "magnet:?xt=urn:btih:synthetic", "torrent://synthetic", "https://tracker.example.test/a.srt",
            "file:///private/a.srt", "content://another.app/cache/subtitles/a.srt",
            "content://com.syclop.test.fileprovider/cache/other/a.srt",
            "content://com.syclop.test.fileprovider/cache/subtitles/%2e%2e",
            "content://com.syclop.test.fileprovider/cache/subtitles/a.srt?token=synthetic"
        ).map { valid.copy(url = it) }
        assertEquals(listOf(valid), rules.cachedSubtitles("com.syclop.test", rejected + valid))
        assertEquals("eng_track.ass", rules.subtitleFilename(valid))
    }

    @Test fun `subtitle and skip extras are bounded before Binder handoff`() {
        val subtitle = SubtitleInput("content://com.syclop.test.fileprovider/cache/subtitles/eng.vtt", "x".repeat(300), "x".repeat(50))
        val result = rules.cachedSubtitles("com.syclop.test", List(40) { subtitle })
        assertEquals(32, result.size)
        assertEquals(256, result.first().name.length)
        assertEquals(32, result.first().lang.length)
        assertNull(rules.skipSegments("x".repeat(16_385)))
        assertEquals("[]", rules.skipSegments("[]"))
    }

    @Test fun `skip timing handoff reconstructs only allowed fields and discards source metadata`() {
        val result = rules.skipSegments("""[{"type":"intro","start":1,"end":20,
            "provider":"private-provider","infoHash":"private-hash","trackers":["https://tracker.example.test"]}]""")!!
        assertEquals(setOf("type", "start", "end"), Json.parseToJsonElement(result).jsonArray.first().jsonObject.keys)
        listOf("private-provider", "private-hash", "tracker.example.test").forEach { assertFalse(result.contains(it)) }
    }

    @Test fun `malformed unknown and invalid skip timings cannot reach the external player`() {
        listOf("not-json", "{}", """[{"type":"magnet:private","start":1,"end":2}]""",
            """[{"type":"intro","start":"1","end":2}]""", """[{"type":"intro","start":-1,"end":2}]""",
            """[{"type":"intro","start":2,"end":1}]""", """[{"type":"intro","start":1,"end":1e999}]""")
            .forEach { assertNull(rules.skipSegments(it)) }
        assertNull(rules.skipSegments(List(65) { """{"type":"intro","start":1,"end":2}""" }.joinToString(",", "[", "]")))
    }

    @Test fun `Just Player end only completion remains complete without inventing duration`() {
        val result = rules.parseResult(true, "com.mxtech.intent.result.VIEW", null, null, "playback_completion")!!
        assertEquals(0L, result.positionMs)
        assertNull(result.durationMs)
        assertFalse(result.endedByUser)
    }

    @Test fun `VLC user exit retains Long millisecond progress and does not claim natural completion`() {
        val result = rules.parseResult(false, "org.videolan.vlc.player.result", 1_234_567L, 7_654_321L, null)!!
        assertEquals(1_234_567L, result.positionMs)
        assertEquals(7_654_321L, result.durationMs)
        assertTrue(result.endedByUser)
    }

    @Test fun `only exact mpv EOF result shape allows empty success to count as completion`() {
        assertFalse(rules.parseResult(true, "is.xyz.mpv.MPVActivity.result", null, null, null)!!.endedByUser)
        assertNull(rules.parseResult(true, "com.example.result", null, null, null))
        assertNull(rules.parseResult(false, "is.xyz.mpv.MPVActivity.result", null, null, null))
        assertNull(rules.parseResult(true, "is.xyz.mpv.MPVActivity.result", null, null, "user"))
        assertNull(rules.parseResult(true, null, -1L, -1L, null))
    }

    @Test fun `handoff model string representations redact signed URL title and credential values`() {
        val subtitle = SubtitleInput("content://private-subtitle", "private-subtitle-name", "en")
        val input = ExternalPlayerInput(url = "https://cdn.example.test/private?token=secret-value", title = "private-title",
            headers = mapOf("Authorization" to "private-key-value"), subtitles = listOf(subtitle), playerPackage = "com.brouken.player")
        listOf("secret-value", "private-title", "private-key-value", "private-subtitle").forEach {
            assertFalse(input.toString().contains(it))
            assertFalse(subtitle.toString().contains(it))
        }
    }
}
