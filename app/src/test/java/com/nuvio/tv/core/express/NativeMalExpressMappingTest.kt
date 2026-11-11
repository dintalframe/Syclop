package com.nuvio.tv.core.express

import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.core.anime.NativeAnimeEpisodeProof
import com.nuvio.tv.core.anime.NativeAnimeMappingCodec
import com.nuvio.tv.core.anime.NativeAnimeTitleIdentity
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.mal.MalAnime
import com.nuvio.tv.data.mal.MalMetadataService
import com.nuvio.tv.data.mal.MalSourceMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeMalExpressMappingTest {
    @Test fun `corroborated Kitsu coordinates enable Express episode providers`() = runBlocking {
        val title = "Hunter x Hunter (2011)"
        val filename = "$title S02E10.mkv"
        val identity = requireNotNull(NativeAnimeTitleIdentity.create(11061, title, emptyList()))
        val proof = mockk<NativeAnimeEpisodeProof>()
        every { proof.isKitsuEpisode(6448, 68) } returns true
        every { proof.titleIdentity } returns identity
        every { proof.queryTitles() } returns listOf("Hunter x Hunter")
        every { proof.catalogOnly } returns false
        every { proof.catalogSeason } returns 1
        every { proof.malEpisode } returns 68
        every { proof.tvdbSeason } returns 2
        every { proof.tvdbEpisode } returns 10
        every { proof.absoluteEpisode } returns null
        every { proof.needsCatalogYear } returns true
        every { proof.usesCatalogOrdinalForSearch } returns true
        every { proof.acceptsReleaseTitle(any()) } answers { firstArg<String>() == filename }
        every { proof.cacheKey } returns "verified-kitsu-68"
        val mappings = mockk<NativeAnimeEpisodeMappings>()
        val request = ExpressMediaRequest("anime", "kitsu:6448:68", 2, 10)
        coEvery { mappings.proof(request.videoId, any()) } returns proof

        MockWebServer().use { server ->
            val config = """{"test":{"base_url":"${server.url("/backend")}",
                "episode":{"query":"/search?q={query}","keywords":"{title} {episodeCode}"},
                "anime":{"query":"/search?q={query}","keywords":"{title} {absoluteNumber}"},
                "json_format":{"results":"streams","title":"title","hash":"infoHash"}},
                "exact":{"base_url":"${server.url("/exact")}",
                "anime":{"query":"/episode/{absoluteNumber}?q={query}","keywords":"{title} {absoluteNumber}"},
                "json_format":{"results":"streams","title":"title","hash":"infoHash"}}}"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when {
                        request.path == "/config" -> config
                        request.requestUrl?.queryParameter("q")?.endsWith(" s02e10") == true ->
                            """{"streams":[{"title":"$filename","behaviorHints":{"filename":"$filename"},"infoHash":"$HASH"}]}"""
                        else -> """{"streams":[]}"""
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            val http = ExpressHttpClient(OkHttpClient(), true)
            val adapter = ExpressAddonAdapter(http, ExpressMetadataResolver(http, mockk(), mockk(), mappings), { 0L })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, request) }
            assertTrue("Expected a verified S/E source, got $result", result is NetworkResult.Success)
            val stream = (result as NetworkResult.Success).data.single()
            assertEquals(filename, stream.behaviorHints?.filename)
            assertEquals(proof.cacheKey, stream.nativeKitsuProofCacheKey)
            val queries = (1..server.requestCount).mapNotNull {
                requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).requestUrl?.queryParameter("q")
            }
            assertEquals(listOf("Hunter x Hunter 2011 s02e10", "Hunter x Hunter 2011 68"), queries)
        }
    }

    @Test fun `direct Kitsu episode searches the proof title year and ordinal without UI coordinates`() = runTest {
        val proof = requireNotNull(com.nuvio.tv.core.anime.NativeAnimeEpisodeProof.fromKitsu(
            11061, 6448, com.nuvio.tv.core.anime.KitsuAnimeEpisodes("Hunter x Hunter (2011)", 2011,
                mapOf(68 to com.nuvio.tv.core.anime.KitsuAnimeEpisode(106287, 1, 68)))
        )?.episodes?.get(68))
        val mal = mockk<MalMetadataService>()
        val mappings = mockk<NativeAnimeEpisodeMappings>()
        coEvery { mappings.proof("kitsu:6448:68", any()) } returns proof
        val media = ExpressMetadataResolver(ExpressHttpClient(), mockk(), mal, mappings)
            .resolve(ExpressMediaRequest("anime", "kitsu:6448:68", 2, 10))
        assertSame(proof, media.nativeAnimeEpisode)
        assertFalse(media.hasMappedEpisodeCoordinates)
        assertEquals("68", media.variables()["catalogEpisode"])
        assertEquals("2011", media.variables()["year"])
        for (field in listOf("season", "episode", "episodeCode", "absoluteNumber", "tvdbEpisode", "imdbId")) {
            assertNull(field, media.variables()[field])
        }
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        val provider = parseExpressObject("""{"base_url":"https://example.test/search","anime":{"query":"?q={query}","keywords":"{title} {absoluteNumber}"}}""")
        assertEquals(listOf("Hunter x Hunter 2011 68"),
            adapter.providerQueries(provider, media).map { it.toHttpUrl().queryParameter("q") })
        assertTrue(media.acceptsNativeAnimeRow(ExpressRow("Hunter x Hunter (2011) - 68", null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Hunter x Hunter (1999) - 68", null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Hunter x Hunter (2011) - 67", null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Hunter x Hunter (2011) - 68", null, null, HASH,
            filename = "Hunter x Hunter (2011) S01E68.mkv")))
        provider.getAsJsonObject("anime").addProperty("query", "by-episode/{absoluteNumber}?q={query}")
        assertTrue(adapter.providerQueries(provider, media).isEmpty())
        coVerify(exactly = 0) { mal.sourceMetadata(any()) }
        coVerify(exactly = 1) { mappings.proof("kitsu:6448:68", any()) }
    }

    @Test fun `Kitsu episode fallback uses title and scoped episode only in text searches`() = runTest {
        val proof = requireNotNull(com.nuvio.tv.core.anime.NativeAnimeEpisodeProof.fromKitsu(
            63367, 50497, com.nuvio.tv.core.anime.KitsuAnimeEpisodes("Dragon Ball Super: Beerus", 2026,
                mapOf(1 to com.nuvio.tv.core.anime.KitsuAnimeEpisode(393369, 1)))
        )?.episodes?.get(1))
        val mal = mockk<MalMetadataService>()
        coEvery { mal.sourceMetadata(63367) } returns MalSourceMetadata(63367,
            "Dragon Ball Super: Beerus", 2026, "tv", 19)
        val mappings = mockk<NativeAnimeEpisodeMappings>()
        coEvery { mappings.proof("mal:63367:1", any()) } returns proof
        val media = ExpressMetadataResolver(ExpressHttpClient(), mockk(), mal, mappings)
            .resolve(ExpressMediaRequest("series", "mal:63367:1", 1, 1))
        assertFalse(media.hasMappedEpisodeCoordinates)
        assertEquals("1", media.variables()["catalogEpisode"])
        for (field in listOf("absoluteNumber", "episodeCode", "tvdbEpisode", "imdbId"))
            assertNull(field, media.variables()[field])
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        val provider = parseExpressObject("""{"base_url":"https://example.test/search","anime":{"query":"?q={query}","keywords":"{title} {absoluteNumber}"}}""")
        assertEquals(listOf("Dragon Ball Super: Beerus 2026 1"),
            adapter.providerQueries(provider, media).map { it.toHttpUrl().queryParameter("q") })
        provider.getAsJsonObject("anime").addProperty("query", "by-episode/{absoluteNumber}?q={query}")
        assertTrue(adapter.providerQueries(provider, media).isEmpty())
    }

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
