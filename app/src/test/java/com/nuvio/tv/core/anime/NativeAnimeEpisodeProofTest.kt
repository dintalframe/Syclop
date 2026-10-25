package com.nuvio.tv.core.anime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NativeAnimeEpisodeProofTest {
    @Test fun `minimal public identity maps MAL relative one to actual season two absolute twenty nine`() {
        val record = requireNotNull(parse())
        val proof = requireNotNull(record.episodes[1])
        assertEquals(59978L, record.malId)
        assertEquals(59978L, proof.malId)
        assertEquals(1, proof.malEpisode)
        assertEquals(424536L, proof.tvdbShowId)
        assertEquals(2, proof.tvdbSeason)
        assertEquals(1, proof.tvdbEpisode)
        assertEquals(29, proof.absoluteEpisode)
    }

    @Test fun `original series uses its own exact MAL identity and season one mapping`() {
        val source = fixture().replace("59978", "52991").replace("\"seasonNumber\":2", "\"seasonNumber\":1")
            .replace("\"absoluteEpisodeNumber\":29", "\"absoluteEpisodeNumber\":1")
        val proof = requireNotNull(NativeAnimeMappingCodec.parse(source, 52991)?.episodes?.get(1))
        assertTrue(proof.matchesFile("Frieren.S01E01.mkv"))
        assertFalse(proof.matchesFile("Frieren.S02E01.mkv"))
    }

    @Test fun `record cannot expose a mutable proof map`() {
        val record = requireNotNull(parse())
        try {
            (record.episodes as MutableMap<Int, NativeAnimeEpisodeProof>).clear()
            fail("Read-only mapping was mutable")
        } catch (_: UnsupportedOperationException) { }
        assertEquals(setOf(1), record.episodes.keys)
    }

    @Test fun `cache key binds the exact public episode identity`() {
        val first = requireNotNull(parse()?.episodes?.get(1))
        val same = requireNotNull(parse()?.episodes?.get(1))
        val changed = requireNotNull(parse(mutate { row().addProperty("tvdbId", 10719110) })?.episodes?.get(1))
        assertTrue(first.cacheKey.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first.cacheKey, same.cacheKey)
        assertNotEquals(first.cacheKey, changed.cacheKey)
        assertEquals("NativeAnimeEpisodeProof(validated=true)", first.toString())
    }

    @Test fun `wrong MAL expected identity and wrong TVDB show fail closed`() {
        assertNull(NativeAnimeMappingCodec.parse(fixture(), 52991))
        assertNull(parse(mutate { row().addProperty("tvdbShowId", 123) }))
        assertNull(NativeAnimeMappingCodec.parse(fixture(), 0))
        assertNull(NativeAnimeMappingCodec.parse(fixture(), Long.MAX_VALUE))
    }

    @Test fun `unknown structural fields at every schema level are rejected`() {
        assertNull(parse(mutate { addProperty("newRoot", true) }))
        assertNull(parse(mutate { getAsJsonObject("mappings").addProperty("newMapping", 1) }))
        assertNull(parse(mutate { row().addProperty("newEpisode", 1) }))
        assertNull(parse(mutate { getAsJsonArray("images").add(JsonObject().apply { addProperty("other", "x") }) }))
    }

    @Test fun `duplicate object keys cannot silently replace identities or episode entries`() {
        assertNull(parse(fixture().replace("\"mal_id\":59978", "\"mal_id\":52991,\"mal_id\":59978")))
        assertNull(parse(fixture().replace("\"episodes\":{", "\"episodes\":{\"1\":" + episode() + ",")))
        assertNull(parse(fixture().replace("\"episode\":\"1\"", "\"episode\":\"2\",\"episode\":\"1\"")))
    }

    @Test fun `episode key must equal its inner canonical regular episode`() {
        assertNull(parse(mutate { row().addProperty("episode", "2") }))
        assertNull(parse(fixture().replace("\"1\":{", "\"01\":{")))
        assertNull(parse(fixture().replace("\"1\":{", "\"1.5\":{")))
        assertNull(parse(mutate { row().addProperty("episode", 1) }))
    }

    @Test fun `required coordinates and IDs cannot be absent null strings fractions negative or overflow`() {
        for (field in listOf("tvdbShowId", "tvdbId", "seasonNumber", "episodeNumber")) {
            assertNull(field, parse(mutate { row().remove(field) }))
            for (literal in listOf("null", "0", "-1", "1.5", "2147483648", "\"1\"", "true")) {
                assertNull("$field=$literal", parse(mutate { row().add(field, JsonParser.parseString(literal)) }))
            }
        }
    }

    @Test fun `coordinate and body bounds fail closed`() {
        assertNull(parse(mutate { row().addProperty("seasonNumber", 1001) }))
        assertNull(parse(mutate { row().addProperty("episodeNumber", 4001) }))
        assertNull(parse(mutate { row().addProperty("absoluteEpisodeNumber", 100001) }))
        assertNull(parse(mutate { addProperty("episodeCount", 4001) }))
        assertNull(parse(" ".repeat(2 * 1024 * 1024 + 1)))
        assertNull(parse(mutate { row().addProperty("overview", "x".repeat(16385)) }))
    }

    @Test fun `specials are validated but never produce an episode proof`() {
        val json = mutate {
            getAsJsonObject("episodes").add("S1", JsonParser.parseString("""{"episode":"S1","seasonNumber":0,"episodeNumber":1,"title":{"en":"Special"}}"""))
        }
        assertEquals(setOf(1), parse(json)?.episodes?.keys)
        assertNull(parse(JsonParser.parseString(json).asJsonObject.apply { getAsJsonObject("episodes").remove("1") }.toString()))
        assertNull(parse(json.replace("\"episode\":\"S1\"", "\"episode\":\"S2\"")))
    }

    @Test fun `regular mappings cannot duplicate TVDB coordinate episode ID or absolute number`() {
        for (field in listOf("episodeNumber", "tvdbId", "absoluteEpisodeNumber")) {
            val second = JsonParser.parseString(episode(2, 2, 30, 10719110)).asJsonObject
            second.add(field, JsonParser.parseString(episode()).asJsonObject[field])
            assertNull(field, parse(mutate { getAsJsonObject("episodes").add("2", second) }))
        }
    }

    @Test fun `optional known metadata must retain its documented structural type`() {
        assertNull(parse(mutate { addProperty("titles", "not a language object") }))
        assertNull(parse(mutate { row().addProperty("title", "not a language object") }))
        assertNull(parse(mutate { row().addProperty("runtime", "24") }))
        assertNull(parse(mutate { row().addProperty("rating", "NaN") }))
        assertNull(parse(mutate { getAsJsonObject("mappings").addProperty("anidb_id", "18886") }))
        assertNotNull(parse(mutate { row().addProperty("rating", "8.48"); row().addProperty("runtime", 24) }))
    }

    @Test fun `invalid JSON trailing content and excess nesting never produce proof`() {
        for (bad in listOf("", "null", "[]", fixture() + "{}", fixture().dropLast(1), "{" + "\"x\":{".repeat(18) + "}".repeat(19))) {
            assertNull(parse(bad))
        }
    }

    @Test fun `mapped season episode labels and x notation match actual basename`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        for (file in listOf("Frieren.S02E01.mkv", "Frieren.s2e1.1080p.mkv", "Frieren.S02E01_1080p.mkv", "Frieren.E29-2160p.mkv", "Frieren.2x01.mp4", "Frieren.02x001.mkv", "folder\\Frieren.S02E01.mkv")) {
            assertTrue(file, proof.matchesFile(file))
        }
    }

    @Test fun `unambiguous absolute E and anime dash labels match only mapped absolute episode`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        for (file in listOf("Frieren - 29 [1080p].mkv", "Frieren-029.mkv", "Frieren.E29.mkv", "[Group] Frieren E029 [HEVC].mkv")) {
            assertTrue(file, proof.matchesFile(file))
        }
    }

    @Test fun `wrong season relative episode or suffix collisions never match`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        for (file in listOf("Frieren.S01E01.mkv", "Frieren.S02E02.mkv", "Frieren.S02E010.mkv", "Frieren.2x010.mkv", "Frieren.E290.mkv", "Frieren - 290.mkv", "Frieren.29.mkv", "Frieren.S2.Episode1.mkv")) {
            assertFalse(file, proof.matchesFile(file))
        }
    }

    @Test fun `ranges compound episodes fractions and conflicting labels are rejected`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        for (file in listOf("Frieren.S02E01-02.mkv", "Frieren.S02E01E02.mkv", "Frieren - 29-30.mkv", "Frieren.E29-E30.mkv", "Frieren.E29+30.mkv", "Frieren.E29.5.mkv", "Frieren - 29.5.mkv", "Frieren.S02E01 E30.mkv", "Frieren.E29 S1.mkv", "Frieren.S02E01 S01E01.mkv", "Frieren.2x01.5.mkv", "Frieren.E29_30.mkv", "Frieren.S02E01_02.mkv", "Frieren.E29-30v2.mkv", "Frieren.S02E01_02v2.mkv", "Frieren.2x01.5v2.mkv", "Frieren.E29-30dub.mkv")) {
            assertFalse(file, proof.matchesFile(file))
        }
    }

    @Test fun `parent folder and unknown numeric filename cannot prove episode identity`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        for (file in listOf("Frieren.S02E01/video.mkv", "Frieren - 29/video.mkv", "Frieren/00001.m2ts", "Frieren/episode.mkv", "Frieren/E29\u0000.mkv", "x".repeat(4097))) {
            assertFalse(file.take(100), proof.matchesFile(file))
        }
    }

    @Test fun `without verified absolute mapping only season episode notation is accepted`() {
        val proof = requireNotNull(parse(mutate { row().remove("absoluteEpisodeNumber") })?.episodes?.get(1))
        assertTrue(proof.matchesFile("Frieren.S02E01.mkv"))
        assertFalse(proof.matchesFile("Frieren - 29.mkv"))
        assertFalse(proof.matchesFile("Frieren.E29.mkv"))
    }

    @Test fun `same exact coordinates on unrelated franchise never match actual file`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        assertTrue(proof.hasTrustedTitleIdentity)
        for (file in listOf("Night.On.Earth.S02E01.mkv", "Welcome.to.Derry.E29.mkv",
            "[Frieren] Night.On.Earth.S02E01.mkv", "Frieren/Other.E29.mkv",
            "Other.S02E01.Frieren.mkv")) assertFalse(file, proof.matchesFile(file))
    }

    @Test fun `NFKC title and every coordinate range or fraction check use same basename`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        assertTrue(proof.matchesFile("Ｆｒｉｅｒｅｎ．Ｓ０２Ｅ０１.mkv"))
        for (file in listOf("Frieren Ｓ０１Ｅ０１ E29.mkv", "Frieren Ｓ０２Ｅ０２ E29.mkv",
            "Frieren Ｅ２９＿３０.mkv", "Frieren Ｓ０２Ｅ０１＿０２.mkv", "Frieren ２ｘ０１．５.mkv")) {
            assertFalse(file, proof.matchesFile(file))
        }
    }

    @Test fun `missing unusable or wrong typed mapping titles fail closed before playable proof`() {
        assertNull(parse(mutate { remove("titles") }))
        assertNull(parse(mutate { add("titles", JsonObject()) }))
        assertNull(parse(mutate { getAsJsonObject("titles").addProperty("en", "Anime Season 2") }))
        assertNull(parse(mutate { getAsJsonObject("titles").addProperty("en", 29) }))
    }

    @Test fun `cache identity binds aliases and cannot rebind to another MAL record`() {
        val proof = requireNotNull(parse()?.episodes?.get(1))
        val changed = requireNotNull(parse(mutate { getAsJsonObject("titles").addProperty("ja", "葬送のフリーレン") })?.episodes?.get(1))
        assertNotEquals(proof.cacheKey, changed.cacheKey)
        val other = requireNotNull(NativeAnimeTitleIdentity.create(52991, "Frieren", emptyList()))
        assertNull(proof.withIdentity(other))
    }

    private fun parse(json: String = fixture()) = NativeAnimeMappingCodec.parse(json, 59978)
    private fun mutate(block: JsonObject.() -> Unit): String = JsonParser.parseString(fixture()).asJsonObject.apply(block).toString()
    private fun JsonObject.row() = getAsJsonObject("episodes").getAsJsonObject("1")
    private fun fixture() = """{"mappings":{"mal_id":59978,"thetvdb_id":424536},"episodeCount":10,"specialCount":0,"titles":{"en":"Frieren 2nd Season"},"images":[],"episodes":{"1":${episode()}}}"""
    private fun episode(relative: Int = 1, episode: Int = 1, absolute: Int = 29, id: Int = 10719109) =
        """{"tvdbShowId":424536,"tvdbId":$id,"seasonNumber":2,"episodeNumber":$episode,"absoluteEpisodeNumber":$absolute,"episode":"$relative"}"""
}
