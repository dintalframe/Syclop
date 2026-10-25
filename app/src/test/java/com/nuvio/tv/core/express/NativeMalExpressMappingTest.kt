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
        assertNull(result.season); assertNull(result.imdbId)
        assertEquals("mal:59978:1", metadata.videos.first().id)
        assertEquals(1, metadata.videos.first().episode)
        assertEquals("1", result.variables()["episode"]); assertEquals("29", result.variables()["absoluteNumber"])
        assertFalse(result.variables().containsKey("episodeCode"))
    }

    @Test fun `canonical plus MAL aliases yield at most three explicit absolute queries`() = runTest {
        val (resolver, _) = resolver()
        val media = resolver.resolve(ExpressMediaRequest("series", "mal:59978:1", 1, 1))
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        val provider = parseExpressObject("""{"base_url":"https://example.test/search","anime":{"query":"?q={query}","keywords":["{title} {absoluteNumber}","{title} {absoluteNumber} 1080p","{title} {absoluteNumber} 720p"]}}""")
        val queries = adapter.providerQueries(provider, media)
        assertEquals(3, queries.size)
        assertEquals(listOf("Sousou no Frieren 2nd Season 29", "Sousou no Frieren 29", "Sousou no Frieren S2 29"),
            queries.map { it.toHttpUrl().queryParameter("q") })
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

    private fun resolver(mapped: Boolean = true): Pair<ExpressMetadataResolver, NativeAnimeEpisodeMappings> {
        val mal = mockk<MalMetadataService>()
        coEvery { mal.sourceMetadata(59978) } returns MalSourceMetadata(59978, "Sousou no Frieren 2nd Season", 2026, "tv", 10,
            alternativeTitles = listOf("Sousou no Frieren S2", "Frieren: Beyond Journey’s End", "Fourth"))
        val mappings = mockk<NativeAnimeEpisodeMappings>()
        val proof = NativeAnimeMappingCodec.parse(FIXTURE, 59978)!!.episodes.getValue(1)
        coEvery { mappings.proof("mal:59978:1", any()) } returns if (mapped) proof else null
        return ExpressMetadataResolver(ExpressHttpClient(), mockk(), mal, mappings) to mappings
    }
    private companion object {
        const val FIXTURE = """{"titles":{"en":"Frieren"},"mappings":{"mal_id":59978,"thetvdb_id":424536},"episodeCount":10,"episodes":{"1":{"tvdbShowId":424536,"tvdbId":10719109,"seasonNumber":2,"episodeNumber":1,"absoluteEpisodeNumber":29,"episode":"1"}}}"""
    }
}
