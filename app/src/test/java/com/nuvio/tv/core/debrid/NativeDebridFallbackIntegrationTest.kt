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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    @Test fun `library ready selection bypasses preferred unknown account and rechecks before granting`() = runBlocking {
        Harness().use { h ->
            val detail = """{"id":"existing","hash":"$HASH","status":"downloaded","files":[{"id":1,"path":"/movie.mkv","bytes":123,"selected":1}],"links":["https://real-debrid.example.test/selected"]}"""
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[]}}"""))
            h.rd.enqueue(json("""[{"id":"existing","hash":"$HASH","status":"downloaded"}]"""))
            h.rd.enqueue(json(detail))
            val request = NativeDebridLibraryRequest("movie", "tt1234", null, null)
            val ready = h.availability.annotateCachedAvailability(listOf(AddonStreams("Synthetic", null, listOf(source()))),
                h.settings.value, request).single().streams.single()
            assertNotNull(ready.debridCacheStatus?.libraryFile)
            assertEquals(2, h.rd.requestCount)
            assertEquals(1, h.ad.requestCount)

            h.rd.enqueue(json(detail))
            h.rd.enqueue(json("""{"download":"$FILE","filename":"movie.mkv","filesize":123}"""))
            assertEquals(DirectDebridResolveResult.Success(FILE, "movie.mkv", 123L, providerId = "realdebrid"),
                h.resolver.resolve(ready, null, null, nativeVideoId = request.videoId, nativeMediaType = "movie"))
            assertTrue(h.resolver.isPlaybackAuthorized(FILE, 1))
            assertNull(h.resolver.cachedPlayableStream(ready, null, null))
            assertEquals(1, h.ad.requestCount)
            assertTrue(h.rd.takeRequest().path.orEmpty().startsWith("/torrents?page=1&limit="))
            assertEquals("/torrents/info/existing", h.rd.takeRequest().path)
            assertEquals("/torrents/info/existing", h.rd.takeRequest().path)
            assertEquals("/unrestrict/link", h.rd.takeRequest().path)

            h.rd.enqueue(json(detail.replace(HASH, "a".repeat(40))))
            val failure = h.resolver.resolve(ready, null, null)
            assertEquals(DirectDebridFailureReason.UNAVAILABLE, (failure as DirectDebridResolveResult.Failure).detail.reason)
            assertEquals(5, h.rd.requestCount)
            assertEquals(1, h.ad.requestCount)
            h.credentials.incrementAndGet()
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(ready, null, null))
            assertEquals(5, h.rd.requestCount)
            assertEquals(1, h.ad.requestCount)
        }
    }

    @Test fun `two verified libraries fall back sequentially when the preferred item is lost or rate limited`() = runBlocking {
        for (status in listOf(404, 429)) Harness().use { h ->
            val rdDetail = """{"id":"existing","hash":"$HASH","status":"downloaded","files":[{"id":1,"path":"/movie.mkv","bytes":123,"selected":1}],"links":["https://real-debrid.example.test/selected"]}"""
            val adFiles = """[{"n":"movie.mkv","s":123,"l":"https://alldebrid.example.test/selected"}]"""
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"hash":"$HASH","statusCode":4}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"files":$adFiles}]}}"""))
            h.rd.enqueue(json("""[{"id":"existing","hash":"$HASH","status":"downloaded"}]"""))
            h.rd.enqueue(json(rdDetail))
            val request = NativeDebridLibraryRequest("movie", "tt1234", null, null)
            val ready = h.availability.annotateCachedAvailability(listOf(AddonStreams("Synthetic", null, listOf(source()))),
                h.settings.value, request).single().streams.single()
            assertEquals(setOf("realdebrid", "alldebrid"), ready.nativeDebridAvailability
                .filter { it.libraryFile != null }.map { it.providerId }.toSet())

            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            h.ad.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.complete(Unit)
                    release.await(4, TimeUnit.SECONDS)
                    return json("{}", status).setHeader("Retry-After", "60")
                }
            }
            h.rd.enqueue(json(rdDetail))
            h.rd.enqueue(json("""{"download":"$FILE","filename":"movie.mkv","filesize":123}"""))
            val attempts = mutableListOf<String>()
            val selection = async { h.resolver.resolve(ready, null, null, onProviderAttempt = { attempts.add(it) }) }
            try {
                withTimeout(4_000) { entered.await() }
                delay(350)
                assertEquals(2, h.rd.requestCount)
            } finally {
                release.countDown()
            }
            assertEquals(DirectDebridResolveResult.Success(FILE, "movie.mkv", 123L, providerId = "realdebrid"),
                withTimeout(5_000) { selection.await() })
            assertEquals(listOf("alldebrid", "realdebrid"), attempts)
            assertTrue(h.resolver.isPlaybackAuthorized(FILE, 1))
            assertEquals(3, h.ad.requestCount)
            assertEquals(4, h.rd.requestCount)
            assertEquals(listOf("/v4.1/magnet/status", "/v4/magnet/files", "/v4.1/magnet/status"),
                List(3) { h.ad.takeRequest().path })
            assertEquals(listOf("/torrents/info/existing", "/unrestrict/link"),
                List(4) { h.rd.takeRequest().path }.takeLast(2))
        }
    }

    @Test fun `AllDebrid recalls a selected upload hash when the read-only status omits it`() = runBlocking {
        Harness().use { h ->
            h.settings.value = h.settings.value.copy(connectedServiceFallbackEnabled = false)
            val files = """[{"n":"movie.mkv","s":123,"l":"https://alldebrid.example.test/selected"}]"""
            val download = """{"status":"success","data":{"link":"$FILE","filename":"movie.mkv","filesize":123}}"""
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"hash":"$HASH","ready":true}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"files":$files}]}}"""))
            h.ad.enqueue(json(download))
            assertTrue(h.resolver.resolve(source(), null, null) is DirectDebridResolveResult.Success)

            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":4}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":"42","files":$files}]}}"""))
            val request = NativeDebridLibraryRequest("movie", "tt1234", null, null)
            val ready = h.availability.annotateCachedAvailability(listOf(AddonStreams("Synthetic", null, listOf(source()))),
                h.settings.value, request).single().streams.single()
            assertEquals("42", ready.debridCacheStatus?.libraryFile?.torrentId)
            assertEquals(5, h.ad.requestCount)

            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":4,"files":$files}]}}"""))
            h.ad.enqueue(json(download))
            assertEquals(DirectDebridResolveResult.Success(FILE, "movie.mkv", 123L, providerId = "alldebrid"),
                h.resolver.resolve(ready, null, null, nativeVideoId = request.videoId, nativeMediaType = "movie"))
            assertEquals(0, h.rd.requestCount)
            assertEquals(7, h.ad.requestCount)
            assertEquals(listOf("/v4/magnet/upload", "/v4/magnet/files", "/v4/link/unlock",
                "/v4.1/magnet/status", "/v4/magnet/files", "/v4.1/magnet/status", "/v4/link/unlock"),
                List(7) { h.ad.takeRequest().path })
        }
    }

    @Test fun `broadcast ordinal resolves only for its exact episode and never shares the regular cache`() = runBlocking {
        Harness().use { h ->
            h.settings.value = h.settings.value.copy(preferredResolverProviderId = "realdebrid")
            val filename = "Aşk-ı Memnu 39. Bölüm.mkv"
            val proof = TurkishBroadcastEpisode("tt1286677:2:1", 2, 1, 39, "Aşk-ı Memnu", 2008)
            val plain = source().copy(behaviorHints = source().behaviorHints?.copy(filename = filename))
            val selected = plain.copy(nativeTurkishBroadcastEpisode = proof)

            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(selected, 2, 1,
                allowFallback = false, nativeVideoId = "tt1286677:2:2", nativeMediaType = "series"))
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(selected, 2, 2,
                allowFallback = false, nativeVideoId = proof.videoId, nativeMediaType = "series"))
            assertEquals(0, h.rd.requestCount)

            h.rdReady(filename)
            val result = h.resolver.resolve(selected, 2, 1, allowFallback = false,
                nativeVideoId = proof.videoId, nativeMediaType = "series")
            assertEquals(DirectDebridResolveResult.Success(FILE, filename, 123L, providerId = "realdebrid"), result)
            assertEquals(FILE, h.resolver.cachedPlayableStream(selected, 2, 1, proof.videoId, "series")?.url)
            assertNull(h.resolver.cachedPlayableStream(plain, 2, 1, proof.videoId, "series"))
            assertNull(h.resolver.cachedPlayableStream(selected, 2, 1, "tt1286677:2:2", "series"))
            val changedProof = TurkishBroadcastEpisode(proof.videoId, 2, 1, 40, "Aşk-ı Memnu", 2008)
            assertNull(h.resolver.cachedPlayableStream(selected.copy(nativeTurkishBroadcastEpisode = changedProof),
                2, 1, proof.videoId, "series"))
            assertTrue(h.resolver.isPlaybackAuthorized(FILE, 1, proof.videoId, 2, 1, "series"))
            assertFalse(h.resolver.isPlaybackAuthorized(FILE, 1, "tt1286677:2:2", 2, 1, "series"))
            assertEquals(5, h.rd.requestCount)
        }
    }

    @Test fun `broadcast ordinal rejects a conflicting selected or unrestricted service filename`() = runBlocking {
        for (changedSelectedFile in listOf(true, false)) Harness().use { h ->
            h.settings.value = h.settings.value.copy(preferredResolverProviderId = "realdebrid")
            val filename = "Aşk-ı Memnu 39. Bölüm.mkv"
            val conflicting = "Aşk-ı Memnu 40. Bölüm.mkv"
            val proof = TurkishBroadcastEpisode("tt1286677:2:1", 2, 1, 39, "Aşk-ı Memnu", 2008)
            val selected = source().copy(behaviorHints = source().behaviorHints?.copy(filename = filename),
                nativeTurkishBroadcastEpisode = proof)
            h.rdReady(filename, selectedFilename = if (changedSelectedFile) conflicting else filename,
                finalFilename = if (changedSelectedFile) filename else conflicting)

            assertEquals(wrongDebridFile("realdebrid"), h.resolver.resolve(selected, 2, 1,
                allowFallback = false, nativeVideoId = proof.videoId, nativeMediaType = "series"))
            assertFalse(h.resolver.isPlaybackAuthorized(FILE, 1, proof.videoId, 2, 1, "series"))
            assertEquals(if (changedSelectedFile) 4 else 5, h.rd.requestCount)
        }
    }

    @Test fun `neutral hash race keeps separate service auth and actual RD grant metadata cache`() = runBlocking {
        Harness().use { h ->
            h.adProcessing(); h.rdReady()
            val attempts = mutableListOf<String>()
            val result = withTimeout(10_000) { h.resolver.resolve(source(), null, null, onProviderAttempt = { attempts += it }) }
            assertEquals(DirectDebridResolveResult.Success(FILE, "movie.mkv", 123L, providerId = "realdebrid"), result)
            assertEquals(setOf("alldebrid", "realdebrid"), attempts.toSet())
            assertEquals(2, attempts.size)
            assertEquals("alldebrid", h.settings.value.activeResolverProviderId)
            assertTrue(h.resolver.isPlaybackAuthorized(FILE, 1))
            verify(exactly = 1) { h.grants.issue(FILE, "realdebrid", any()) }
            verify(exactly = 0) { h.grants.issue(FILE, "alldebrid", any()) }
            val cached = h.resolver.cachedPlayableStream(source(), null, null)!!
            assertEquals(FILE, cached.getStreamUrl())
            assertEquals("realdebrid", cached.nativeResolvedProviderId)
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
            assertEquals(DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.PROCESSING, "alldebrid")), withTimeout(10_000) {
                h.resolver.resolve(source().copy(url = null, clientResolve = explicit()), null, null)
            })
            assertEquals(2, h.ad.requestCount); assertEquals(0, h.rd.requestCount)
        }
    }

    @Test fun `explicit connected issuer resolves alone even when another service is preferred`() = runBlocking {
        Harness().use { h ->
            h.settings.value = h.settings.value.copy(preferredResolverProviderId = "realdebrid")
            h.adProcessing()
            val result = h.resolver.resolve(source().copy(url = null, clientResolve = explicit()), null, null)
            assertEquals(DirectDebridResolveResult.Failure(DirectDebridFailure(
                DirectDebridFailureReason.PROCESSING, "alldebrid")), result)
            assertEquals(2, h.ad.requestCount)
            assertEquals(0, h.rd.requestCount)
            assertEquals("realdebrid", h.settings.value.activeResolverProviderId)
        }
    }

    @Test fun `ambiguous source file selection is terminal instead of silently choosing another service`() = runBlocking {
        Harness().use { h ->
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":true}]}}"""))
            h.ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"files":[]}]}}"""))
            assertEquals(wrongDebridFile("alldebrid"), withTimeout(10_000) {
                h.resolver.resolve(source().copy(url = null, clientResolve = explicit()), null, null)
            })
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
            try { withTimeout(10_000) { h.resolver.resolve(source().copy(url = null, clientResolve = explicit()), null, null) }; fail("Expected cancellation") }
            catch (error: CancellationException) { assertFalse(error is TimeoutCancellationException) }
            assertEquals(0, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `negative AD availability cannot be applied to RD and background preparation stays single service`() = runBlocking {
        Harness().use { h ->
            val now = System.currentTimeMillis()
            val source = source().copy(nativeDebridAvailability = listOf(StreamDebridCacheStatus("alldebrid", "AllDebrid",
                StreamDebridCacheState.NOT_CACHED, nativeScope = StreamAvailabilityScope(1, 1L, 1L), infoHash = HASH,
                checkedAtMs = now, expiresAtMs = now + 10_000L)))
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

    @Test fun `legacy provider cache claims cannot skip a native service resolution`() = runBlocking {
        Harness().use { h ->
            h.adProcessing(); h.rdReady()
            val claimed = source().copy(debridCacheStatus = StreamDebridCacheStatus("alldebrid", "AllDebrid", StreamDebridCacheState.NOT_CACHED))
            assertTrue(withTimeout(10_000) { h.resolver.resolve(claimed, null, null) } is DirectDebridResolveResult.Success)
            assertEquals(2, h.ad.requestCount)
            assertEquals(5, h.rd.requestCount)
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
            try { withTimeout(10_000) { h.resolver.resolve(source().copy(url = null, clientResolve = explicit()), null, null) }; fail("Expected cancellation") }
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
            val selection = launch { h.resolver.resolve(source(), null, null, allowFallback = false) }
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
            assertEquals(DirectDebridResolveResult.Success(FILE, "movie.mkv", 123L, providerId = "realdebrid"),
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
            try { withTimeout(10_000) { h.resolver.resolve(source().copy(url = null, clientResolve = explicit()), null, null) }; fail("Expected stale operation cancellation") }
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
        val availability: LocalDebridAvailabilityService
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
            val rdApi = retrofit(rd).create(RealDebridApi::class.java)
            val adApi = retrofit(ad).create(AllDebridApi::class.java)
            val adService = AllDebridService(adApi)
            val library = NativeDebridAccountLibrary(rdApi, adApi, adService)
            val cache = mockk<LocalDebridService>()
            availability = LocalDebridAvailabilityService(store, cache, System::currentTimeMillis, library)
            resolver = DirectDebridResolver(store, mockk(), RealDebridDirectDebridResolver(store, rdApi, RealDebridFileSelector()),
                AllDebridDirectDebridResolver(store, adService),
                mockk(), cache, CoroutineScope(job + Dispatchers.Unconfined), grants, accountLibrary = library)
        }
        fun adProcessing() {
            ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}"""))
            ad.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":1}]}}"""))
        }
        fun rdReady(filename: String = "movie.mkv", selectedFilename: String = filename, finalFilename: String = filename) {
            rd.enqueue(json("""{"id":"synthetic-job"}""").setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/$filename","bytes":123,"selected":0}]}"""))
            rd.enqueue(MockResponse().setResponseCode(204))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/$selectedFilename","bytes":123,"selected":1}],"links":["https://real-debrid.example.test/selected"]}"""))
            rd.enqueue(json("""{"download":"$FILE","filename":"$finalFilename","filesize":123}"""))
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
