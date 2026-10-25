package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.core.anime.NativeAnimeMappingCodec
import com.nuvio.tv.core.streams.ServicePlaybackGrantRegistry
import com.nuvio.tv.core.streams.ServicePlaybackOrigin
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.domain.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Actual resolver, RD HTTP/file selection, private grants and proof-bound caches. */
class NativeMalDebridMappingIntegrationTest {
    @Test fun `MAL sequel UI episode one selects actual TVDB S2E1 and scoped cache never original S1E1`() = runBlocking {
        Harness().use { h ->
            h.readyPack()
            val result = withTimeout(10_000) { h.resolver.resolve(source(), 1, 1, nativeVideoId = MAL, nativeMediaType = "series") }
            assertEquals(DirectDebridResolveResult.Success(FILE, "Frieren.S02E01.mkv", 222L), result)
            assertTrue(h.resolver.isPlaybackAuthorized(FILE, 1))
            assertEquals("realdebrid", h.settings.value.activeResolverProviderId)
            val requests = List(5) { h.rd.takeRequest() }
            assertEquals("/torrents/selectFiles/synthetic-job", requests[2].path)
            assertEquals("files=2", requests[2].body.readUtf8())
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer synthetic-rd-key" })
            val body = java.net.URLDecoder.decode(requests[0].body.readUtf8(), "UTF-8")
            assertEquals("magnet=magnet:?xt=urn:btih:$HASH", body)
            assertFalse(body.contains("private-tracker")); assertFalse(body.contains("provider-header"))
            val cached = h.resolver.cachedPlayableStream(source(), 1, 1, MAL, "series")!!
            assertEquals(FILE, cached.url); assertEquals(222L, cached.nativeFileMetadata?.selectedFileBytes)
            assertNull(h.resolver.cachedPlayableStream(source(), 1, 1, "mal:52991:1", "series"))
            assertNull(h.resolver.cachedPlayableStream(source(), 1, 1))
            val facts = DebridBitrateMetadataService(h.resolver, h.store).enrichKnownFiles(listOf(source()),
                1_000L, StreamDurationOrigin.CATALOG_EPISODE, 1, 1, 1, MAL, "series").single().second
            assertEquals(222L, facts.nativeFileMetadata?.selectedFileBytes)
            assertEquals(source().url, facts.url)
            assertTrue(DebridBitrateMetadataService(h.resolver, h.store).enrichKnownFiles(listOf(cached),
                1_000L, StreamDurationOrigin.CATALOG_EPISODE, 1, 1, 1, "mal:52991:1", "series").isEmpty())
            assertEquals(5, h.rd.requestCount)
            coVerify(exactly = 1) { h.mappings.proof(MAL, any()) }
        }
    }

    @Test fun `wrong explicit filename or file index cannot override actual sequel proof`() = runBlocking {
        for ((wrong, expectedRequests) in listOf(source().copy(fileIdx = 0) to 2,
            source().copy(behaviorHints = source().behaviorHints!!.copy(filename = "Frieren.S01E01.mkv")) to 0)) {
            Harness().use { h ->
                if (expectedRequests > 0) h.beforePack()
                assertEquals(DirectDebridResolveResult.Stale, withTimeout(10_000) {
                    h.resolver.resolve(wrong, 1, 1, nativeVideoId = MAL, nativeMediaType = "series")
                })
                assertEquals(expectedRequests, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
            }
        }
    }

    @Test fun `missing mapping stops before any RD job or grant`() = runBlocking {
        Harness().use { h ->
            coEvery { h.mappings.proof(MAL, any()) } returns null
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source(), 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            assertEquals(0, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `live selection or credential changes during mapping stop before service HTTP with frozen observer`() = runBlocking {
        for (credentialChange in listOf(false, true)) Harness().use { h ->
            coEvery { h.mappings.proof(MAL, any()) } coAnswers {
                if (credentialChange) h.credentials.incrementAndGet() else h.selection.addAndGet(2L)
                h.proof(59978)
            }
            try { h.resolver.resolve(source(), 1, 1, nativeVideoId = MAL, nativeMediaType = "series"); fail("Expected stale cancellation") }
            catch (error: CancellationException) { assertFalse(error is TimeoutCancellationException) }
            assertEquals(0, h.rd.requestCount)
        }
    }

    @Test fun `native URL with old generic grant cannot claim MAL provenance from provider fields`() = runBlocking {
        Harness().use { h ->
            val binding = h.grants.observe(1, mapOf("realdebrid" to "synthetic-rd-key"), 1L, 1L)
            assertTrue(h.grants.issue(FILE, "realdebrid", binding))
            val forged = source().copy(url = FILE, behaviorHints = source().behaviorHints!!.copy(filename = "Frieren.S02E01.mkv"))
            assertEquals(DirectDebridPlayableResult.Stale, h.resolver.resolveToPlayableStream(forged, 1, 1,
                nativeVideoId = MAL, nativeMediaType = "series"))
            assertFalse(h.resolver.isPlaybackAuthorized(FILE, 1, MAL, 1, 1, "series"))
            assertEquals(0, h.rd.requestCount)
        }
    }

    @Test fun `private verified native URL is reusable only for exact MAL proof and current account`() = runBlocking {
        Harness().use { h ->
            h.readyPack()
            val ready = h.resolver.resolveToPlayableStream(source(), 1, 1, nativeVideoId = MAL, nativeMediaType = "series") as DirectDebridPlayableResult.Success
            assertTrue(h.resolver.resolveToPlayableStream(ready.stream, 1, 1, nativeVideoId = MAL, nativeMediaType = "series") is DirectDebridPlayableResult.Success)
            assertTrue(h.resolver.isPlaybackAuthorized(FILE, 1, MAL, 1, 1, "series"))
            assertFalse(h.resolver.isPlaybackAuthorized(FILE, 1, "mal:52991:1", 1, 1, "series"))
            assertEquals(DirectDebridPlayableResult.Stale, h.resolver.resolveToPlayableStream(ready.stream, 1, 1,
                nativeVideoId = "mal:52991:1", nativeMediaType = "series"))
            h.credentials.incrementAndGet()
            assertEquals(DirectDebridPlayableResult.Stale, h.resolver.resolveToPlayableStream(ready.stream, 1, 1,
                nativeVideoId = MAL, nativeMediaType = "series"))
            assertEquals(5, h.rd.requestCount)
        }
    }

    @Test fun `plain native MAL movie keeps movie selection without episode mapping`() = runBlocking {
        Harness().use { h ->
            h.readyMovie()
            assertTrue(h.resolver.resolve(source(), null, null, nativeVideoId = "mal:59978", nativeMediaType = "movie") is DirectDebridResolveResult.Success)
            coVerify(exactly = 0) { h.mappings.proof(any(), any()) }
            assertEquals(5, h.rd.requestCount)
        }
    }

    @Test fun `native MAL relative identity rejects imported TVDB UI coordinates before lookup`() = runBlocking {
        Harness().use { h ->
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source(), 2, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source(), 1, 29, nativeVideoId = MAL, nativeMediaType = "series"))
            coVerify(exactly = 0) { h.mappings.proof(any(), any()) }
            assertEquals(0, h.rd.requestCount)
        }
    }

    @Test fun `cache only metadata never starts mapping or cloud requests when proof absent`() = runBlocking {
        Harness().use { h ->
            every { h.mappings.cachedProof(MAL) } returns null
            assertNull(h.resolver.cachedPlayableStream(source(), 1, 1, MAL, "series"))
            coVerify(exactly = 0) { h.mappings.proof(any(), any()) }
            assertEquals(0, h.rd.requestCount)
        }
    }

    @Test fun `reused RD job changed file path after selection cannot borrow the earlier matching ID`() = runBlocking {
        Harness().use { h ->
            h.beforePack(); h.rd.enqueue(MockResponse().setResponseCode(204))
            h.rd.enqueue(json("""{"status":"downloaded","files":[{"id":2,"path":"/Frieren.S01E01.mkv","bytes":222,"selected":1}],"links":["https://real-debrid.example.test/selected"]}"""))
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source(), 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            assertEquals(4, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `final unlock filename contradicting selected episode is never cached or granted`() = runBlocking {
        Harness().use { h ->
            h.readyPack(unlockFilename = "Frieren.S01E01.mkv")
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source(), 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            assertNull(h.resolver.cachedPlayableStream(source(), 1, 1, MAL, "series"))
            assertEquals(5, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `unrelated declared show with matching coordinates cannot create any RD job`() = runBlocking {
        Harness().use { h ->
            val unrelated = source().copy(title = "IT.Welcome.to.Derry.S02E01.29.Neibolt.Street.mkv")
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(unrelated, 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            assertNull(h.resolver.cachedPlayableStream(unrelated, 1, 1, MAL, "series"))
            assertEquals(0, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `spoofed matching row cannot select another shows actual S2E1 file`() = runBlocking {
        Harness().use { h ->
            h.rd.enqueue(json("""{"id":"synthetic-job"}"""))
            h.rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/Night.on.Earth.S02E01.mkv","bytes":222,"selected":0}]}"""))
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(source(), 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            assertEquals(2, h.rd.requestCount); assertFalse(h.resolver.isPlaybackAuthorized(FILE))
        }
    }

    @Test fun `valid native MAL cache cannot bypass changed raw hints blank hints or conflicting file indexes`() = runBlocking {
        Harness().use { h ->
            h.readyPack()
            val contract = StreamClientResolve(type = "debrid", infoHash = HASH, fileIdx = 1,
                magnetUri = "magnet:?xt=urn:btih:$HASH", sources = null, torrentName = "Frieren pack",
                filename = "Frieren.S02E01.mkv", mediaType = "series", mediaId = MAL, mediaOnlyId = "mal:59978",
                title = "Frieren", season = 1, episode = 1, service = "realdebrid", serviceIndex = null,
                serviceExtension = null, isCached = true, stream = StreamClientResolveStream(
                    StreamClientResolveRaw("Frieren pack", "Frieren.S02E01.mkv", null, null, null, null, null, null)))
            val nested = contract.stream!!
            val raw = nested.raw!!
            val valid = source().copy(url = null, fileIdx = 1, clientResolve = contract)
            assertTrue(h.resolver.resolve(valid, 1, 1, nativeVideoId = MAL, nativeMediaType = "series") is DirectDebridResolveResult.Success)
            for (rawFilename in listOf("Other.Show.S02E01.mkv", "Frieren.S01E01.mkv", "Frieren.S02E01.EncodeB.mkv", "")) {
                val changed = valid.copy(clientResolve = contract.copy(stream = nested.copy(raw = raw.copy(filename = rawFilename))))
                assertEquals(rawFilename, DirectDebridResolveResult.Stale,
                    h.resolver.resolve(changed, 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
                assertNull(h.resolver.cachedPlayableStream(changed, 1, 1, MAL, "series"))
            }
            val blankHint = valid.copy(behaviorHints = valid.behaviorHints!!.copy(filename = " "))
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(blankHint, 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            val conflictingIndex = valid.copy(clientResolve = contract.copy(fileIdx = 0))
            assertEquals(DirectDebridResolveResult.Stale, h.resolver.resolve(conflictingIndex, 1, 1, nativeVideoId = MAL, nativeMediaType = "series"))
            val missingRawHint = valid.copy(clientResolve = contract.copy(stream = nested.copy(raw = raw.copy(filename = null))))
            assertNull(h.resolver.cachedPlayableStream(missingRawHint, 1, 1, MAL, "series"))
            assertEquals(5, h.rd.requestCount)
            verify(exactly = 1) { h.grants.issue(FILE, "realdebrid", any()) }
        }
    }

    private class Harness : AutoCloseable {
        val rd = MockWebServer()
        val selection = AtomicLong(1L); val credentials = AtomicLong(1L)
        val profile = MutableStateFlow(1)
        val settings = MutableStateFlow(DebridSettings(enabled = true, realDebridApiKey = "synthetic-rd-key", preferredResolverProviderId = "realdebrid"))
        val store = mockk<DebridSettingsDataStore>()
        val mappings = mockk<NativeAnimeEpisodeMappings>()
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
            coEvery { mappings.proof(any(), any()) } answers { proof(firstArg<String>().split(':')[1].toLong()) }
            every { mappings.cachedProof(any()) } answers { proof(firstArg<String>().split(':')[1].toLong()) }
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val api = Retrofit.Builder().baseUrl(rd.url("/")).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(RealDebridApi::class.java)
            resolver = DirectDebridResolver(store, mockk(), RealDebridDirectDebridResolver(store, api, RealDebridFileSelector()),
                mockk(), mockk(), mockk(), CoroutineScope(job + Dispatchers.Unconfined), grants, mappings)
        }
        fun proof(id: Long) = NativeAnimeMappingCodec.parse("""{"titles":{"en":"Frieren"},"mappings":{"mal_id":$id,"thetvdb_id":424536},"episodeCount":10,"episodes":{"1":{"tvdbShowId":424536,"tvdbId":10719109,"seasonNumber":${if(id == 59978L) 2 else 1},"episodeNumber":1,"absoluteEpisodeNumber":${if(id == 59978L) 29 else 1},"episode":"1"}}}""", id)!!.episodes.getValue(1)
        fun beforePack() {
            rd.enqueue(json("""{"id":"synthetic-job"}"""))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/Frieren.S01E01.mkv","bytes":111,"selected":0},{"id":2,"path":"/Frieren.S02E01.mkv","bytes":222,"selected":0}]}"""))
        }
        fun readyPack(unlockFilename: String = "Frieren.S02E01.mkv") {
            beforePack(); rd.enqueue(MockResponse().setResponseCode(204))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/Frieren.S01E01.mkv","bytes":111,"selected":0},{"id":2,"path":"/Frieren.S02E01.mkv","bytes":222,"selected":1}],"links":["https://real-debrid.example.test/selected"]}"""))
            rd.enqueue(json("""{"download":"$FILE","filename":"$unlockFilename","filesize":222}"""))
        }
        fun readyMovie() {
            rd.enqueue(json("""{"id":"synthetic-job"}"""))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/movie.mkv","bytes":222,"selected":0}]}"""))
            rd.enqueue(MockResponse().setResponseCode(204))
            rd.enqueue(json("""{"status":"downloaded","files":[{"id":1,"path":"/movie.mkv","bytes":222,"selected":1}],"links":["https://real-debrid.example.test/selected"]}"""))
            rd.enqueue(json("""{"download":"$FILE","filename":"movie.mkv","filesize":222}"""))
        }
        override fun close() { job.cancel(); grants.clear(); rd.close() }
    }
    private fun source() = Stream("Synthetic", "Frieren pack", null,
        "magnet:?xt=urn:btih:$HASH&tr=https://private-tracker.example.test/passkey", null, HASH, null, null,
        StreamBehaviorHints(null, null, null, ProxyHeaders(mapOf("Authorization" to "provider-header"), null)), "Synthetic", null)
    private companion object {
        const val MAL = "mal:59978:1"
        const val HASH = "aabbccddeeff00112233445566778899aabbccdd"
        const val FILE = "https://cdn.example.test/selected.mkv"
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
