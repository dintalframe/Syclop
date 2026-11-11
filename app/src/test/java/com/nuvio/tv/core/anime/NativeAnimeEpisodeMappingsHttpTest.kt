package com.nuvio.tv.core.anime

import com.nuvio.tv.data.mal.MalSourceMetadata
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
    @Test fun `raw Kitsu keeps its exact edition and gains independently corroborated TVDB coordinates`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(hunterKitsu()))
            h.server.enqueue(json(hunterMapping()))

            val proof = withTimeout(3_000) { h.repo.proof("kitsu:6448:68") }!!
            assertTrue(proof.isKitsuEpisode(6448, 68))
            assertFalse(proof.catalogOnly)
            assertEquals(252322L, proof.tvdbShowId)
            assertEquals(2, proof.tvdbSeason)
            assertEquals(10, proof.tvdbEpisode)
            assertEquals(68, proof.absoluteEpisode)
            assertFalse(proof.usesCatalogOrdinalForSearch)
            assertTrue(proof.matchesFile("Hunter x Hunter (2011) S02E10.mkv"))
            assertTrue(proof.acceptsReleaseTitle("Hunter x Hunter (2011) S02E10"))
            assertTrue(proof.matchesFile("Hunter x Hunter (2011) - 68.mkv"))
            assertFalse(proof.matchesFile("Hunter x Hunter (2011) S01E68.mkv"))
            assertFalse(proof.matchesFile("Hunter x Hunter (2011) S02E11.mkv"))
            assertFalse(proof.matchesFile("Hunter x Hunter (1999) S02E10.mkv"))
            assertFalse(proof.matchesFile("Hunter x Hunter S02E10.mkv"))
            assertFalse(proof.matchesFile("Hunter x Hunter (2011) S02E10 - 69.mkv"))
            assertSame(proof, h.repo.cachedProof("kitsu:6448:68"))

            val sibling = withTimeout(3_000) { h.repo.proof("kitsu:6448:69") }!!
            assertTrue(sibling.isKitsuEpisode(6448, 69))
            assertFalse(sibling.catalogOnly)
            assertEquals(11, sibling.tvdbEpisode)
            assertNull(sibling.absoluteEpisode)
            assertTrue(sibling.usesCatalogOrdinalForSearch)
            assertTrue(sibling.matchesFile("Hunter x Hunter (2011) - 69.mkv"))
            assertEquals(2, h.server.requestCount)
            assertEquals("number,seasonNumber,relativeNumber,canonicalTitle,airdate",
                h.original.first().url.queryParameter("fields[episodes]"))
        }
    }

    @Test fun `raw Kitsu retains local coordinates when exact episode evidence is incomplete or conflicts`() = runBlocking {
        val mapping = hunterMapping()
        for ((label, changed) in listOf(
            "name" to mapping.replace("Pirates x and x Guesses", "A Different Episode"),
            "date" to mapping.replace("2013-02-24", "2013-02-25"),
            "absolute" to mapping.replace("\"absoluteEpisodeNumber\":68", "\"absoluteEpisodeNumber\":70"),
            "missing name" to mapping.replace("\"title\":{\"en\":\"Pirates x and x Guesses\"},", ""),
            "missing date" to mapping.replace("\"airDate\":\"2013-02-24\",", "")
        )) {
            Harness().use { h ->
                h.server.enqueue(json(hunterKitsu()))
                h.server.enqueue(json(changed))
                val proof = withTimeout(3_000) { h.repo.proof("kitsu:6448:68") }!!
                assertTrue(label, proof.isKitsuEpisode(6448, 68))
                assertTrue(label, proof.catalogOnly)
                assertNull(label, proof.tvdbSeason)
                assertTrue(label, proof.matchesFile("Hunter x Hunter (2011) S01E68.mkv"))
                assertTrue(label, proof.matchesFile("Hunter x Hunter (2011) - 68.mkv"))
                assertFalse(label, proof.matchesFile("Hunter x Hunter (2011) S02E10.mkv"))
                assertEquals(label, 2, h.server.requestCount)
            }
        }
    }

    @Test fun `verified Kitsu linkage recovers an anime without TVDB and keeps the request account free`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json("""{"mappings":{"mal_id":63367,"kitsu_id":50497,"anilist_id":206814},"episodes":{},"images":[]}"""))
            h.server.enqueue(json(kitsu(1, 393369)))
            val proof = withTimeout(3_000) { h.repo.proof("mal:63367:1") }!!
            assertTrue(proof.catalogOnly)
            assertTrue(proof.matchesFile("Dragon Ball Super Beerus (2026) - 01.mkv"))
            assertFalse(proof.matchesFile("Dragon Ball Super (2026) - 01.mkv"))
            assertNull(proof.tvdbShowId)
            assertNull(proof.absoluteEpisode)
            assertEquals(2, h.original.size)
            assertEquals("kitsu.io", h.original.last().url.host)
            assertEquals("/api/edge/anime/50497", h.original.last().url.encodedPath)
            repeat(2) {
                val wire = h.server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("GET", wire.method)
                assertEquals(0L, wire.bodySize)
                for (header in listOf("Authorization", "Proxy-Authorization", "Cookie")) assertNull(wire.getHeader(header))
            }
            assertSame(proof, h.repo.cachedProof("mal:63367:1"))
            assertEquals(listOf(1), h.repo.confirmedEpisodeNumbers("mal:63367"))
            assertTrue(h.repo.confirmedEpisodeNumbers("mal:63367:1").isEmpty())
            assertEquals(0, h.authenticationCalls.get())
        }
    }

    @Test fun `a crosswalk for another anime never sends a fallback request`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json("""{"mappings":{"mal_id":59978,"kitsu_id":50497},"episodes":{},"images":[]}"""))
            assertNull(withTimeout(3_000) { h.repo.proof("mal:63367:1") })
            assertEquals(1, h.original.size)
        }
    }

    @Test fun `a cached mapped episode does not suppress fallback for another episode`() = runBlocking {
        Harness().use { h ->
            val primary = beerusMapping(1..1)
            h.server.enqueue(json(primary))
            val first = withTimeout(3_000) { h.repo.proof("mal:63367:1") }!!
            assertFalse(first.catalogOnly)
            h.server.enqueue(json(primary))
            h.server.enqueue(json(kitsu(2, 393370)))
            val second = withTimeout(3_000) { h.repo.proof("mal:63367:2") }!!
            assertTrue(second.catalogOnly)
            assertEquals(first.cacheKey, h.repo.cachedProof("mal:63367:1")?.cacheKey)
            h.clock.addAndGet(5L * 60 * 1000 + 1)
            assertEquals(second.cacheKey, h.repo.cachedProof("mal:63367:2")?.cacheKey)
            assertEquals(3, h.original.size)
        }
    }

    @Test fun `refresh for another episode upgrades an existing catalog fallback to a primary mapping`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(beerusMapping(1..1)))
            h.server.enqueue(json(kitsu(2, 393370)))
            val fallback = withTimeout(3_000) { h.repo.proof("mal:63367:2") }!!
            assertTrue(fallback.catalogOnly)

            h.server.enqueue(json(beerusMapping(1..2)))
            h.server.enqueue(json(kitsu(3, 393371)))
            assertTrue(withTimeout(3_000) { h.repo.proof("mal:63367:3") }!!.catalogOnly)
            assertEquals(4, h.server.requestCount)

            val upgraded = requireNotNull(h.repo.cachedProof("mal:63367:2"))
            assertFalse(upgraded.catalogOnly)
            assertEquals(2, upgraded.tvdbSeason)
            assertEquals(2, upgraded.tvdbEpisode)
            assertSame(upgraded, withTimeout(3_000) { h.repo.proof("mal:63367:2") })
            assertEquals(4, h.server.requestCount)
        }
    }

    @Test fun `catalog fallback remains usable while active lookups recheck for a primary mapping`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(beerusMapping(1..1)))
            h.server.enqueue(json(kitsu(2, 393370)))
            val fallback = withTimeout(3_000) { h.repo.proof("mal:63367:2") }!!
            assertTrue(fallback.catalogOnly)

            h.clock.addAndGet(5L * 60 * 1000 - 1)
            assertSame(fallback, withTimeout(3_000) { h.repo.proof("mal:63367:2") })
            h.clock.addAndGet(2)
            assertSame(fallback, h.repo.cachedProof("mal:63367:2"))
            assertEquals(2, h.server.requestCount)

            h.server.enqueue(MockResponse().setResponseCode(503))
            assertSame(fallback, withTimeout(3_000) { h.repo.proof("mal:63367:2") })
            assertSame(fallback, h.repo.cachedProof("mal:63367:2"))
            assertEquals(3, h.server.requestCount)
            assertSame(fallback, withTimeout(3_000) { h.repo.proof("mal:63367:2") })
            assertEquals(3, h.server.requestCount)

            h.clock.addAndGet(5L * 60 * 1000 + 1)
            h.server.enqueue(json(beerusMapping(1..2)))
            val upgraded = withTimeout(3_000) { h.repo.proof("mal:63367:2") }!!
            assertFalse(upgraded.catalogOnly)
            assertEquals(2, upgraded.tvdbSeason)
            assertSame(upgraded, h.repo.cachedProof("mal:63367:2"))
            assertEquals(4, h.server.requestCount)
        }
    }

    @Test fun `details use only verified episode numbers when episode one is unavailable`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json("""{"mappings":{"mal_id":100,"thetvdb_id":200},"titles":{"en":"An Airing Title"},"episodes":{"3":{"episode":"3","tvdbShowId":200,"tvdbId":300,"seasonNumber":1,"episodeNumber":3}}}"""))
            assertEquals(listOf(3), withTimeout(3_000) { h.repo.confirmedEpisodeNumbers("mal:100") })
            assertNull(h.repo.cachedProof("mal:100:1"))
            assertEquals(3, h.repo.cachedProof("mal:100:3")?.malEpisode)
            assertEquals(1, h.original.size)
        }
    }

    @Test fun `verified MAL fallback works when mapping services have no episode row`() = runBlocking {
        Harness(malMetadata = { id -> MalSourceMetadata(id, "A Distant Harbor", 2024, "ona", 12,
            startDate = "2024-02-29") }).use { h ->
            h.server.enqueue(MockResponse().setResponseCode(503))
            val proof = withTimeout(3_000) { h.repo.proof("mal:100:8") }!!
            assertTrue(proof.matchesFile("A Distant Harbor (2024) - 08.mkv"))
            assertFalse(proof.matchesFile("A Distant Harbor (2024) S01E08.mkv"))
            assertEquals(1, h.original.size)
        }
    }

    @Test fun `a Kitsu title contradicting known MAL metadata cannot select another work`() = runBlocking {
        Harness(malMetadata = { id -> MalSourceMetadata(id, "A Distant Harbor", 2026, "ona", 12,
            startDate = "2026-01") }).use { h ->
            h.server.enqueue(json("""{"mappings":{"mal_id":63367,"kitsu_id":50497,"anilist_id":206814},"episodes":{}}"""))
            h.server.enqueue(json(kitsu(1, 393369)))
            val proof = withTimeout(3_000) { h.repo.proof("mal:63367:1") }!!
            assertTrue(proof.matchesFile("A Distant Harbor (2026) - 01.mkv"))
            assertFalse(proof.matchesFile("Dragon Ball Super Beerus (2026) - 01.mkv"))
        }
    }

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

    @Test fun `verified mapped IMDb fetches bounded public parent episode without account headers and caches it`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(NativeAnimeParentFixtures.MAPPING))
            h.server.enqueue(json(NativeAnimeParentFixtures.PARENT))
            val proof = withTimeout(3_000) { h.repo.proof("mal:61990:1") }!!
            assertEquals("tt12590266", proof.verifiedParentImdbId)
            assertTrue(proof.matchesFile(NativeAnimeParentFixtures.RELEASE))
            assertEquals(2, h.original.size)
            val parent = h.original.last()
            assertEquals("https", parent.url.scheme)
            assertEquals("v3-cinemeta.strem.io", parent.url.host)
            assertEquals("/meta/series/tt12590266.json", parent.url.encodedPath)
            assertTrue(parent.url.queryParameterNames.isEmpty())
            repeat(2) {
                val wire = h.server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("GET", wire.method)
                assertEquals(0L, wire.bodySize)
                for (header in listOf("Authorization", "Proxy-Authorization", "Cookie")) assertNull(wire.getHeader(header))
            }
            assertSame(proof, h.repo.cachedProof("mal:61990:1"))
            assertSame(proof, h.repo.proof("mal:61990:1"))
            assertEquals(2, h.server.requestCount)
            assertEquals(0, h.authenticationCalls.get())
        }
    }

    @Test fun `unavailable malformed redirected or mismatched parent metadata never expands validated mapping`() = runBlocking {
        for (mode in listOf("unavailable", "malformed", "identity", "redirect")) {
            Harness().use { h ->
                h.server.enqueue(json(NativeAnimeParentFixtures.MAPPING))
                h.server.enqueue(when (mode) {
                    "unavailable" -> MockResponse().setResponseCode(503)
                    "malformed" -> json("{")
                    "identity" -> json(NativeAnimeParentFixtures.PARENT.replace("tt12590266", "tt99999999"))
                    else -> MockResponse().setResponseCode(302).setHeader("Location", h.server.url("/must-not-follow"))
                })
                val proof = withTimeout(3_000) { h.repo.proof("mal:61990:1") }!!
                assertEquals(61990L, proof.malId)
                assertEquals(1, proof.malEpisode)
                assertNull(mode, proof.verifiedParentImdbId)
                assertFalse(mode, proof.matchesFile(NativeAnimeParentFixtures.RELEASE))
                assertTrue(mode, proof.matchesFile("Cyberpunk Edgerunners 2 S02E01.mkv"))
                assertEquals(mode, 2, h.server.requestCount)
            }
        }
    }

    @Test fun `optional parent timeout preserves mapping and cancels its bounded HTTP call`() = runBlocking {
        Harness(timeoutMillis = 200L).use { h ->
            h.server.enqueue(json(NativeAnimeParentFixtures.MAPPING))
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val proof = withTimeout(3_000) { h.repo.proof("mal:61990:1") }!!
            assertEquals(11, proof.absoluteEpisode)
            assertNull(proof.verifiedParentImdbId)
            assertEquals(2, h.server.requestCount)
            assertFalse(proof.matchesFile(NativeAnimeParentFixtures.RELEASE))
            assertSame(proof, h.repo.cachedProof("mal:61990:1"))
        }
    }

    @Test fun `profile cancellation during parent metadata cannot publish even the preceding base mapping`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(NativeAnimeParentFixtures.MAPPING))
            h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val lookup = launch(Dispatchers.Default) { h.repo.proof("mal:61990:1") }
            withContext(Dispatchers.IO) {
                repeat(2) { assertNotNull(h.server.takeRequest(3, TimeUnit.SECONDS)) }
            }
            h.origin.set(2 to 11L)
            h.changes.value += 1
            withTimeout(3_000) { lookup.join() }
            assertTrue(lookup.isCancelled)
            assertTrue(requireNotNull(h.call.get()).isCanceled())
            assertNull(h.repo.cachedProof("mal:61990:1"))
            assertEquals(2, h.server.requestCount)
        }
    }

    @Test fun `temporary parent failure keeps base mapping then retries after five minutes and caches verified recovery`() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(json(NativeAnimeParentFixtures.MAPPING))
            h.server.enqueue(MockResponse().setResponseCode(503))
            val base = withTimeout(3_000) { h.repo.proof("mal:61990:1") }!!
            assertEquals(11, base.absoluteEpisode)
            assertTrue(base.matchesFile("Cyberpunk Edgerunners 2 S02E01.mkv"))
            assertFalse(base.matchesFile(NativeAnimeParentFixtures.RELEASE))
            assertNull(base.verifiedParentImdbId)
            assertEquals(2, h.server.requestCount)

            h.server.enqueue(json(NativeAnimeParentFixtures.MAPPING))
            h.server.enqueue(json(NativeAnimeParentFixtures.PARENT))
            h.clock.addAndGet(5L * 60L * 1_000L - 1L)
            assertSame(base, h.repo.proof("mal:61990:1"))
            assertEquals(2, h.server.requestCount)
            h.clock.addAndGet(2L)
            assertNull(h.repo.cachedProof("mal:61990:1"))
            val recovered = withTimeout(3_000) { h.repo.proof("mal:61990:1") }!!
            assertEquals("tt12590266", recovered.verifiedParentImdbId)
            assertTrue(recovered.matchesFile(NativeAnimeParentFixtures.RELEASE))
            assertEquals(4, h.server.requestCount)

            h.clock.addAndGet(5L * 60L * 1_000L + 1L)
            assertSame(recovered, h.repo.proof("mal:61990:1"))
            assertEquals(4, h.server.requestCount)
            h.clock.addAndGet(24L * 60L * 60L * 1_000L)
            assertNull(h.repo.cachedProof("mal:61990:1"))
        }
    }

    private class Harness(timeoutMillis: Long = 8_000L,
        malMetadata: (suspend (Long) -> MalSourceMetadata)? = null) : Closeable {
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
        val repo = NativeAnimeEpisodeMappings(client, clock::get, origin::get, changes, timeoutMillis, malMetadata)
        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun hunterKitsu() = """{
        "data":{"id":"6448","type":"anime","attributes":{"canonicalTitle":"Hunter x Hunter (2011)","startDate":"2011-10-02","subtype":"TV"},
        "relationships":{"episodes":{"data":[{"id":"106287","type":"episodes"},{"id":"106288","type":"episodes"}]},
        "mappings":{"data":[{"id":"1","type":"mappings"},{"id":"2","type":"mappings"}]}}},
        "included":[{"id":"106287","type":"episodes","attributes":{"number":68,"seasonNumber":1,"relativeNumber":68,
            "canonicalTitle":"Pirates × And × Guesses","airdate":"2013-02-24"}},
        {"id":"106288","type":"episodes","attributes":{"number":69,"seasonNumber":1,"relativeNumber":69,
            "canonicalTitle":"A × Heated × Showdown","airdate":"2013-03-03"}},
        {"id":"1","type":"mappings","attributes":{"externalSite":"myanimelist/anime","externalId":"11061"}},
        {"id":"2","type":"mappings","attributes":{"externalSite":"anilist/anime","externalId":"11061"}}]
        }"""
    private fun hunterMapping() = """{
        "titles":{"en":"Hunter x Hunter (2011)"},"mappings":{"mal_id":11061,"anilist_id":11061,"kitsu_id":6448,"thetvdb_id":252322},
        "episodes":{"68":{"episode":"68","tvdbShowId":252322,"tvdbId":4504220,"seasonNumber":2,"episodeNumber":10,"absoluteEpisodeNumber":68,
            "title":{"en":"Pirates x and x Guesses"},"airDate":"2013-02-24","runtime":24},
        "69":{"episode":"69","tvdbShowId":252322,"tvdbId":4508443,"seasonNumber":2,"episodeNumber":11,
            "title":{"en":"A x Heated x Showdown"},"airDate":"2013-03-03"}}
        }"""
    private fun beerusMapping(episodeNumbers: IntRange): String {
        val episodes = episodeNumbers.joinToString { number ->
            """"$number":{"episode":"$number","tvdbShowId":12345,"tvdbId":${23455 + number},"seasonNumber":2,"episodeNumber":$number}"""
        }
        return """{"mappings":{"mal_id":63367,"thetvdb_id":12345,"kitsu_id":50497,"anilist_id":206814},"titles":{"en":"Dragon Ball Super: Beerus"},"episodes":{$episodes}}"""
    }

    private fun kitsu(number: Int, id: Long) = """{
        "data":{"id":"50497","type":"anime","attributes":{"canonicalTitle":"Dragon Ball Super: Beerus","startDate":"2026-10-11","subtype":"TV"},
        "relationships":{"episodes":{"data":[{"id":"$id","type":"episodes"}]},
        "mappings":{"data":[{"id":"351174","type":"mappings"}]}}},
        "included":[{"id":"$id","type":"episodes","attributes":{"number":$number,"seasonNumber":1}},
        {"id":"351174","type":"mappings","attributes":{"externalSite":"anilist/anime","externalId":"206814"}}]
        }"""
    private fun fixture() = """{"titles":{"en":"Frieren"},"mappings":{"mal_id":59978,"thetvdb_id":424536},"episodes":{"1":{"tvdbShowId":424536,"tvdbId":10719109,"seasonNumber":2,"episodeNumber":1,"absoluteEpisodeNumber":29,"episode":"1"},"2":{"tvdbShowId":424536,"tvdbId":10719110,"seasonNumber":2,"episodeNumber":2,"absoluteEpisodeNumber":30,"episode":"2"}}}"""
}
