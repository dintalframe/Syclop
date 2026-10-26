package com.nuvio.tv.core.express

import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.core.anime.NativeAnimeMappingCodec
import com.nuvio.tv.data.mal.MalAnime
import com.nuvio.tv.data.mal.MalMetadataService
import com.nuvio.tv.data.mal.MalSourceMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NativeMalExpressMappingTest {
    @Test fun `verified release absolute never changes raw MAL progress identity`() = runTest {
        val metadata = MalMetadataService.toMeta(MalAnime(59978, "Sousou no Frieren 2nd Season", totalEpisodes = 10, mediaType = "tv"))
        val raw = metadata.videos.first()
        assertEquals("mal:59978:1", raw.id); assertEquals(1, raw.season); assertEquals(1, raw.episode)
        val (resolver, _) = resolver()
        val result = resolver.resolve(ExpressMediaRequest("series", raw.id, raw.season, raw.episode))
        assertEquals(1, result.episode); assertEquals(29, result.absoluteNumber)
        assertEquals(2, result.season); assertNull(result.imdbId)
        assertEquals(1, result.catalogSeason); assertEquals(1, result.catalogEpisode)
        assertEquals(2, result.tvdbSeason); assertEquals(1, result.tvdbEpisode)
        assertEquals("mal:59978:1", metadata.videos.first().id)
        assertEquals(1, metadata.videos.first().episode)
        assertEquals("1", result.variables()["episode"]); assertEquals("29", result.variables()["absoluteNumber"])
        assertEquals("s02e01", result.variables()["episodeCode"])
        assertEquals("1", result.variables()["catalogEpisode"])
        assertEquals("2", result.variables()["tvdbSeason"])
    }

    @Test fun `trusted Latin and Japanese title families yield bounded explicit absolute queries`() = runTest {
        val (resolver, _) = resolver()
        val media = resolver.resolve(ExpressMediaRequest("series", "mal:59978:1", 1, 1))
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        val provider = parseExpressObject("""{"base_url":"https://example.test/search","anime":{"query":"?q={query}","keywords":["{title} {absoluteNumber}","{title} {absoluteNumber} 1080p","{title} {absoluteNumber} 720p"]}}""")
        val queries = adapter.providerQueries(provider, media)
        assertTrue(queries.size in 3..6)
        assertEquals(listOf("Frieren: Beyond Journey’s End 29", "Sousou no Frieren 29", "葬送のフリーレン 第2期 29"),
            queries.take(3).map { it.toHttpUrl().queryParameter("q") })
        assertTrue(queries.none { it.contains("s01e01", ignoreCase = true) })
    }

    @Test fun `aliases reject blank controls overlong duplicates and remain bounded`() {
        val media = ExpressMedia("series", "Canonical", null, null, null, 1, 29, true,
            listOf(" ", "Canonical", "Bad\nAlias", "x".repeat(257), " First ", "Second", "Third"))
        assertEquals(listOf("Canonical", "First", "Second"), media.queryTitles())
    }

    @Test fun `missing mapping is a fixed failure rather than guessed relative absolute`() = runTest {
        val (resolver, mappings) = resolver(mapped = false)
        try { resolver.resolve(ExpressMediaRequest("series", "mal:59978:1", 1, 1)); fail("Expected fail closed") }
        catch (error: ExpressException) { assertEquals("A confirmed anime episode mapping is unavailable.", error.message) }
        coVerify(exactly = 1) { mappings.proof("mal:59978:1", any()) }
    }

    @Test fun `conflicting native MAL UI coordinates stop before public mapping lookup`() = runTest {
        val (resolver, mappings) = resolver()
        for (request in listOf(ExpressMediaRequest("series", "mal:59978:1", 2, 1),
            ExpressMediaRequest("series", "mal:59978:1", 1, 29))) {
            try { resolver.resolve(request); fail("Expected conflict") } catch (_: ExpressException) { }
        }
        coVerify(exactly = 0) { mappings.proof(any(), any()) }
    }

    @Test fun `TVDB episode mapping without absolute number can search only its available coordinates`() = runTest {
        val (resolver, _) = resolver(fixture = FIXTURE.replace("\"absoluteEpisodeNumber\":29,", ""))
        val media = resolver.resolve(ExpressMediaRequest("series", "mal:59978:1", 1, 1))
        assertTrue(media.hasMappedEpisodeCoordinates)
        assertEquals(1, media.catalogEpisode)
        assertEquals(2, media.tvdbSeason)
        assertEquals(1, media.tvdbEpisode)
        assertNull(media.absoluteNumber)
        assertNull(expressTemplate("{title} {absoluteNumber}", media.variables()))
        assertEquals("Sousou no Frieren 2nd Season s02e01", expressTemplate("{title} {episodeCode}", media.variables()))
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        val provider = parseExpressObject("""{"base_url":"https://example.test/search","anime":{"query":"?q={query}","keywords":"{title} {absoluteNumber}"},"episode":{"query":"?q={query}","keywords":"{title} {episodeCode}"}}""")
        val queries = adapter.providerQueries(provider, media)
        assertEquals(3, queries.size)
        assertTrue(queries.all { it.toHttpUrl().queryParameter("q")!!.endsWith("s02e01") })
        assertTrue(queries.none { it.toHttpUrl().queryParameter("q")!!.endsWith(" 1") })
    }

    @Test fun `mapped TVDB episode and MAL relative episode stay distinct for split cours`() = runTest {
        val (resolver, _) = resolver(fixture = FIXTURE.replace("\"episodeNumber\":1", "\"episodeNumber\":13"))
        val media = resolver.resolve(ExpressMediaRequest("series", "mal:59978:1", 1, 1))
        assertEquals(1, media.catalogEpisode)
        assertEquals(13, media.episode)
        assertEquals(29, media.absoluteNumber)
        assertEquals("s02e13", media.variables()["episodeCode"])
        assertEquals("1", media.variables()["catalogEpisode"])
        assertEquals("13", media.variables()["tvdbEpisode"])
        assertEquals("29", media.variables()["absoluteNumber"])
        assertTrue(media.nativeAnimeEpisode!!.matchesFile("Sousou no Frieren S02E13.mkv"))
        assertFalse(media.nativeAnimeEpisode!!.matchesFile("Sousou no Frieren S02E01.mkv"))
    }

    @Test fun `search and file proof share an immutable trusted alias snapshot`() = runTest {
        val (resolver, _) = resolver()
        val media = resolver.resolve(ExpressMediaRequest("series", "mal:59978:1", 1, 1))
        val proof = media.nativeAnimeEpisode!!
        assertSame(proof.titleIdentity, media.nativeAnimeIdentity)
        assertEquals(3, media.queryTitles().size)
        for (alias in media.queryTitles()) {
            assertTrue(alias, media.acceptsNativeAnimeRow(ExpressRow("$alias S02E01", null, null, HASH)))
            assertTrue(alias, proof.matchesFile("$alias S02E01.mkv"))
        }
        assertFalse(media.queryTitles().contains("Fourth")) // A separate metadata list cannot expand proof.
        assertFalse(proof.matchesFile("Fourth S02E01.mkv"))
        assertEquals(media.queryTitles(), media.copy(title = "Unrelated remake", titleAliases = mutableListOf("Other Show")).queryTitles())
        assertFalse(proof.matchesFile("Unrelated remake S02E01.mkv"))
    }

    @Test fun `proof for another catalog episode is rejected without coordinate guessing`() = runTest {
        val (resolver, _) = resolver(fixture = FIXTURE.replace("\"1\":{", "\"2\":{").replace("\"episode\":\"1\"", "\"episode\":\"2\""), proofEpisode = 2)
        try { resolver.resolve(ExpressMediaRequest("series", "mal:59978:1", 1, 1)); fail("Expected exact episode binding") }
        catch (error: ExpressException) { assertEquals("A confirmed anime episode mapping is unavailable.", error.message) }
    }

    private fun resolver(mapped: Boolean = true, fixture: String = FIXTURE, proofEpisode: Int = 1): Pair<ExpressMetadataResolver, NativeAnimeEpisodeMappings> {
        val mal = mockk<MalMetadataService>()
        coEvery { mal.sourceMetadata(59978) } returns MalSourceMetadata(59978, "Sousou no Frieren 2nd Season", 2026, "tv", 10,
            alternativeTitles = listOf("Sousou no Frieren S2", "Frieren: Beyond Journey’s End", "Fourth"))
        val mappings = mockk<NativeAnimeEpisodeMappings>()
        val proof = NativeAnimeMappingCodec.parse(fixture, 59978)!!.episodes.getValue(proofEpisode)
        coEvery { mappings.proof("mal:59978:1", any()) } returns if (mapped) proof else null
        return ExpressMetadataResolver(ExpressHttpClient(), mockk(), mal, mappings) to mappings
    }
    private companion object {
        const val HASH = "aabbccddeeff00112233445566778899aabbccdd"
        const val FIXTURE = """{"titles":{"romaji":"Sousou no Frieren 2nd Season","en":"Frieren: Beyond Journey’s End","ja":"葬送のフリーレン 第2期"},"mappings":{"mal_id":59978,"thetvdb_id":424536},"episodeCount":10,"episodes":{"1":{"tvdbShowId":424536,"tvdbId":10719109,"seasonNumber":2,"episodeNumber":1,"absoluteEpisodeNumber":29,"episode":"1"}}}"""
    }
}
