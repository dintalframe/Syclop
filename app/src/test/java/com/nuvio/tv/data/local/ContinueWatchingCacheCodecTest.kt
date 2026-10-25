package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingCacheCodecTest {
    private val gson = Gson()
    private fun progress() = CachedInProgressItem("mal:42", "anime", "Fixture", null, null, null,
        "mal:42:2", 0, 2, null, 0L, 0L, 0L, null, genres = listOf("Adventure"))
    private fun next() = CachedNextUpItem("tt42", "series", "Fixture", null, null, null,
        "tt42:0:0", 0, 0, null, thumbnail = null, lastWatched = 0L, sortTimestamp = -1L,
        releaseTimestamp = -315619200000L)
    private fun row(value: Any) = JsonParser.parseString(gson.toJson(value)).asJsonObject

    @Test fun `real Gson can bypass Kotlin required identity but codec rejects it`() {
        val legacy = """[{"a":"tt42","b":"Fixture"}]"""
        assertNull(gson.fromJson(JsonParser.parseString(legacy).asJsonArray[0], CachedInProgressItem::class.java).contentId)
        assertTrue(ContinueWatchingCacheCodec.inProgress(legacy).isEmpty())
        assertTrue(ContinueWatchingCacheCodec.nextUp(legacy).isEmpty())
    }
    @Test fun `valid canonical cache round trips without losing metadata`() {
        assertEquals(listOf(progress()), ContinueWatchingCacheCodec.inProgress(gson.toJson(listOf(progress()))))
        assertEquals(listOf(next()), ContinueWatchingCacheCodec.nextUp(gson.toJson(listOf(next()))))
    }
    @Test fun `missing null blank or wrongly typed required identity is rejected`() {
        for (name in listOf("contentId", "contentType", "name", "videoId")) {
            for (bad in listOf(JsonNull.INSTANCE, gson.toJsonTree(3), gson.toJsonTree(" "))) {
                val malformed = row(progress()).apply { add(name, bad) }
                assertTrue(ContinueWatchingCacheCodec.inProgress("[$malformed]").isEmpty())
            }
            val missing = row(next()).apply { remove(name) }
            assertTrue(ContinueWatchingCacheCodec.nextUp("[$missing]").isEmpty())
        }
    }
    @Test fun `one corrupt row does not discard valid neighbors`() {
        val json = "[null,${gson.toJson(progress())},{\"contentId\":null},3,${gson.toJson(progress().copy(contentId = "tt43"))}]"
        assertEquals(listOf("mal:42", "tt43"), ContinueWatchingCacheCodec.inProgress(json).map { it.contentId })
    }
    @Test fun `old optional omissions retain constructor defaults rather than Unsafe false or null`() {
        val old = row(next()).apply {
            for (key in listOf("genres", "hasAired", "ratingVisible", "isReleaseAlert", "isNewSeasonRelease", "releaseTimestamp", "seedSeason", "seedEpisode")) remove(key)
        }
        val restored = ContinueWatchingCacheCodec.nextUp("[$old]").single()
        assertTrue(restored.hasAired)
        assertTrue(restored.ratingVisible)
        assertFalse(restored.isReleaseAlert)
        assertFalse(restored.isNewSeasonRelease)
        assertEquals(emptyList<String>(), restored.genres)
        assertNull(restored.releaseTimestamp)
    }
    @Test fun `optional explicit false values are preserved`() {
        val expected = next().copy(hasAired = false, ratingVisible = false, isReleaseAlert = true)
        assertEquals(expected, ContinueWatchingCacheCodec.nextUp(gson.toJson(listOf(expected))).single())
    }
    @Test fun `wrong types are not coerced into identity primitives or flags`() {
        for ((field, value) in listOf("position" to "12", "duration" to true, "lastWatched" to "5", "season" to "1", "ratingVisible" to "true")) {
            val bad = row(progress()).apply { add(field, gson.toJsonTree(value)) }
            assertTrue(ContinueWatchingCacheCodec.inProgress("[$bad]").isEmpty())
        }
        for (field in listOf("position", "duration", "lastWatched")) {
            val bad = row(progress()).apply { remove(field) }
            assertTrue(ContinueWatchingCacheCodec.inProgress("[$bad]").isEmpty())
        }
    }
    @Test fun `overflow fractional and negative progress primitive numbers are rejected`() {
        for ((field, value) in listOf("position" to "-1", "duration" to "1.5", "lastWatched" to "9223372036854775808", "season" to "2147483648")) {
            val bad = row(progress()).apply { add(field, JsonParser.parseString(value)) }
            assertTrue(ContinueWatchingCacheCodec.inProgress("[$bad]").isEmpty())
        }
    }
    @Test fun `legitimate zero unknown duration nullable coordinates and signed release timestamps survive`() {
        val expected = progress().copy(position = 50L, duration = 0L, season = null, episode = null)
        assertEquals(expected, ContinueWatchingCacheCodec.inProgress(gson.toJson(listOf(expected))).single())
        assertEquals(next(), ContinueWatchingCacheCodec.nextUp(gson.toJson(listOf(next()))).single())
        assertEquals(progress().copy(position = 200L, duration = 100L), ContinueWatchingCacheCodec.inProgress(gson.toJson(listOf(progress().copy(position = 200L, duration = 100L)))).single())
    }
    @Test fun `nonfinite or out of range floats cannot reach progress calculations`() {
        for (value in listOf("1e999", "-1", "100.1", "\"NaN\"")) {
            val bad = row(progress()).apply { add("progressPercent", JsonParser.parseString(value)) }
            assertTrue(ContinueWatchingCacheCodec.inProgress("[$bad]").isEmpty())
        }
        val badRating = row(next()).apply { addProperty("imdbRating", 11) }
        assertTrue(ContinueWatchingCacheCodec.nextUp("[$badRating]").isEmpty())
    }
    @Test fun `null genre lists null entries and wrong nested shapes are discarded`() {
        for (value in listOf("null", "{}", "[null]", "[3]")) {
            val bad = row(progress()).apply { add("genres", JsonParser.parseString(value)) }
            assertTrue(ContinueWatchingCacheCodec.inProgress("[$bad]").isEmpty())
        }
        val badText = row(next()).apply { add("episodeTitle", JsonParser.parseString("{}")) }
        assertTrue(ContinueWatchingCacheCodec.nextUp("[$badText]").isEmpty())
    }
    @Test fun `malformed or nonarray snapshots fail closed`() {
        for (json in listOf("{", "null", "{}", "12", "[")) assertTrue(ContinueWatchingCacheCodec.nextUp(json).isEmpty())
    }
    @Test fun `oversized snapshots and excessive row counts fail closed`() {
        assertTrue(ContinueWatchingCacheCodec.inProgress(" ".repeat(ContinueWatchingCacheCodec.MAX_BYTES.toInt() + 1)).isEmpty())
        assertTrue(ContinueWatchingCacheCodec.inProgress(List(2_001) { "null" }.joinToString(",", "[", "]")).isEmpty())
    }
}
