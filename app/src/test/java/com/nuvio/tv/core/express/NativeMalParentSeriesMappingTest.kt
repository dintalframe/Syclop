package com.nuvio.tv.core.express

import com.google.gson.JsonObject
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.SourceSearchReason
import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.core.anime.NativeAnimeMappingCodec
import com.nuvio.tv.core.anime.NativeAnimeParentFixtures
import com.nuvio.tv.data.mal.MalMetadataService
import com.nuvio.tv.data.mal.MalSourceMetadata
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NativeMalParentSeriesMappingTest {
    @Test fun `exact verified parent unlocks IMDb queries while preserving native MAL episode identity`() = runTest {
        val media = media(verifiedParent = true)
        assertEquals("tt12590266", media.imdbId)
        assertEquals(1, media.catalogSeason)
        assertEquals(1, media.catalogEpisode)
        assertEquals(2, media.tvdbSeason)
        assertEquals(1, media.tvdbEpisode)
        assertEquals(11, media.absoluteNumber)
        assertEquals(61990L, media.nativeAnimeEpisode!!.malId)
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        val byId = parseExpressObject("""{"base_url":"https://example.test/search","episode":{"query":"?id={showImdbId}&s={season}&e={episode}","keywords":"{title} {episodeCode}"}}""")
        val idQuery = adapter.providerQueries(byId, media).single().toHttpUrl()
        assertEquals("tt12590266", idQuery.queryParameter("id"))
        assertEquals("2", idQuery.queryParameter("s"))
        assertEquals("1", idQuery.queryParameter("e"))
    }

    @Test fun `parent title search and returned rows use the same exact episode proof`() = runTest {
        val media = media(verifiedParent = true)
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        val provider = parseExpressObject("""{"base_url":"https://example.test/search","anime":{"query":"?q={query}","keywords":"{title} {absoluteNumber}"},"episode":{"query":"?q={query}","keywords":"{title} {episodeCode}"}}""")
        val queries = adapter.providerQueries(provider, media).map { it.toHttpUrl().queryParameter("q") }
        assertTrue(queries.size <= 6)
        assertEquals("Cyberpunk: Edgerunners 2 s02e01", queries.first())
        assertTrue("Cyberpunk: Edgerunners s02e01" in queries)
        assertTrue("Cyberpunk: Edgerunners 11" in queries)
        assertTrue(media.acceptsNativeAnimeRow(ExpressRow(NativeAnimeParentFixtures.RELEASE, null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Cyberpunk Edgerunners S01E01", null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Cyberpunk Edgerunners - 01", null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Cyberpunk Edgerunners 3 S02E01", null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Cyberpunk Edgerunners", null, null, HASH)))
    }

    @Test fun `missing parent verification keeps IMDb unavailable and cannot admit a generic parent row`() = runTest {
        val media = media(verifiedParent = false)
        assertNull(media.imdbId)
        assertFalse("Cyberpunk: Edgerunners" in media.queryTitles())
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow(NativeAnimeParentFixtures.RELEASE, null, null, HASH)))
        val provider = parseExpressObject("""{"base_url":"https://example.test/search","episode":{"query":"?id={showImdbId}","keywords":"{title} {episodeCode}"}}""")
        val adapter = ExpressAddonAdapter(ExpressHttpClient(), ExpressMetadataSource { media }, { 0L })
        assertTrue(adapter.providerQueries(provider, media).isEmpty())
    }

    @Test fun `shared origin cap cannot crowd verified episode query out of the first provider round`() = runBlocking {
        val selected = media(verifiedParent = true)
        MockWebServer().use { server ->
            val config = JsonObject().apply {
                add("_manifest", JsonObject().apply { addProperty("name", "Shared origin sources") })
                repeat(33) { index -> add("provider$index", JsonObject().apply {
                    addProperty("name", "Provider$index")
                    addProperty("base_url", server.url("/backend$index").toString())
                    add("anime", parseExpressObject("""{"query":"/search?q={query}","keywords":"{title} {absoluteNumber}"}"""))
                    add("episode", parseExpressObject("""{"query":"/search?q={query}","keywords":"{title} {episodeCode}"}"""))
                    add("json_format", parseExpressObject("""{"results":"streams","title":"title","hash":"infoHash"}"""))
                }) }
            }.toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when {
                        request.path == "/config" -> config
                        request.requestUrl!!.encodedPath == "/backend16/search" &&
                            request.requestUrl!!.queryParameter("q")!!.endsWith("s02e01") ->
                            """{"streams":[{"title":"Cyberpunk Edgerunners S02E01 1080p","infoHash":"$HASH"}]}"""
                        else -> """{"streams":[]}"""
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            val adapter = ExpressAddonAdapter(ExpressHttpClient(OkHttpClient(), true),
                ExpressMetadataSource { selected }, { 0L })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = withTimeout(10_000) {
                adapter.searchProgressively(addon, ExpressMediaRequest("series", "mal:61990:1", 1, 1)).toList().last()
            }
            assertEquals("Cyberpunk Edgerunners S02E01 1080p", last.streams.single().title)
            assertEquals(31, server.requestCount) // One configuration and the unchanged30-request origin cap.
            assertTrue(last.report.reasons.getValue(SourceSearchReason.REQUEST_BUDGET) > 0)
            assertEquals(1, selected.catalogEpisode)
            assertEquals(2, selected.tvdbSeason)
        }
    }

    private suspend fun media(verifiedParent: Boolean): ExpressMedia {
        val mal = mockk<MalMetadataService>()
        coEvery { mal.sourceMetadata(61990) } returns MalSourceMetadata(61990,
            "Cyberpunk: Edgerunners 2", 2026, "ona", 10, imdbId = "tt99999999")
        val record = requireNotNull(NativeAnimeMappingCodec.parse(NativeAnimeParentFixtures.MAPPING, 61990))
        val proof = (if (verifiedParent) record.withVerifiedParentMetadata(NativeAnimeParentFixtures.PARENT) else record)
            .episodes.getValue(1)
        val mappings = mockk<NativeAnimeEpisodeMappings>()
        coEvery { mappings.proof("mal:61990:1", any()) } returns proof
        return ExpressMetadataResolver(ExpressHttpClient(), mockk(), mal, mappings)
            .resolve(ExpressMediaRequest("series", "mal:61990:1", 1, 1))
    }

    private companion object { const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
}
