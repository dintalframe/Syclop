package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.ServicePlaybackGrantRegistry
import com.nuvio.tv.core.streams.ServicePlaybackOrigin
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridMagnetCollectionAdapterFactory
import com.nuvio.tv.domain.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Actual router, cache, operation guards, provider resolvers and intercepted HTTP contracts. */
class NativeDebridFallbackIntegrationTest {
    @Test fun `AD processing falls back to RD with separate auth and actual RD grant metadata cache`() = runBlocking {
        Harness().use { h ->
            h.adProcessing(); h.rdReady()
            val attempts = mutableListOf<String>()
            val result = withTimeout(10_000) { h.resolver.resolve(source(), null, null, onProviderAttempt = { attempts += it }) }
            assertEquals(DirectDebridResolveResult.Success(FILE, "movie.mkv", 123L), result)
            assertEquals(listOf("alldebrid", "realdebrid"), attempts)
            assertEquals("alldebrid", h.settings.value.activeResolverProviderId)
            assertTrue(h.resolver.isPlaybackAuthorized(FILE, 1))
            verify(exactly = 1) { h.grants.issue(FILE, "realdebrid", any()) }
            verify(exactly = 0) { h.grants.issue(FILE, "alldebrid", any()) }
            val cached = h.resolver.cachedPlayableStream(source(), null, null)!!
            assertEquals(FILE, cached.getStreamUrl())
            assertEquals(123L, cached.nativeFileMetadata?.selectedFileBytes)
            assertNull(cached.behaviorHints?.proxyHeaders)
            assertNull(cached.clientResolve)
            assertNull(cached.debridCacheStatus)
            // Result-list ranking reads only existing native caches, with the actual RD size.
            val oldFacts = source().copy(nativeFileMetadata = StreamFileMetadata(999L, 9000L,
                StreamDurationOrigin.NATIVE_CONTAINER, verifiedTracks = VerifiedSourceMediaFacts(video = VerifiedSourceVideo(hdr = VerifiedSourceHdr.DOLBY_VISION))))
            val facts = DebridBitrateMetadataService(h.resolver, h.store).enrichKnownFiles(listOf(oldFacts),
                1000L, StreamDurationOrigin.CATALOG_MOVIE, null, null, 1).single().second
            assertEquals(source().url, facts.url)
            assertEquals(123L, facts.nativeFileMetadata?.selectedFileBytes)
            assertEquals(1000L, facts.nativeFileMetadata?.durationMillis)
            assertNull(facts.nativeFileMetadata?.verifiedTracks)
            assertEquals(StreamDurationOrigin.CATALOG_MOVIE, facts.nativeFileMetadata?.durationOrigin)
            assertEquals(2, h.ad.requestCount); assertEquals(5, h.rd.requestCount)
            repeat(2) {
                val request = h.ad.takeRequest()
                assertEquals("Bearer synthetic-ad-key", request.getHeader("Authorization"))
                listOf("synthetic-ad-key", "synthetic-rd-key").forEach { credential ->
                    assertFalse(request.path.orEmpty().contains(credential))
                }
            }
            val expectedRdPaths = listOf("/torrents/addMagnet", "/torrents/info/synthetic-job",
                "/torrents/selectFiles/synthetic-job", "/torrents/info/synthetic-job", "/unrestrict/link")
            repeat(5) { index ->
                val request = h.rd.takeRequest()
                assertEquals(expectedRdPaths[index], request.path)
                assertEquals("Bearer synthetic-rd-key", request.getHeader("Authorization"))
                listOf("synthetic-ad-key", "synthetic-rd-key").forEach { credential ->
                    assertFalse(request.path.orEmpty().contains(credential))
                }
                if (index == 0) {
                    val body = request.body.readUtf8()
                    assertTrue(body.contains(HASH))
                    assertEquals("magnet=magnet:?xt=urn:btih:$HASH", java.net.URLDecoder.decode(body, "UTF-8"))
                    assertFalse(body.contains("private-tracker")); assertFalse(body.contains("provider-header"))
                }
            }
        }
    }

    @Test fun `current AD HTTP auth rejection or subscription error may skip only to separately authenticated RD`() = runBlocking {
        for (response in listOf(json("{}", 401), json("""{"status":"error","error":{"code":"MUST_BE_PREMIUM"}}"""))) {
            Harness().use { h ->
                h.ad.enqueue(response); h.rdReady()
                assertTrue(withTimeout(10_000) { h.resolver.resolve(source(), null, null) } is DirectDebridResolveResult.Success)
                assertEquals(1, h.ad.requestCount); assertEquals(5, h.rd.requestCount)
                assertEquals("Bearer synthetic-rd-key", h.rd.takeRequest().getHeader("Authorization"))
            }
        }
    }

    @Test fun `explicit AD client contract never falls back to RD`() = runBlocking {
        Harness().use { h ->
            h.adProcessing()
            assertEquals(DirectDebridResolveResult.NotCached, withTimeout(10_000) {
                h.resolver.resolve(source().copy(url = null, clientResolve = explicit()), null, null)
            })
            assertEquals(2, h.ad.requestCount); assertEquals(0, h.rd.requestCount)
        }
    }

    @Test fun `ambiguous source file selection is terminal instead of silently choosing another service`() = runBlocking {
        Harness().use { h ->
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":true}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"files":[]}]}}"""))
            assertEquals(DirectDebridResolveResult.Stale, withTimeout(10_000) { h.resolver.resolve(source(), null, null) })
            assertEquals(0, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `same viewer credential replacement invalidates cached RD file and never grants old account URL`() = runBlocking {
        Harness().use { h ->
            h.adProcessing(); h.rdReady()
            assertTrue(withTimeout(10_000) { h.resolver.resolve(source(), null, null) } is DirectDebridResolveResult.Success)
            h.credentials.incrementAndGet()
            h.settings.value = h.settings.value.copy(realDebridApiKey = "synthetic-reconnected-rd")
            assertNull(h.resolver.cachedPlayableStream(source(), null, null))
            assertFalse(h.resolver.isPlaybackAuthorized(FILE))
            assertEquals(2, h.ad.requestCount); assertEquals(5, h.rd.requestCount)
        }
    }

    @Test fun `away and back selection revision during AD response cancels before RD dispatch or grant`() = runBlocking {
        Harness().use { h ->
            h.ad.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return if (request.path == "/v4/magnet/upload") json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}""")
                    else { h.selection.addAndGet(2L); json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":1}]}}""") }
                }
            }
            try { withTimeout(10_000) { h.resolver.resolve(source(), null, null) }; fail("Expected cancellation") }
            catch (error: CancellationException) { assertFalse(error is TimeoutCancellationException) }
            assertEquals(0, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `negative AD availability cannot be applied to RD and background preparation stays single service`() = runBlocking {
        Harness().use { h ->
            val source = source().copy(debridCacheStatus = StreamDebridCacheStatus("alldebrid", "AllDebrid", StreamDebridCacheState.NOT_CACHED))
            h.rdReady()
            assertEquals(DirectDebridResolveResult.NotCached, h.resolver.resolve(source, null, null, allowFallback = false))
            assertEquals(0, h.ad.requestCount); assertEquals(0, h.rd.requestCount)
            assertTrue(withTimeout(10_000) { h.resolver.resolve(source, null, null) } is DirectDebridResolveResult.Success)
            assertEquals(0, h.ad.requestCount); assertEquals(5, h.rd.requestCount)
        }
    }

    @Test fun `signed URL and invalid hash cannot invoke any service or mint a playback grant`() = runBlocking {
        Harness().use { h ->
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source().copy(url = FILE), null, null))
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source().copy(infoHash = "bad", url = null), null, null))
            assertEquals(0, h.ad.requestCount); assertEquals(0, h.rd.requestCount)
            assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `credential mutation in flight during first service response aborts before any fallback`() = runBlocking {
        Harness().use { h ->
            h.ad.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    h.credentials.set(-1L)
                    return json("{}", 503)
                }
            }
            try { withTimeout(10_000) { h.resolver.resolve(source(), null, null) }; fail("Expected cancellation") }
            catch (error: CancellationException) { assertFalse(error is TimeoutCancellationException) }
            assertEquals(0, h.rd.requestCount)
            assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `caller cancellation cannot launch RD after shared first service work finishes`() = runBlocking {
        Harness().use { h ->
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":1}]}}""")
                .setBodyDelay(250, java.util.concurrent.TimeUnit.MILLISECONDS))
            val selection = launch { h.resolver.resolve(source(), null, null) }
            withTimeout(5000) { while (h.ad.requestCount < 2) delay(10) }
            selection.cancelAndJoin()
            delay(400)
            assertEquals(0, h.rd.requestCount)
            assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `policy rejected AD HTTP success is not cached granted or upgraded and RD HTTPS may succeed`() = runBlocking {
        Harness().use { h ->
            val rejected = "http://cdn.example.test/ad-file.mkv"
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":true}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"files":[{"n":"movie.mkv","s":999,"l":"https://alldebrid.example.test/hosted"}]}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"link":"$rejected","filename":"movie.mkv","filesize":999}}"""))
            h.rdReady()
            assertEquals(DirectDebridResolveResult.Success(FILE, "movie.mkv", 123L),
                withTimeout(10_000) { h.resolver.resolve(source(), null, null) })
            assertFalse(h.resolver.isPlaybackAuthorized(rejected))
            verify(exactly = 0) { h.grants.issue(rejected, any(), any()) }
            val cached = h.resolver.cachedPlayableStream(source(), null, null)!!
            assertEquals(FILE, cached.url); assertEquals(123L, cached.nativeFileMetadata?.selectedFileBytes)
            assertEquals(3, h.ad.requestCount); assertEquals(5, h.rd.requestCount)
        }
    }

    @Test fun `live datastore revision gates stop dispatch even when registry notifications are deliberately frozen`() = runBlocking {
        for (changeCredentials in listOf(false, true)) Harness().use { h ->
            val captured = h.grants.observe(1, mapOf("alldebrid" to "synthetic-ad-key", "realdebrid" to "synthetic-rd-key"), 1L, 1L)
            h.grants.bindLiveOrigin { ServicePlaybackOrigin(1, 1L, 1L) }
            h.ad.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/v4/magnet/upload") json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}""")
                    else {
                        // These are the actual mocked DataStore synchronous getters, not
                        // registry.observe(). No profile/settings Flow emission is delivered.
                        if (changeCredentials) h.credentials.incrementAndGet() else h.selection.addAndGet(2L)
                        assertTrue(h.grants.scopeIsCurrent(captured))
                        json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":1}]}}""")
                    }
            }
            try { withTimeout(10_000) { h.resolver.resolve(source(), null, null) }; fail("Expected stale operation cancellation") }
            catch (error: CancellationException) { assertFalse(error is TimeoutCancellationException) }
            assertEquals(2, h.ad.requestCount); assertEquals(0, h.rd.requestCount)
        }
    }

    private class Harness : AutoCloseable {
        val ad = MockWebServer(); val rd = MockWebServer()
        val profile = MutableStateFlow(1)
        val selection = AtomicLong(1L); val credentials = AtomicLong(1L)
        val settings = MutableStateFlow(DebridSettings(enabled = true, allDebridApiKey = "synthetic-ad-key",
            realDebridApiKey = "synthetic-rd-key", preferredResolverProviderId = "alldebrid"))
        val store = mockk<DebridSettingsDataStore>()
        val grants = spyk(ServicePlaybackGrantRegistry({ System.nanoTime() / 1_000_000 }, requireLiveOrigin = true))
        private val job = SupervisorJob()
        val resolver: DirectDebridResolver
        init {
            every { store.activeProfileId } returns profile
            every { store.activeProfileSelectionRevision } answers { selection.get() }
            every { store.activeCredentialRevision } answers { credentials.get() }
            every { store.settings } returns settings
            coEvery { store.credentialForProfile(any(), any()) } answers { settings.value.apiKeyFor(secondArg()) }
            grants.bindLiveOrigin { ServicePlaybackOrigin(profile.value, selection.get(), credentials.get()) }
            val moshi = Moshi.Builder().add(AllDebridMagnetCollectionAdapterFactory).addLast(KotlinJsonAdapterFactory()).build()
            fun retrofit(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            resolver = DirectDebridResolver(store, mockk(), RealDebridDirectDebridResolver(store,
                retrofit(rd).create(RealDebridApi::class.java), RealDebridFileSelector()),
                AllDebridDirectDebridResolver(store, AllDebridService(retrofit(ad).create(AllDebridApi::class.java))),
                mockk(), mockk(), CoroutineScope(job + Dispatchers.Unconfined), grants)
        }
        fun adProcessing() {
            ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}"""))
            ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":1}]}}"""))
        }
        fun rdReady() {
            rd.enqueue(json("""{"id":"synthetic-job"}"""))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/movie.mkv","bytes":123,"selected":0}]}"""))
            rd.enqueue(MockResponse().setResponseCode(204))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/movie.mkv","bytes":123,"selected":1}],"links":["https://real-debrid.example.test/selected"]}"""))
            rd.enqueue(json("""{"download":"$FILE","filename":"movie.mkv","filesize":123}"""))
        }
        override fun close() { job.cancel(); grants.clear(); ad.close(); rd.close() }
    }

    private fun explicit() = StreamClientResolve("debrid", HASH, 0, null, null, null, "movie.mkv", "movie",
        null, null, "Movie", null, null, "alldebrid", null, null, true)
    private fun source() = Stream("Synthetic", "Movie", null,
        "magnet:?xt=urn:btih:$HASH&tr=https://private-tracker.example.test/passkey", null, HASH, 0, null,
        StreamBehaviorHints(null, null, null, ProxyHeaders(mapOf("Authorization" to "provider-header"), null), filename = "movie.mkv"),
        "Synthetic", null)
    companion object {
        private const val HASH = "aabbccddeeff00112233445566778899aabbccdd"
        private const val FILE = "https://cdn.example.test/rd-file.mkv"
        private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    }
}
