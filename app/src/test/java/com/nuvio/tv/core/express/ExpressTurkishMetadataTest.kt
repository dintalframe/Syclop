package com.nuvio.tv.core.express

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.network.NetworkResult
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpressTurkishMetadataTest {
    @Test fun `a keyless Turkish movie searches the verified original and folded title before English`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.resolve()

        assertEquals("Kelebeğin Rüyası", result.originalTitle)
        assertEquals(listOf("Kelebeğin Rüyası", "Kelebegin Ruyasi", "The Butterfly's Dream"), result.queryTitles())
        assertEquals(result, fixture.resolve())
        assertEquals(1, fixture.wikiRequests.get())
    }

    @Test fun `another identity year or language cannot supply the original title`() = runBlocking {
        for (entity in listOf(
            entity(imdbId = "tt0476735"),
            entity(year = 2005),
            entity(languageId = "Q1860")
        )) {
            val fixture = Fixture(wikiEntity = entity)
            assertNull(fixture.resolve().originalTitle)
            assertEquals(1, fixture.wikiRequests.get())
        }
    }

    @Test fun `an unrelated movie does not call the Turkish title lookup`() = runBlocking {
        val fixture = Fixture(country = "United States")
        assertNull(fixture.resolve().originalTitle)
        assertEquals(0, fixture.wikiRequests.get())
    }

    @Test fun `verified catalog and broadcast numbers add a localized query without changing the provider coordinates`() = runBlocking {
        val fixture = EpisodeFixture()
        val media = fixture.resolve()

        assertEquals("tt1286677:2:1", media.turkishBroadcastEpisode?.videoId)
        assertEquals(39, media.turkishBroadcastEpisode?.number)
        assertEquals(listOf("Aşk-ı Memnu", "Ask-i Memnu", "Forbidden Love"), media.queryTitles())
        val query = fixture.queries(media)[1].toHttpUrl()
        assertEquals("Aşk-ı Memnu 2008 39. Bölüm", query.queryParameter("q"))
        assertEquals("2", query.queryParameter("s"))
        assertEquals("1", query.queryParameter("e"))
        assertEquals(39, fixture.resolve().turkishBroadcastEpisode?.number)
        assertEquals(1, fixture.episodeRequests.get())
    }

    @Test fun `a conflicting broadcast episode uses the localized season and episode label only`() = runBlocking {
        val fixture = EpisodeFixture(broadcastLabel = "40. Bölüm")
        val media = fixture.resolve()

        assertNull(media.turkishBroadcastEpisode)
        val query = fixture.queries(media)[1].toHttpUrl()
        assertEquals("Aşk-ı Memnu 2008 2. Sezon 1. Bölüm", query.queryParameter("q"))
    }

    @Test fun `a reset ordinal or different linked show cannot authorize a bare episode label`() = runBlocking {
        assertNull(EpisodeFixture(broadcastLabel = "1. Bölüm", catalogLabel = "Episode 1")
            .resolve().turkishBroadcastEpisode)
        assertNull(EpisodeFixture(linkedShow = 123).resolve().turkishBroadcastEpisode)
    }

    @Test fun `one ordinal cannot identify two catalog episodes`() {
        val catalog = JsonParser.parseString("""
            {"videos":[{"id":"tt1286677:1:39","season":1,"episode":39,"name":"Episode 39"},
                       {"id":"tt1286677:2:1","season":2,"episode":1,"name":"Episode 39"}]}
        """.trimIndent()).asJsonObject
        assertEquals(emptyMap<String, Int>(), catalog.catalogTurkishEpisodeNumbers("tt1286677"))
    }

    @Test fun `Express attaches proof to a matching broadcast row and preserves the ordinary route`() = runBlocking {
        val fixture = EpisodeFixture()
        val sources = fixture.search()
        assertEquals(2, sources.size)
        val localized = sources.single { it.infoHash == "1".repeat(40) }
        assertEquals(39, localized.nativeTurkishBroadcastEpisode?.number)
        assertNull(sources.single { it.infoHash == "2".repeat(40) }.nativeTurkishBroadcastEpisode)
    }

    @Test fun `a slow optional TVmaze lookup preserves the verified TMDB title`() = runBlocking {
        val fixture = EpisodeFixture(slowTvmaze = true)
        assertEquals("Aşk-ı Memnu", fixture.resolve().originalTitle)
    }

    private class EpisodeFixture(
        broadcastLabel: String = "39. Bölüm",
        catalogLabel: String = "Episode 39",
        linkedShow: Int = 18892,
        slowTvmaze: Boolean = false
    ) {
        val episodeRequests = AtomicInteger()
        private val http = client { request ->
            when (request.url.encodedPath) {
                "/meta/series/tt1286677.json" -> """
                    {"meta":{"id":"tt1286677","name":"Forbidden Love","country":"Turkey","releaseInfo":"2008–2010",
                    "videos":[{"id":"tt1286677:2:1","season":2,"episode":1,"name":"$catalogLabel"}]}}
                """.trimIndent()
                "/lookup/shows" -> {
                    if (slowTvmaze) Thread.sleep(3_000L)
                    """
                        {"id":18892,"name":"Aşk-ı Memnu","language":"Turkish","premiered":"2008-09-04",
                        "externals":{"imdb":"tt1286677"}}
                    """.trimIndent()
                }
                "/shows/18892/episodebynumber" -> {
                    episodeRequests.incrementAndGet()
                    """{"id":852746,"season":2,"number":1,"name":"$broadcastLabel",
                        "_links":{"show":{"href":"https://api.tvmaze.com/shows/$linkedShow"}}}"""
                }
                "/config" -> """
                    {"_manifest":{"name":"Turkish fixture"},"fixture":{"base_url":"https://example.org/provider",
                    "episode":{"query":"search?q={query}","keywords":"{title} {episodeCode}"},
                    "json_format":{"results":"streams","hash":"infoHash","title":"title"}}}
                """.trimIndent()
                "/provider/search" -> """
                    {"streams":[{"infoHash":"${"1".repeat(40)}","title":"Aşk-ı Memnu 39. Bölüm 1080p"},
                    {"infoHash":"${"2".repeat(40)}","title":"Aşk-ı Memnu S02E01 1080p"}]}
                """.trimIndent()
                else -> "{}"
            }
        }
        private val tmdb = if (slowTvmaze) mockk<TmdbService>().also {
            coEvery { it.findOriginalTitle("tt1286677", "series", 2008) } returns "Aşk-ı Memnu"
        } else TmdbService(mockk(), "")
        private val resolver = ExpressMetadataResolver(http, tmdb, mockk())
        private val adapter = ExpressAddonAdapter(http, resolver, System::currentTimeMillis)
        private val request = ExpressMediaRequest("series", "tt1286677:2:1")

        suspend fun resolve() = resolver.resolve(request)
        suspend fun search(): List<com.nuvio.tv.domain.model.Stream> {
            val addon = (adapter.discover("https://example.org/config") as NetworkResult.Success).data
            return (adapter.search(addon, request) as NetworkResult.Success).data
        }

        fun queries(media: ExpressMedia) = adapter.providerQueries(JsonParser.parseString("""
                {"base_url":"https://example.org","episode":{"query":"search?q={query}&s={season}&e={episode}",
                "keywords":"{title} {year} {episodeCode}"}}
            """.trimIndent()).asJsonObject, media)
    }

    private class Fixture(country: String = "Turkey", wikiEntity: JsonObject = entity()) {
        val wikiRequests = AtomicInteger()
        private val wiki = JsonObject().apply {
            add("query", JsonParser.parseString(
                """{"pages":[{"title":"Q12208610","revisions":[{"slots":{"main":{"contentmodel":"wikibase-item"}}}]}]}"""
            ))
            getAsJsonObject("query").getAsJsonArray("pages")[0].asJsonObject
                .getAsJsonArray("revisions")[0].asJsonObject.getAsJsonObject("slots")
                .getAsJsonObject("main").addProperty("content", wikiEntity.toString())
        }
        private val resolver = ExpressMetadataResolver(
            client { request ->
                when (request.url.host) {
                    "v3-cinemeta.strem.io" ->
                        """{"meta":{"id":"tt2608224","name":"The Butterfly's Dream","country":"$country","releaseInfo":"2013"}}"""
                    "www.wikidata.org" -> {
                        wikiRequests.incrementAndGet()
                        wiki.toString()
                    }
                    else -> "{}"
                }
            }, TmdbService(mockk(), ""), mockk()
        )

        suspend fun resolve() = resolver.resolve(ExpressMediaRequest("movie", "tt2608224"))
    }

    private companion object {
        fun client(response: (Request) -> String) = ExpressHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(response(request).toResponseBody("application/json".toMediaType())).build()
        }.build())

        fun entity(imdbId: String = "tt2608224", year: Int = 2013, languageId: String = "Q256") =
            JsonParser.parseString("""
                {"id":"Q12208610","claims":{
                  "P345":[{"rank":"normal","mainsnak":{"datavalue":{"value":"$imdbId"}}}],
                  "P31":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q11424"}}}}],
                  "P364":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"$languageId"}}}}],
                  "P577":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"time":"+$year-02-22T00:00:00Z","precision":11}}}}],
                  "P1476":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"text":"Kelebeğin Rüyası","language":"tr"}}}}]
                }}
            """.trimIndent()).asJsonObject
    }
}
