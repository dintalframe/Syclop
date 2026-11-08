package com.nuvio.tv.core.express

import com.google.gson.JsonParser
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbFindResponse
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ExpressInternationalTitleTest {
    @Test
    fun `verified Turkish original supplies three exact episode query variants and is cached with Cinemeta`() = runTest {
        Fixture().use { fixture ->
            val first = fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1", 2, 1))
            assertEquals(listOf("Forbidden Love", "Aşk-ı Memnu", "Ask-i Memnu"), first.queryTitles())
            assertEquals(listOf(
                "https://example.org/search?q=Forbidden%20Love%202008%20s02e01&imdb=tt1286677&season=2&episode=1",
                "https://example.org/search?q=A%C5%9Fk-%C4%B1%20Memnu%202008%20s02e01&imdb=tt1286677&season=2&episode=1",
                "https://example.org/search?q=Ask-i%20Memnu%202008%20s02e01&imdb=tt1286677&season=2&episode=1"
            ), fixture.adapter().providerQueries(episodeProvider, first))
            val second = fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:2", 2, 2))
            assertEquals(first.queryTitles(), second.queryTitles())
            assertEquals(2, second.episode)
            assertEquals("tt1286677", second.imdbId)
            assertEquals(2008, second.year)
            assertNull(second.absoluteNumber)
            assertEquals(1, fixture.findRequests.get())
            assertEquals(1, fixture.cinemetaRequests.get())
        }
    }

    @Test
    fun `ASCII duplicates and IMDb-only provider queries do not fan out`() = runTest {
        Fixture(original = "Forbidden Love").use { fixture ->
            val media = fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1"))
            assertEquals(listOf("Forbidden Love"), media.queryTitles())
            val provider = JsonParser.parseString("""{"base_url":"https://example.org","episode":{"query":"search?q={query}","keywords":"{imdbId} {episodeCode}"}}""").asJsonObject
            assertEquals(listOf("https://example.org/search?q=tt1286677%20s02e01"), fixture.adapter().providerQueries(provider, media))
        }
    }

    @Test
    fun `movie original is Unicode normalized without deleting non Latin letters`() = runTest {
        Fixture(type = "movie", original = "千と千尋の神隠し", canonical = "Spirited Away", year = 2001).use { fixture ->
            val media = fixture.resolver.resolve(ExpressMediaRequest("movie", "tt1286677"))
            assertEquals(listOf("Spirited Away", "千と千尋の神隠し"), media.queryTitles())
            assertEquals("movie", media.type)
            assertNull(media.season)
            assertNull(media.episode)
        }
        Fixture(original = "As\u0327k-ı Memnu").use { fixture ->
            val media = fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:1:1"))
            assertEquals(listOf("Forbidden Love", "Aşk-ı Memnu", "Ask-i Memnu"), media.queryTitles())
        }
    }

    @Test
    fun `ambiguous wrong type conflicting premiere and invalid original metadata keep canonical identity`() = runTest {
        val rows = listOf(
            """{"id":1,"media_type":"tv","original_name":"Aşk-ı Memnu","first_air_date":"1975-09-01"}""",
            """{"id":1,"media_type":"movie","original_name":"Aşk-ı Memnu","first_air_date":"2008-09-04"}""",
            """{"id":0,"media_type":"tv","original_name":"Aşk-ı Memnu","first_air_date":"2008-09-04"}""",
            """{"id":1,"original_name":"   "}""",
            """{"id":1,"original_name":"Bad\u0000Title"}""",
            """{"id":1,"original_name":"${"a".repeat(257)}"}""",
            """{"id":1,"original_name":"Aşk-ı Memnu"},{"id":2,"original_name":"Unrelated show"}"""
        )
        for (row in rows) Fixture(findJson = """{"tv_results":[$row]}""").use { fixture ->
            val media = fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1"))
            assertEquals(row, listOf("Forbidden Love"), media.queryTitles())
            assertEquals(2008, media.year)
            assertEquals(2, media.season)
            assertEquals(1, media.episode)
        }
        Fixture(findJson = """{"movie_results":[{"id":1,"original_title":"Wrong movie"}],"tv_results":[]}""").use { fixture ->
            assertEquals(listOf("Forbidden Love"), fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1")).queryTitles())
        }
    }

    @Test
    fun `optional metadata failure and timeout retain canonical searches`() = runTest {
        Fixture().use { fixture ->
            coEvery { fixture.api.findByExternalId(any(), any(), any()) } throws IllegalStateException("Unavailable")
            assertEquals(listOf("Forbidden Love"), fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1")).queryTitles())
        }
        Fixture().use { fixture ->
            coEvery { fixture.api.findByExternalId(any(), any(), any()) } coAnswers {
                delay(60_000)
                Response.success(fixture.findResponse)
            }
            val started = System.nanoTime()
            assertEquals(listOf("Forbidden Love"), fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1")).queryTitles())
            assertTrue("Optional lookup must have its own short timeout", (System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun `caller cancellation during original title lookup propagates`() = runTest {
        Fixture().use { fixture ->
            coEvery { fixture.api.findByExternalId(any(), any(), any()) } throws CancellationException("Viewer changed")
            try {
                fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1"))
                fail("Caller cancellation must escape optional enrichment")
            } catch (_: CancellationException) { }
        }
    }

    @Test
    fun `international search rejects a clear earlier remake in either row field but accepts later season years`() = runTest {
        Fixture().use { fixture ->
            fixture.rows = """{"streams":[
              {"infoHash":"${hash('1')}","title":"Ask-i Memnu 1975 S02E01","behaviorHints":{"filename":"Ask-i.Memnu.1975.S02E01.mkv"}},
              {"infoHash":"${hash('2')}","title":"Aşk-ı Memnu 2008 S02E01","behaviorHints":{"filename":"Ask-i.Memnu.1975.S02E01.mkv"}},
              {"infoHash":"${hash('3')}","title":"Aşk-ı Memnu 2009 S02E01","behaviorHints":{"filename":"Ask-i.Memnu.2009.S02E01.mkv"}},
              {"infoHash":"${hash('4')}","title":"Ask-i Memnu S02E01","behaviorHints":{"filename":"Ask-i.Memnu.S02E01.2024.Remaster.mkv"}}
            ]}"""
            val adapter = fixture.adapter()
            val addon = (adapter.discover(fixture.server.url("/config").toString()) as NetworkResult.Success).data
            val search = withContext(Dispatchers.IO) { adapter.search(addon, ExpressMediaRequest("series", "tt1286677:2:1")) }
            assertTrue("Search failed: $search", search is NetworkResult.Success)
            val result = search as NetworkResult.Success
            assertEquals(setOf(hash('3'), hash('4')), result.data.mapNotNull { it.infoHash }.toSet())
        }
    }

    @Test
    fun `clear bracketed and adjacent parenthesized remake years in either field cannot bypass the premiere guard`() = runTest {
        Fixture().use { fixture ->
            fixture.rows = """{"streams":[
              {"infoHash":"${hash('1')}","title":"Ask-i Memnu [1975] S02E01"},
              {"infoHash":"${hash('2')}","title":"Ask-i Memnu(1975).S02E01"},
              {"infoHash":"${hash('3')}","title":"Ask-i Memnu 2008 S02E01","behaviorHints":{"filename":"Ask-i.Memnu.[1975].S02E01.mkv"}},
              {"infoHash":"${hash('4')}","title":"Ask-i Memnu 2008 S02E01","behaviorHints":{"filename":"Ask-i.Memnu(1975).S02E01.mkv"}},
              {"infoHash":"${hash('5')}","title":"Ask-i Memnu [2009] S02E01","behaviorHints":{"filename":"Ask-i.Memnu(2009).S02E01.mkv"}},
              {"infoHash":"${hash('6')}","title":"Ask-i Memnu S02E01","behaviorHints":{"filename":"Ask-i.Memnu.S02E01.[1975].Remaster.mkv"}},
              {"infoHash":"${hash('7')}","title":"Ask-i Memnu S02E01","behaviorHints":{"filename":"Ask-i.Memnu.S02E01.mkv"}}
            ]}"""
            val adapter = fixture.adapter()
            val addon = (adapter.discover(fixture.server.url("/config").toString()) as NetworkResult.Success).data
            val search = withContext(Dispatchers.IO) { adapter.search(addon, ExpressMediaRequest("series", "tt1286677:2:1")) }
            assertTrue("Search failed: $search", search is NetworkResult.Success)
            val result = search as NetworkResult.Success
            assertEquals(setOf(hash('5'), hash('6'), hash('7')), result.data.mapNotNull { it.infoHash }.toSet())
        }
    }

    @Test
    fun `folded search rejects an earlier remake when canonical and original are the same Turkish title`() = runTest {
        Fixture(canonical = "Aşk-ı Memnu", original = "Aşk-ı Memnu").use { fixture ->
            assertFoldedOnlyAdmission(fixture, expectedOriginal = "Aşk-ı Memnu")
        }
    }

    @Test
    fun `folded canonical search rejects an earlier remake when optional enrichment is unavailable`() = runTest {
        Fixture(canonical = "Aşk-ı Memnu").use { fixture ->
            coEvery { fixture.api.findByExternalId(any(), any(), any()) } throws IllegalStateException("Unavailable")
            assertFoldedOnlyAdmission(fixture, expectedOriginal = null)
        }
    }

    @Test
    fun `unchanged ASCII query retains existing admission without an international year guard`() = runTest {
        Fixture(canonical = "Forbidden Love", original = "Forbidden Love").use { fixture ->
            fixture.rows = """{"streams":[{"infoHash":"${hash('1')}","title":"Forbidden Love [1975] S02E01"}]}"""
            val adapter = fixture.adapter()
            val addon = (adapter.discover(fixture.server.url("/config").toString()) as NetworkResult.Success).data
            val result = withContext(Dispatchers.IO) {
                adapter.search(addon, ExpressMediaRequest("series", "tt1286677:2:1"))
            }
            assertTrue("Search failed: $result", result is NetworkResult.Success)
            assertEquals(setOf(hash('1')), (result as NetworkResult.Success).data.mapNotNull { it.infoHash }.toSet())
        }
    }

    private suspend fun assertFoldedOnlyAdmission(fixture: Fixture, expectedOriginal: String?) {
        val media = fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1"))
        assertEquals(expectedOriginal, media.originalTitle)
        assertEquals(listOf("Aşk-ı Memnu", "Ask-i Memnu"), media.queryTitles())
        assertEquals(listOf(
            "https://example.org/search?q=A%C5%9Fk-%C4%B1%20Memnu%202008%20s02e01&imdb=tt1286677&season=2&episode=1",
            "https://example.org/search?q=Ask-i%20Memnu%202008%20s02e01&imdb=tt1286677&season=2&episode=1"
        ), fixture.adapter().providerQueries(episodeProvider, media))
        fixture.rows = """{"streams":[
          {"infoHash":"${hash('1')}","title":"Ask-i Memnu [1975] S02E01","behaviorHints":{"filename":"Ask-i.Memnu.2008.S02E01.mkv"}},
          {"infoHash":"${hash('2')}","title":"Aşk-ı Memnu 2008 S02E01","behaviorHints":{"filename":"Ask-i.Memnu.[1975].S02E01.mkv"}},
          {"infoHash":"${hash('3')}","title":"Ask-i Memnu [2009] S02E01","behaviorHints":{"filename":"Ask-i.Memnu.2009.S02E01.mkv"}},
          {"infoHash":"${hash('4')}","title":"Ask-i Memnu S02E01","behaviorHints":{"filename":"Ask-i.Memnu.S02E01.[1975].Remaster.mkv"}},
          {"infoHash":"${hash('5')}","title":"Ask-i Memnu S02E01","behaviorHints":{"filename":"Ask-i.Memnu.S02E01.mkv"}}
        ]}"""
        val adapter = fixture.adapter()
        val addon = (adapter.discover(fixture.server.url("/config").toString()) as NetworkResult.Success).data
        val result = withContext(Dispatchers.IO) {
            adapter.search(addon, ExpressMediaRequest("series", "tt1286677:2:1"))
        }
        assertTrue("Search failed: $result", result is NetworkResult.Success)
        assertEquals(setOf(hash('3'), hash('4'), hash('5')), (result as NetworkResult.Success).data.mapNotNull { it.infoHash }.toSet())
    }

    @Test
    fun `movie international search rejects conflicting prefix year while retaining one year drift`() = runTest {
        Fixture(type = "movie", canonical = "The Secret in Their Eyes", original = "El secreto de sus ojos", year = 2009).use { fixture ->
            fixture.rows = """{"streams":[
              {"infoHash":"${hash('1')}","title":"El secreto de sus ojos 2015 1080p"},
              {"infoHash":"${hash('2')}","title":"El secreto de sus ojos 2010 1080p"},
              {"infoHash":"${hash('3')}","title":"El secreto de sus ojos 1080p"}
            ]}"""
            val adapter = fixture.adapter()
            val addon = (adapter.discover(fixture.server.url("/config").toString()) as NetworkResult.Success).data
            val search = withContext(Dispatchers.IO) { adapter.search(addon, ExpressMediaRequest("movie", "tt1286677")) }
            assertTrue("Search failed: $search", search is NetworkResult.Success)
            val result = search as NetworkResult.Success
            assertEquals(setOf(hash('2'), hash('3')), result.data.mapNotNull { it.infoHash }.toSet())
        }
    }

    private class Fixture(
        type: String = "series",
        original: String = "Aşk-ı Memnu",
        canonical: String = "Forbidden Love",
        year: Int = 2008,
        findJson: String = if (type == "movie")
            """{"movie_results":[{"id":42,"media_type":"movie","original_title":"$original","release_date":"$year-09-04"}]}"""
        else """{"tv_results":[{"id":42,"media_type":"tv","original_name":"$original","first_air_date":"$year-09-04"}]}"""
    ) : AutoCloseable {
        val server = MockWebServer()
        val api = mockk<TmdbApi>()
        val findResponse = Moshi.Builder().build().adapter(TmdbFindResponse::class.java).fromJson(findJson)!!
        val findRequests = AtomicInteger()
        val cinemetaRequests = AtomicInteger()
        var rows = """{"streams":[]}"""
        private val http: ExpressHttpClient
        val resolver: ExpressMetadataResolver

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/meta/") == true -> {
                        cinemetaRequests.incrementAndGet()
                        json("""{"meta":{"id":"tt1286677","name":"$canonical","releaseInfo":"$year–"}}""")
                    }
                    request.path == "/config" -> json("""{"_manifest":{"name":"International fixture"},"fixture":{"base_url":"${server.url("/provider")}","$typeScope":{"query":"search?q={query}","keywords":"{title}"},"json_format":{"results":"streams","hash":"infoHash","title":"title"}}}""")
                    else -> json(rows)
                }
                private val typeScope = if (type == "movie") "movie" else "episode"
            }
            server.start()
            coEvery { api.findByExternalId("tt1286677", any(), "imdb_id") } answers {
                findRequests.incrementAndGet()
                Response.success(findResponse)
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val target = if (request.url.host in setOf("v3-cinemeta.strem.io", "api.tvmaze.com"))
                    server.url(request.url.encodedPath) else request.url
                chain.proceed(request.newBuilder().url(target).build())
            }.build()
            http = ExpressHttpClient(client, true)
            resolver = ExpressMetadataResolver(http, TmdbService(api, "fixture-key"), mockk())
        }

        fun adapter() = ExpressAddonAdapter(http, resolver, System::currentTimeMillis)
        override fun close() = server.close()
    }

    private companion object {
        val episodeProvider = JsonParser.parseString("""{"base_url":"https://example.org","episode":{"query":"search?q={query}&imdb={imdbId}&season={season}&episode={episode}","keywords":"{title} {year} {episodeCode}"}}""").asJsonObject
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        fun hash(character: Char) = character.toString().repeat(40)
    }
}
