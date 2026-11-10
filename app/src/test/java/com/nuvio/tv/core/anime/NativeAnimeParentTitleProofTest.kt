package com.nuvio.tv.core.anime

import org.junit.Assert.*
import org.junit.Test

class NativeAnimeParentTitleProofTest {
    @Test fun `exact verified parent preserves year checks across punctuation whitespace and NFKC`() {
        val parent = NativeAnimeParentFixtures.PARENT.replace("\"name\":\"Cyberpunk: Edgerunners\"",
            "\"name\":\"Cyberpunk: Edgerunners (2022)\"")
        val proof = record().withVerifiedParentMetadata(parent).episodes.getValue(1)
        for (qualifier in listOf(": (2022)", " ( 2022 )", "— [2022]", "– [ 2022 ]",
            "： （ 2022 ）", "— ［　2022　］", "： （　２０２２　）")) {
            val name = "Cyberpunk Edgerunners$qualifier S02E01.mkv"
            assertTrue(name, proof.acceptsReleaseTitle(name))
            assertTrue(name, proof.matchesFile(name))
        }
        for (qualifier in listOf(": (1999)", " ( 1999 )", "— [1999]", "– [ 1999 ]",
            "： （ 1999 ）", "— ［　1999　］", "： （　１９９９　）")) {
            val name = "Cyberpunk Edgerunners$qualifier S02E01.mkv"
            assertFalse(name, proof.acceptsReleaseTitle(name))
            assertFalse(name, proof.matchesFile(name))
        }
        for (name in listOf("[Group ( 1999 )] Cyberpunk Edgerunners S02E01.mkv",
            "[1999] Cyberpunk Edgerunners S02E01.mkv", "Cyberpunk Edgerunners S02E01 : ( 1999 ).mkv")) {
            assertTrue(name, proof.acceptsReleaseTitle(name))
            assertTrue(name, proof.matchesFile(name))
        }
    }

    @Test fun `verified parent premiere year and child season year remain distinct evidence`() {
        // Synthetic qualifiers exercise binding policy, not a new public catalog assertion.
        val mapping = NativeAnimeParentFixtures.MAPPING
            .replace("Cyberpunk: Edgerunners 2", "Cyberpunk: Edgerunners Season 2 (2026)")
            .replace("Cyberpunk: Edgerunners II", "Cyberpunk: Edgerunners Season 2 (2026)")
            .replace("CYBERPUNK: EDGERUNNERS II", "Cyberpunk: Edgerunners Season 2 (2026)")
        val record = requireNotNull(NativeAnimeMappingCodec.parse(mapping, 61990))
        val parent = NativeAnimeParentFixtures.PARENT.replace("\"name\":\"Cyberpunk: Edgerunners\"",
            "\"name\":\"Cyberpunk: Edgerunners (2022)\"")
        val proof = record.withVerifiedParentMetadata(parent).episodes.getValue(1)
        assertEquals("tt12590266", proof.verifiedParentImdbId)
        for (name in listOf("Cyberpunk Edgerunners (2022) S02E01.mkv",
            "Cyberpunk Edgerunners (2026) S02E01.mkv", "Cyberpunk Edgerunners S02E01.mkv")) {
            assertTrue(name, proof.acceptsReleaseTitle(name))
            assertTrue(name, proof.matchesFile(name))
        }
        for (name in listOf("Cyberpunk Edgerunners (1999) S02E01.mkv",
            "Cyberpunk Edgerunners (2022) S01E01.mkv")) {
            assertFalse(name, proof.acceptsReleaseTitle(name))
            assertFalse(name, proof.matchesFile(name))
        }
        val unknownParent = record.withVerifiedParentMetadata(NativeAnimeParentFixtures.PARENT).episodes.getValue(1)
        assertNotEquals(unknownParent.cacheKey, proof.cacheKey)
        assertTrue(unknownParent.matchesFile("Cyberpunk Edgerunners S02E01.mkv"))
        assertFalse(unknownParent.acceptsReleaseTitle("Cyberpunk Edgerunners (1999) S02E01.mkv"))
        assertFalse(unknownParent.matchesFile("Cyberpunk Edgerunners (1999) S02E01.mkv"))
    }

    @Test fun `verified exact parent episode fixes real sequel release without changing MAL coordinates`() {
        val record = record()
        val base = record.episodes.getValue(1)
        val proof = record.withVerifiedParentMetadata(NativeAnimeParentFixtures.PARENT).episodes.getValue(1)
        assertFalse(base.matchesFile(NativeAnimeParentFixtures.RELEASE))
        assertNull(base.verifiedParentImdbId)
        assertTrue(proof.matchesFile(NativeAnimeParentFixtures.RELEASE))
        assertTrue(proof.matchesFile("Cyberpunk.Edgerunners - 11 [1080p].mkv"))
        assertTrue(proof.acceptsReleaseTitle(NativeAnimeParentFixtures.RELEASE))
        assertEquals("tt12590266", proof.verifiedParentImdbId)
        assertEquals(61990L, proof.malId)
        assertEquals(1, proof.malEpisode)
        assertEquals(384541L, proof.tvdbShowId)
        assertEquals(2, proof.tvdbSeason)
        assertEquals(1, proof.tvdbEpisode)
        assertEquals(11, proof.absoluteEpisode)
        assertNotEquals(base.cacheKey, proof.cacheKey)
    }

    @Test fun `parent title requires complete exact mapped episode and cannot admit original season or wrong sequel`() {
        val proof = enriched()
        for (name in listOf("Cyberpunk Edgerunners", "Cyberpunk Edgerunners S01E01.mkv",
            "Cyberpunk Edgerunners - 01.mkv", "Cyberpunk Edgerunners S02E02.mkv",
            "Cyberpunk Edgerunners S02E01 S01E01.mkv", "Cyberpunk Edgerunners E11-12.mkv",
            "Cyberpunk Edgerunners 3 S02E01.mkv", "Cyberpunk Edgerunners III S02E01.mkv",
            "Other Show S02E01.mkv", "[Cyberpunk Edgerunners] Other Show S02E01.mkv",
            "Other Show S02E01 Cyberpunk Edgerunners.mkv", "Cyberpunk Edgerunners/S02E01.mkv")) {
            assertFalse(name, proof.matchesFile(name))
            assertFalse(name, proof.acceptsReleaseTitle(name))
        }
        assertTrue(proof.matchesFile("[Group] Cyberpunk Edgerunners (S02E01) [1080p].mkv"))
        assertTrue(proof.matchesFile("[E11 Group] Cyberpunk Edgerunners S02E01.mkv"))
    }

    @Test fun `missing parent proof never strips numeric or roman sequel titles`() {
        val proof = record().episodes.getValue(1)
        assertFalse(proof.acceptsReleaseTitle(NativeAnimeParentFixtures.RELEASE))
        assertFalse(proof.matchesFile("Cyberpunk Edgerunners S02E01.mkv"))
        assertTrue(proof.matchesFile("Cyberpunk Edgerunners 2 S02E01.mkv"))
        assertTrue(proof.matchesFile("Cyberpunk Edgerunners II - 11.mkv"))
        assertEquals(listOf("Cyberpunk: Edgerunners 2", "Cyberpunk: Edgerunners II"), proof.queryTitles())
    }

    @Test fun `parent metadata mismatched identity type episode id title and ambiguous coordinates cannot bind`() {
        val original = NativeAnimeParentFixtures.PARENT
        val invalid = listOf(
            original.replace("\"id\":\"tt12590266\"", "\"id\":\"tt99999999\""),
            original.replace("\"imdb_id\":\"tt12590266\"", "\"imdb_id\":\"tt99999999\""),
            original.replace("\"type\":\"series\"", "\"type\":\"movie\""),
            original.replace("tt12590266:2:1", "tt12590266:1:1"),
            original.replace("\"season\":2", "\"season\":1"),
            original.replace("Last Chance for a Slow Dance", "Unrelated episode"),
            original.replace("\"name\":\"Cyberpunk: Edgerunners\"", "\"name\":\"Anime\""),
            original.replace("\"videos\":[", "\"videos\":[${NativeAnimeParentFixtures.VIDEO},"),
            original.replace("\"id\":\"tt12590266\"", "\"id\":\"tt99999999\",\"id\":\"tt12590266\""),
            "{", original + "{}"
        )
        for (metadata in invalid) {
            val proof = record().withVerifiedParentMetadata(metadata).episodes.getValue(1)
            assertNull(metadata.take(120), proof.verifiedParentImdbId)
            assertFalse(proof.matchesFile(NativeAnimeParentFixtures.RELEASE))
        }
    }

    @Test fun `mapping must declare a valid parent IMDb before any metadata can add an alias`() {
        for (mapping in listOf(
            NativeAnimeParentFixtures.MAPPING.replace(",\"imdb_id\":\"tt12590266\"", ""),
            NativeAnimeParentFixtures.MAPPING.replace("tt12590266", "not-an-imdb-id")
        )) {
            val proof = requireNotNull(NativeAnimeMappingCodec.parse(mapping, 61990))
                .withVerifiedParentMetadata(NativeAnimeParentFixtures.PARENT).episodes.getValue(1)
            assertNull(proof.verifiedParentImdbId)
            assertFalse(proof.matchesFile(NativeAnimeParentFixtures.RELEASE))
        }
        assertNull(NativeAnimeMappingCodec.parse(NativeAnimeParentFixtures.MAPPING, 42310))
    }

    @Test fun `parent metadata is episode scoped and cannot lend aliases to a different mapped episode`() {
        val mapping = NativeAnimeParentFixtures.MAPPING.replace("\"episodeNumber\":1", "\"episodeNumber\":2")
        val proof = requireNotNull(NativeAnimeMappingCodec.parse(mapping, 61990))
            .withVerifiedParentMetadata(NativeAnimeParentFixtures.PARENT).episodes.getValue(1)
        assertNull(proof.verifiedParentImdbId)
        assertFalse(proof.matchesFile("Cyberpunk Edgerunners S02E02.mkv"))
    }

    @Test fun `original MAL season one proof continues to reject sequel season two`() {
        val mapping = NativeAnimeParentFixtures.MAPPING.replace("61990", "42310")
            .replace("Cyberpunk: Edgerunners 2", "Cyberpunk: Edgerunners")
            .replace("Cyberpunk: Edgerunners II", "Cyberpunk: Edgerunners")
            .replace("\"seasonNumber\":2", "\"seasonNumber\":1")
            .replace("\"absoluteEpisodeNumber\":11", "\"absoluteEpisodeNumber\":1")
        val proof = requireNotNull(NativeAnimeMappingCodec.parse(mapping, 42310))
            .withVerifiedParentMetadata(NativeAnimeParentFixtures.PARENT).episodes.getValue(1)
        assertTrue(proof.matchesFile("Cyberpunk Edgerunners S01E01.mkv"))
        assertFalse(proof.matchesFile(NativeAnimeParentFixtures.RELEASE))
        assertFalse(proof.matchesFile("Cyberpunk Edgerunners - 11.mkv"))
    }

    @Test fun `verified parent query joins immutable bounded trusted sequel aliases without rewriting titles`() {
        val proof = enriched()
        assertEquals(listOf("Cyberpunk: Edgerunners 2", "Cyberpunk: Edgerunners", "Cyberpunk: Edgerunners II"),
            proof.queryTitles())
        assertFalse(proof.titleIdentity!!.matchesRelease(NativeAnimeParentFixtures.RELEASE))
        assertTrue(proof.acceptsReleaseTitle(NativeAnimeParentFixtures.RELEASE))
        assertFalse(proof.acceptsReleaseTitle("Cyberpunk S02E01.mkv"))
    }

    private fun record() = requireNotNull(NativeAnimeMappingCodec.parse(NativeAnimeParentFixtures.MAPPING, 61990))
    private fun enriched() = record().withVerifiedParentMetadata(NativeAnimeParentFixtures.PARENT).episodes.getValue(1)
}

/** Minimal public response excerpts, not account data. MAL61990 episode1, fetched 2026-10-26. */
internal object NativeAnimeParentFixtures {
    const val RELEASE = "[Feibanyama] Cyberpunk Edgerunners S02E01 [NF WebRip 1080p H265 Veryslow EAC3 Multi-Audio Multi-Subs]"
    const val MAPPING = """{"titles":{"en":"Cyberpunk: Edgerunners 2","x-jat":"Cyberpunk: Edgerunners II","ja":"CYBERPUNK: EDGERUNNERS II"},"mappings":{"mal_id":61990,"thetvdb_id":384541,"imdb_id":"tt12590266"},"episodes":{"1":{"tvdbShowId":384541,"tvdbId":11229710,"seasonNumber":2,"episodeNumber":1,"absoluteEpisodeNumber":11,"episode":"1","title":{"en":"Last Chance for a Slow Dance"}}}}"""
    const val VIDEO = """{"id":"tt12590266:2:1","season":2,"episode":1,"name":"Last Chance for a Slow Dance"}"""
    const val PARENT = """{"meta":{"id":"tt12590266","imdb_id":"tt12590266","type":"series","name":"Cyberpunk: Edgerunners","videos":[$VIDEO]}}"""
}
