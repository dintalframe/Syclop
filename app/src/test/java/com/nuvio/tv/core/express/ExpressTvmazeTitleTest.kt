package com.nuvio.tv.core.express

import com.google.gson.JsonParser
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import io.mockk.coEvery
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ExpressTvmazeTitleTest {
    @Test
    fun `blank TMDB configuration skips its original title HTTP request`() = runTest {
        val requests = AtomicInteger()
        val api = mockk<TmdbApi>()
        coEvery { api.findByExternalId(any(), any(), any()) } answers {
            requests.incrementAndGet()
            throw IllegalStateException("Unconfigured metadata request")
        }
        assertNull(TmdbService(api, "").findOriginalTitle("tt1286677", "series", 2008))
        assertEquals(0, requests.get())
    }

    @Test
    fun `keyless IMDb lookup follows same origin redirect and caches exact Turkish queries`() = runTest {
        Fixture().use { fixture ->
            val media = fixture.resolve()
            assertEquals(listOf("Forbidden Love", "Aşk-ı Memnu", "Ask-i Memnu"), media.queryTitles())
            val provider = JsonParser.parseString("""{"base_url":"https://provider.example","episode":{"query":"search?q={query}&id={imdbId}&s={season}&e={episode}","keywords":"{title} {year} {episodeCode}"}}""").asJsonObject
            val adapter = ExpressAddonAdapter(fixture.http, fixture.resolver, System::currentTimeMillis)
            assertEquals(listOf(
                "https://provider.example/search?q=Forbidden%20Love%202008%20s02e01&id=tt1286677&s=2&e=1",
                "https://provider.example/search?q=A%C5%9Fk-%C4%B1%20Memnu%202008%20s02e01&id=tt1286677&s=2&e=1",
                "https://provider.example/search?q=Ask-i%20Memnu%202008%20s02e01&id=tt1286677&s=2&e=1"
            ), adapter.providerQueries(provider, media))
            val next = fixture.resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:2"))
            assertEquals(media.queryTitles(), next.queryTitles())
            assertEquals(2, next.episode)
            assertNull(next.absoluteNumber)
            assertEquals(listOf(
                "https://v3-cinemeta.strem.io/meta/series/tt1286677.json",
                "https://api.tvmaze.com/lookup/shows?imdb=tt1286677",
                "https://api.tvmaze.com/shows/18892"
            ), fixture.requests.toList())
        }
    }

    @Test
    fun `wrong IMDb premiere missing year invalid name and invalid positive ID never create aliases`() = runTest {
        val invalid = listOf(
            """{"id":18892,"name":"Aşk-ı Memnu","premiered":"2008-09-04","externals":{"imdb":"tt999"}}""",
            """{"id":18892,"name":"Aşk-ı Memnu","premiered":"1975-09-04","externals":{"imdb":"tt1286677"}}""",
            """{"id":18892,"name":"Aşk-ı Memnu","premiered":null,"externals":{"imdb":"tt1286677"}}""",
            """{"id":0,"name":"Aşk-ı Memnu","premiered":"2008-09-04","externals":{"imdb":"tt1286677"}}""",
            """{"id":42,"name":"Aşk-ı Memnu","premiered":"2008-09-04","externals":{"imdb":"tt1286677"}}""",
            """{"id":18892,"name":"Bad\u0000Name","premiered":"2008-09-04","externals":{"imdb":"tt1286677"}}""",
            """{"id":18892,"name":"${"a".repeat(257)}","premiered":"2008-09-04","externals":{"imdb":"tt1286677"}}""",
            """{"id":18892,"name":" ","premiered":"2008-09-04","externals":{"imdb":"tt1286677"}}"""
        )
        for (body in invalid) Fixture().use { fixture ->
            fixture.showBody = body
            assertEquals(body, listOf("Forbidden Love"), fixture.resolve().queryTitles())
        }
    }

    @Test
    fun `TVmaze never fills movie titles or a series with unknown catalog premiere year`() = runTest {
        Fixture().use { fixture ->
            val movie = fixture.resolver.resolve(ExpressMediaRequest("movie", "tt1286677"))
            assertEquals(listOf("Forbidden Love"), movie.queryTitles())
            assertFalse(fixture.requests.any { it.contains("tvmaze") })
        }
        Fixture().use { fixture ->
            fixture.catalogYear = null
            assertEquals(listOf("Forbidden Love"), fixture.resolve().queryTitles())
            assertFalse(fixture.requests.any { it.contains("tvmaze") })
        }
    }

    @Test
    fun `external origin downgrade port and unrelated redirects are rejected before destination fetch`() = runTest {
        for (destination in listOf("https://other.example/shows/18892", "http://api.tvmaze.com/shows/18892",
            "https://api.tvmaze.com:444/shows/18892", "https://api.tvmaze.com/search/shows?q=other",
            "https://api.tvmaze.com/lookup/shows?imdb=tt999")) Fixture().use { fixture ->
            fixture.redirect = destination
            assertEquals(destination, listOf("Forbidden Love"), fixture.resolve().queryTitles())
            assertEquals(destination, 2, fixture.requests.size)
        }
    }

    @Test
    fun `ordinary TVmaze errors and oversized bodies fall back to the canonical title`() = runTest {
        Fixture().use { fixture ->
            fixture.status = 503
            assertEquals(listOf("Forbidden Love"), fixture.resolve().queryTitles())
        }
        Fixture().use { fixture ->
            fixture.showBody = " ".repeat(512 * 1024 + 1)
            assertEquals(listOf("Forbidden Love"), fixture.resolve().queryTitles())
        }
    }

    @Test
    fun `total optional metadata budget includes the preceding TMDB attempt`() = runTest {
        Fixture().use { fixture ->
            coEvery { fixture.tmdb.findOriginalTitle(any(), any(), any()) } coAnswers {
                delay(1_300)
                null
            }
            fixture.noResponse = true
            val started = System.nanoTime()
            assertEquals(listOf("Forbidden Love"), fixture.resolve().queryTitles())
            assertTrue("Total optional metadata time exceeded 2500ms budget plus fixture overhead",
                (System.nanoTime() - started) / 1_000_000 < 3_500)
        }
    }

    @Test
    fun `cancellation while waiting for TVmaze propagates and cancels its request`() = runTest {
        Fixture().use { fixture ->
            fixture.noResponse = true
            val search = async(Dispatchers.IO) { fixture.resolve() }
            withContext(Dispatchers.IO) {
                assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                assertNotNull("Expected TVmaze lookup", fixture.server.takeRequest(2, TimeUnit.SECONDS))
            }
            search.cancelAndJoin()
            try {
                search.await()
                fail("Caller cancellation must propagate")
            } catch (_: CancellationException) { }
        }
    }

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val tmdb = mockk<TmdbService>()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var redirect = "https://api.tvmaze.com/shows/18892"
        var showBody = """{"id":18892,"name":"Aşk-ı Memnu","premiered":"2008-09-04","externals":{"imdb":"tt1286677"}}"""
        var catalogYear: Int? = 2008
        var status = 200
        var noResponse = false
        val http: ExpressHttpClient
        val resolver: ExpressMetadataResolver

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/meta/") == true -> json("""{"meta":{"id":"tt1286677","name":"Forbidden Love","releaseInfo":${catalogYear?.let { "\"$it–\"" } ?: "null"}}}""")
                    noResponse -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    request.path?.startsWith("/lookup/") == true -> MockResponse().setResponseCode(301).setHeader("Location", redirect)
                    else -> json(showBody).setResponseCode(status)
                }
            }
            server.start()
            coEvery { tmdb.findOriginalTitle("tt1286677", any(), any()) } returns null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request.url.toString())
                // Every URL, even an incorrectly admitted redirect, stays inside this fixture.
                val target = server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))
                chain.proceed(request.newBuilder().url(target).build())
            }.build()
            http = ExpressHttpClient(client, true)
            resolver = ExpressMetadataResolver(http, tmdb, mockk())
        }

        suspend fun resolve() = withContext(Dispatchers.IO) {
            resolver.resolve(ExpressMediaRequest("series", "tt1286677:2:1"))
        }
        override fun close() = server.close()
    }

    private companion object {
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
