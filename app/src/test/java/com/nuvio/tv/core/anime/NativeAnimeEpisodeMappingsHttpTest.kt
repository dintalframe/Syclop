package com.nuvio.tv.core.anime

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/** Actual loopback HTTP/cancellation. Wall-clock coroutines avoid virtual-time socket races. */
class NativeAnimeEpisodeMappingsHttpTest {
    @Test fun `one fixed public GET sends numeric MAL ID without account auth cookie or body`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(fixture()))
            assertEquals(29, withTimeout(3_000) { h.repo.proof("mal:59978:1") }?.absoluteEpisode)
            val original = h.original.single()
            assertEquals("https", original.url.scheme)
            assertEquals("api.ani.zip", original.url.host)
            assertEquals("/mappings", original.url.encodedPath)
            assertEquals(setOf("mal_id"), original.url.queryParameterNames)
            assertEquals("59978", original.url.queryParameter("mal_id"))
            val wire = h.server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("GET", wire.method)
            assertEquals("/mappings?mal_id=59978", wire.path)
            assertEquals(0L, wire.bodySize)
            assertEquals("application/json", wire.getHeader("Accept"))
            assertEquals("identity", wire.getHeader("Accept-Encoding"))
            for (header in listOf("Authorization", "Proxy-Authorization", "Cookie")) assertNull(wire.getHeader(header))
            assertEquals(0, h.authenticationCalls.get())
        }
    }

    @Test fun `concurrent relative episodes share one record fetch and preserve distinct proofs`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(fixture()))
            val first = async(Dispatchers.Default) { h.repo.proof("mal:59978:1") }
            val second = async(Dispatchers.Default) { h.repo.proof("mal:59978:2") }
            withTimeout(3_000) {
                assertEquals(29, first.await()?.absoluteEpisode)
                assertEquals(30, second.await()?.absoluteEpisode)
            }
            assertEquals(1, h.server.requestCount)
            assertEquals(1, h.original.size)
        }
    }

    @Test fun `cache only lookup never fetches and expiry remains cache only`() = runBlocking {
        Harness().use { h ->
            assertNull(h.repo.cachedProof("mal:59978:1"))
            assertEquals(0, h.server.requestCount)
            h.server.enqueue(json(fixture()))
            assertNotNull(withTimeout(3_000) { h.repo.proof("mal:59978:1") })
            assertEquals(30, h.repo.cachedProof("mal:59978:2")?.absoluteEpisode)
            assertNull(h.repo.cachedProof("mal:59978:3"))
            h.clock.addAndGet(24L * 60 * 60 * 1000 + 1)
            assertNull(h.repo.cachedProof("mal:59978:2"))
            assertEquals(1, h.server.requestCount)
        }
    }

    @Test fun `redirect oversized malformed and unauthenticated error responses fail closed`() = runBlocking {
        for (mode in listOf("redirect", "length", "chunked", "malformed", "type", "unauthorized")) {
            Harness(timeoutMillis = 1_000L).use { h ->
                val response = when (mode) {
                    "redirect" -> MockResponse().setResponseCode(302).setHeader("Location", h.server.url("/must-not-follow"))
                    "length" -> json(" ".repeat(2 * 1024 * 1024 + 1))
                    "chunked" -> MockResponse().setHeader("Content-Type", "application/json").setChunkedBody(" ".repeat(2 * 1024 * 1024 + 1), 8_192)
                    "malformed" -> json("{")
                    "type" -> MockResponse().setHeader("Content-Type", "text/plain").setBody(fixture())
                    else -> MockResponse().setResponseCode(401)
                }
                h.server.enqueue(response)
                assertNull(mode, withTimeout(3_000) { h.repo.proof("mal:59978:1") })
                assertNull(mode, h.repo.cachedProof("mal:59978:1"))
                assertEquals(mode, 1, h.server.requestCount)
                assertEquals(mode, 0, h.authenticationCalls.get())
            }
        }
    }

    @Test fun `network timeout is never a proof and negative cache expires before successful retry`() = runBlocking {
        Harness(timeoutMillis = 200L).use { h ->
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertNull(withTimeout(3_000) { h.repo.proof("mal:59978:1") })
            assertNull(h.repo.cachedProof("mal:59978:1"))
            assertNull(withTimeout(3_000) { h.repo.proof("mal:59978:2") })
            assertEquals(1, h.original.size)
            h.clock.addAndGet(5L * 60 * 1000 + 1)
            h.server.enqueue(json(fixture()))
            assertEquals(29, withTimeout(3_000) { h.repo.proof("mal:59978:1") }?.absoluteEpisode)
            assertEquals(2, h.original.size)
        }
    }

    @Test fun `caller cancellation cancels the actual in flight HTTP Call without caching`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val lookup = launch(Dispatchers.Default) { h.repo.proof("mal:59978:1") }
            withContext(Dispatchers.IO) { assertNotNull(h.server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3_000) { lookup.cancelAndJoin() }
            assertTrue(lookup.isCancelled)
            assertTrue(requireNotNull(h.call.get()).isCanceled())
            assertNull(h.repo.cachedProof("mal:59978:1"))
        }
    }

    @Test fun `profile flow invalidation cancels the real Call even while server never responds`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val lookup = launch(Dispatchers.Default) { h.repo.proof("mal:59978:1") }
            withContext(Dispatchers.IO) { assertNotNull(h.server.takeRequest(3, TimeUnit.SECONDS)) }
            h.origin.set(2 to 11L)
            h.changes.value += 1
            withTimeout(3_000) { lookup.join() }
            assertTrue(lookup.isCancelled)
            assertTrue(requireNotNull(h.call.get()).isCanceled())
            assertNull(h.repo.cachedProof("mal:59978:1"))
        }
    }

    @Test fun `origin changed after response before publication cannot seed any cache`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(fixture()))
            h.server.enqueue(json(fixture()))
            h.afterResponse = { h.origin.set(2 to 11L) }
            try {
                withTimeout(3_000) { h.repo.proof("mal:59978:1") }
                fail("stale origin must cancel publication")
            } catch (cancelled: CancellationException) {
                assertFalse("stale guard must fail before the test deadline", cancelled is TimeoutCancellationException)
            }
            assertNull(h.repo.cachedProof("mal:59978:1"))
            h.afterResponse = {}
            assertEquals(29, withTimeout(3_000) { h.repo.proof("mal:59978:1") }?.absoluteEpisode)
            assertEquals(2, h.original.size)
        }
    }

    private class Harness(timeoutMillis: Long = 8_000L) : Closeable {
        val server = MockWebServer().apply { start() }
        val clock = AtomicLong(1_000L)
        val origin = AtomicReference(1 to 10L)
        val changes = MutableStateFlow(0)
        val original = CopyOnWriteArrayList<Request>()
        val call = AtomicReference<Call>()
        val authenticationCalls = AtomicInteger()
        @Volatile var afterResponse: () -> Unit = {}
        private val client = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder()
                    .name("synthetic-account-cookie").value("must-not-leave-client")
                    .hostOnlyDomain(url.host).build())
            })
            .authenticator(Authenticator { _, response ->
                authenticationCalls.incrementAndGet()
                response.request.newBuilder().header("Authorization", "must-not-leave-client").build()
            })
            .addInterceptor { chain ->
                val request = chain.request()
                original += request
                call.set(chain.call())
                val rewritten = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
                chain.proceed(request.newBuilder().url(rewritten).build()).also { afterResponse() }
            }.build()
        val repo = NativeAnimeEpisodeMappings(client, clock::get, origin::get, changes, timeoutMillis)
        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun fixture() = """{"titles":{"en":"Frieren"},"mappings":{"mal_id":59978,"thetvdb_id":424536},"episodes":{"1":{"tvdbShowId":424536,"tvdbId":10719109,"seasonNumber":2,"episodeNumber":1,"absoluteEpisodeNumber":29,"episode":"1"},"2":{"tvdbShowId":424536,"tvdbId":10719110,"seasonNumber":2,"episodeNumber":2,"absoluteEpisodeNumber":30,"episode":"2"}}}"""
}
