package com.nuvio.tv.data.simkl.catalog

import com.nuvio.tv.data.simkl.SimklApiConfiguration
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Actual loopback HTTP and cancellation, using wall time rather than a virtual socket timeout. */
class SimklPublicCatalogHttpTest {
    @Test fun `configured public feed sends app identity and never account auth cookie or request body`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json())
            val row = withTimeout(3_000) { h.catalog.catalog("anime-week", 0) }
            assertEquals("mal:59978", row.items.single().id)
            val request = h.original.single()
            assertEquals("https", request.url.scheme)
            assertEquals("data.simkl.in", request.url.host)
            assertEquals("/discover/trending/anime/week_100.json", request.url.encodedPath)
            assertEquals(setOf("client_id", "app-name", "app-version"), request.url.queryParameterNames)
            assertEquals("syclop-public-test", request.url.queryParameter("client_id"))
            val wire = h.server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", wire.method)
            assertEquals(0, wire.bodySize.toInt())
            assertEquals("Syclop/test", wire.getHeader("User-Agent"))
            for (name in listOf("Authorization", "Proxy-Authorization", "Cookie")) assertNull(wire.getHeader(name))
            assertEquals(0, h.authCalls.get())
        }
    }

    @Test fun `blank owner configuration advertises nothing and issues no request`() = runBlocking {
        Harness(clientId = "").use { h ->
            assertNull(h.catalog.addon.first())
            assertTrue(runCatching { h.catalog.catalog("anime-week", 0) }.isFailure)
            assertEquals(0, h.server.requestCount)
        }
    }

    @Test fun `public addon preserves MAL alongside bounded branded catalogs without claiming age ratings`() = runBlocking {
        Harness().use { h ->
            val addon = h.catalog.addon.first()!!
            assertEquals(SimklPublicCatalog.ADDON_ID, addon.id)
            assertEquals(listOf("anime-week"), addon.catalogs.filter { it.showInHome }.map { it.id })
            assertTrue(addon.catalogs.all { it.name.startsWith("Simkl Trending") })
            assertEquals(setOf("catalog"), addon.resources.map { it.name }.toSet())
            assertTrue(addon.catalogs.filter { it.id.startsWith("anime-") }.all { it.apiType == "anime" })
        }
    }

    @Test fun `concurrent same feed shares one request and TTL expiry refreshes on demand only`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json())
            val first = async(Dispatchers.Default) { h.catalog.catalog("anime-week", 0) }
            val second = async(Dispatchers.Default) { h.catalog.catalog("anime-week", 0) }
            withTimeout(3_000) { assertEquals(first.await().items, second.await().items) }
            assertEquals(1, h.server.requestCount)
            h.catalog.catalog("anime-week", 0)
            assertEquals(1, h.server.requestCount)
            h.clock.addAndGet(SimklPublicCatalogClient.CACHE_TTL_MS + 1)
            h.server.enqueue(json())
            h.catalog.catalog("anime-week", 0)
            assertEquals(2, h.server.requestCount)
        }
    }

    @Test fun `genre uses only publisher index slug and retains documented genre-file membership`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json("""[{"name":"Documentary","slug":"publisher-documentary"}]"""))
            h.server.enqueue(json())
            val row = h.catalog.catalog("movies-genre-documentary", 0)
            assertTrue(row.items.single().genres.contains("Documentary"))
            assertEquals(listOf("/discover/trending/movies/genre/index.json", "/discover/trending/movies/genre/publisher-documentary_month_500.json"),
                h.original.map { it.url.encodedPath })
        }
    }

    @Test fun `unavailable genre does not construct a guessed CDN path`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json("""[{"name":"Drama","slug":"drama"}]"""))
            assertTrue(h.catalog.catalog("movies-genre-documentary", 0).items.isEmpty())
            assertEquals(1, h.server.requestCount)
        }
    }

    @Test fun `redirect wrong mime compressed oversized malformed and HTTP errors cannot become catalog data`() = runBlocking {
        for (mode in listOf("redirect", "mime", "compressed", "length", "chunked", "malformed", "401")) Harness().use { h ->
            val response = when (mode) {
                "redirect" -> MockResponse().setResponseCode(302).setHeader("Location", h.server.url("/must-not-follow"))
                "mime" -> MockResponse().setHeader("Content-Type", "text/html").setBody("[]")
                "compressed" -> json().setHeader("Content-Encoding", "br")
                "length" -> json(" ".repeat(SimklPublicCatalogClient.ITEMS_LIMIT + 1))
                "chunked" -> MockResponse().setHeader("Content-Type", "application/json").setChunkedBody(" ".repeat(SimklPublicCatalogClient.ITEMS_LIMIT + 1), 8192)
                "malformed" -> json("{")
                else -> MockResponse().setResponseCode(401).setBody("synthetic-private-response-must-not-appear")
            }
            h.server.enqueue(response)
            val failure = runCatching { withTimeout(5_000) { h.catalog.catalog("anime-week", 0) } }.exceptionOrNull()
            assertNotNull(mode, failure)
            assertFalse(failure!!.message.orEmpty().contains("synthetic-private-response"))
            assertEquals(mode, 1, h.server.requestCount)
            assertEquals(0, h.authCalls.get())
        }
    }

    @Test fun `caller cancellation cancels actual HTTP call`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.Default) { h.catalog.catalog("anime-week", 0) }
            assertNotNull(h.server.takeRequest(3, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(h.call.get()!!.isCanceled())
        }
    }

    @Test fun `profile flow switch cancels HTTP and revision-only away-back cannot publish or cache late data`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.Default) { runCatching { h.catalog.catalog("anime-week", 0) } }
            assertNotNull(h.server.takeRequest(3, TimeUnit.SECONDS))
            h.revision.incrementAndGet(); h.profile.set(2); h.changes.value = 2
            val failure = withTimeout(3_000) { pending.await().exceptionOrNull() }
            assertTrue(failure is CancellationException)
            assertTrue(h.call.get()!!.isCanceled())
        }
        Harness().use { h ->
            h.afterResponse = { h.revision.addAndGet(2) } // No flow emission: catches conflated A→B→A.
            h.server.enqueue(json())
            assertTrue(runCatching { h.catalog.catalog("anime-week", 0) }.exceptionOrNull() is CancellationException)
            h.afterResponse = {}
            h.server.enqueue(json())
            h.catalog.catalog("anime-week", 0)
            assertEquals(2, h.server.requestCount)
        }
    }

    @Test fun `unsupported catalog page or path issues no request`() = runBlocking {
        Harness().use { h ->
            for ((id, page) in listOf("../account" to 0, "anime-week" to -1, "anime-week" to 1, "anime-week" to 600))
                assertTrue(runCatching { h.catalog.catalog(id, page) }.isFailure)
            assertEquals(0, h.server.requestCount)
        }
    }

    private class Harness(clientId: String = "syclop-public-test") : Closeable {
        val server = MockWebServer().apply { start() }
        val clock = AtomicLong(1)
        val profile = AtomicInteger(1)
        val revision = AtomicLong(0)
        val changes = MutableStateFlow(1)
        val original = CopyOnWriteArrayList<Request>()
        val call = AtomicReference<Call>()
        val authCalls = AtomicInteger()
        var afterResponse: () -> Unit = {}
        private val client = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("synthetic-account-cookie")
                    .value("must-not-leave-client").hostOnlyDomain(url.host).build())
            }).authenticator(Authenticator { _, response ->
                authCalls.incrementAndGet(); response.request.newBuilder().header("Authorization", "must-not-leave-client").build()
            }).addInterceptor { chain ->
                original += chain.request(); call.set(chain.call())
                val url = server.url(chain.request().url.encodedPath).newBuilder().encodedQuery(chain.request().url.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(url).build()).also { afterResponse() }
            }.build()
        val transport = SimklPublicCatalogClient(SimklApiConfiguration(clientId, "syclop", "test"), client, clock::get, 2_000)
        val catalog = SimklPublicCatalog(transport, profile::get, revision::get, changes)
        override fun close() {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    private fun json(body: String = "[${SimklPublicCatalogCodecTest.item()}]") =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
