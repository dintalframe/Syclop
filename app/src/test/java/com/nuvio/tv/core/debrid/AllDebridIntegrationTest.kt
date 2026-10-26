package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.cloud.AllDebridCloudLibraryProviderApi
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackResult
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AllDebridIntegrationTest {
    @Test
    fun `account validation uses bearer header with no key in URL`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"user":{"username":"demo","isPremium":true}}}"""))
            assertEquals("demo", service(server).user(TOKEN).username)
            val request = server.takeRequest()
            assertEquals("/v4/user", request.path)
            assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
            assertFalse(request.path.orEmpty().contains(TOKEN))
        }
    }

    @Test
    fun `API error payload cannot leak echoed token or source URL`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"error","error":{"code":"AUTH_BAD_APIKEY","message":"$TOKEN https://private.example/$TOKEN"}}"""))
            try {
                service(server).user(TOKEN)
                fail("Expected authentication failure")
            } catch (error: AllDebridApiException) {
                assertEquals("AllDebrid API key is invalid.", error.message)
                assertEquals(DirectDebridFailureReason.AUTH_EXPIRED, error.reason)
                assertFalse(error.toString().contains(TOKEN))
                assertFalse(error.toString().contains("private.example"))
            }
        }
    }

    @Test fun `HTTP rate limit retains retry after and emits no server message`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"error","error":{"code":"RATE_LIMIT","message":"$TOKEN"}}""")
                .setResponseCode(429).setHeader("Retry-After", "120"))
            val store = mockk<DebridSettingsDataStore>()
            every { store.settings } returns flowOf(DebridSettings(allDebridApiKey = TOKEN))
            val result = AllDebridDirectDebridResolver(store, service(server)).resolve(stream(), 1, 2) as DirectDebridResolveResult.Failure
            assertEquals(DirectDebridFailureReason.RATE_LIMITED, result.detail.reason)
            assertEquals(120L, result.detail.retryAfterSeconds)
            assertFalse(result.toString().contains(TOKEN))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `magnet resolve flattens v4 files tree and selects matching episode instead of largest file`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":true}]}}"""))
            server.enqueue(json(filesResponse))
            server.enqueue(json("""{"status":"success","data":{"link":"https://cdn.example/episode2.mkv","filename":"Show.S01E02.mkv","filesize":200}}"""))
            val dataStore = mockk<DebridSettingsDataStore>()
            every { dataStore.settings } returns flowOf(DebridSettings(allDebridApiKey = TOKEN))
            val result = AllDebridDirectDebridResolver(dataStore, service(server)).resolve(stream(), 1, 2)
            assertTrue(result is DirectDebridResolveResult.Success)
            result as DirectDebridResolveResult.Success
            assertEquals("https://cdn.example/episode2.mkv", result.url)
            assertEquals(200L, result.videoSize)
            assertEquals("/v4/magnet/upload", server.takeRequest().path)
            val files = server.takeRequest()
            assertEquals("/v4/magnet/files", files.path)
            assertTrue(files.body.readUtf8().contains("id%5B%5D=42"))
            val unlock = server.takeRequest()
            assertEquals("/v4/link/unlock", unlock.path)
            assertTrue(unlock.body.readUtf8().contains("episode2"))
        }
    }

    @Test
    fun `uncached magnet returns processing without file download or repeated polling`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}"""))
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"statusCode":1}]}}"""))
            val store = mockk<DebridSettingsDataStore>()
            every { store.settings } returns flowOf(DebridSettings(allDebridApiKey = TOKEN))
            assertEquals(DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.PROCESSING, "alldebrid")), AllDebridDirectDebridResolver(store, service(server)).resolve(stream(), 1, 2))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `HTTP hoster unlock returns official direct link`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"link":"https://cdn.example/file.mkv","filename":"file.mkv"}}"""))
            assertEquals("https://cdn.example/file.mkv", service(server).unlock(TOKEN, "https://host.example/file").link)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v4/link/unlock", request.path)
            assertFalse(request.path.orEmpty().contains("host.example"))
        }
    }

    @Test
    fun `delayed links use cancellable five second polling interval`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"filename":"file.mkv","delayed":7}}"""))
            server.enqueue(json("""{"status":"success","data":{"status":1}}"""))
            server.enqueue(json("""{"status":"success","data":{"status":2,"link":"https://cdn.example/file.mkv"}}"""))
            val before = testScheduler.currentTime
            assertEquals("https://cdn.example/file.mkv", service(server).unlock(TOKEN, "https://host.example/file").link)
            assertEquals(10_000L, testScheduler.currentTime - before)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `cloud lists ready torrent files and resolves chosen file with fresh link`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"filename":"Show","status":"Ready","statusCode":4,"size":900}]}}"""))
            server.enqueue(json(filesResponse))
            val cloud = AllDebridCloudLibraryProviderApi(service(server))
            val item = cloud.listItems(TOKEN).getOrThrow().single()
            assertEquals(2, item.playableFiles.size)
            assertTrue(item.files.all { it.playbackUrl == null })
            val chosen = item.files.single { it.name.endsWith("E02.mkv") }
            server.enqueue(json(filesResponse))
            server.enqueue(json("""{"status":"success","data":{"link":"https://cdn.example/fresh.mkv"}}"""))
            val playback = cloud.resolvePlayback(TOKEN, item, chosen)
            assertTrue(playback is CloudLibraryPlaybackResult.Success)
            assertEquals("https://cdn.example/fresh.mkv", (playback as CloudLibraryPlaybackResult.Success).url)
        }
    }

    @Test fun `single object status preserves native selected-file resolution and cached status checks`()=runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}"""))
            server.enqueue(json("""{"status":"success","data":{"magnets":{"id":42,"statusCode":4}}}"""))
            server.enqueue(json(filesResponse))
            server.enqueue(json("""{"status":"success","data":{"link":"https://cdn.example/episode2.mkv","filename":"Show.S01E02.mkv","filesize":200}}"""))
            val store=mockk<DebridSettingsDataStore>()
            every { store.settings } returns flowOf(DebridSettings(allDebridApiKey=TOKEN))
            val result=AllDebridDirectDebridResolver(store,service(server)).resolve(stream(),1,2)
            assertTrue(result is DirectDebridResolveResult.Success)
            assertEquals(200L,(result as DirectDebridResolveResult.Success).videoSize)
            assertEquals("/v4/magnet/upload",server.takeRequest().path)
            assertEquals("/v4.1/magnet/status",server.takeRequest().path)
            assertEquals("/v4/magnet/files",server.takeRequest().path)
            assertEquals("/v4/link/unlock",server.takeRequest().path)
        }
    }
    @Test fun `single object processing status cannot become a cached playback claim`()=runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}"""))
            server.enqueue(json("""{"status":"success","data":{"magnets":{"id":42,"statusCode":1}}}"""))
            val store=mockk<DebridSettingsDataStore>()
            every { store.settings } returns flowOf(DebridSettings(allDebridApiKey=TOKEN))
            assertEquals(DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.PROCESSING, "alldebrid")),AllDebridDirectDebridResolver(store,service(server)).resolve(stream(),1,2))
            assertEquals(2,server.requestCount)
        }
    }

    @Test fun `all documented processing codes return processing after exactly one status request`() = runTest {
        for (code in 0..3) assertReadinessResult("""{"magnets":{"id":42,"statusCode":$code}}""", DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.PROCESSING, "alldebrid")))
    }

    @Test fun `service error codes return error instead of a misleading cache miss`() = runTest {
        for (code in listOf(5, 12, 999)) assertReadinessResult("""{"magnets":{"id":42,"statusCode":$code}}""", DirectDebridResolveResult.Error)
    }

    @Test fun `missing empty mismatched and unknown status cannot become not cached`() = runTest {
        for (data in listOf("{}", """{"magnets":[]}""", """{"magnets":{"id":43,"statusCode":4}}""",
            """{"magnets":{"id":42}}""", """{"magnets":{"id":42,"statusCode":-1}}""")) {
            assertReadinessResult(data, DirectDebridResolveResult.Error)
        }
    }

    @Test fun `malformed status still fails without file requests retries or a cache claim`() = runTest {
        for (data in listOf("""{"magnets":null}""", """{"magnets":true}""", """{"magnets":{"id":42,"statusCode":"invalid"}}""")) {
            assertReadinessResult(data, DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.NETWORK_FAILURE, "alldebrid")))
        }
    }

    private suspend fun assertReadinessResult(data: String, expected: DirectDebridResolveResult) {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":42,"ready":false}]}}"""))
            server.enqueue(json("""{"status":"success","data":$data}"""))
            val store = mockk<DebridSettingsDataStore>()
            every { store.settings } returns flowOf(DebridSettings(allDebridApiKey = TOKEN))
            assertEquals(expected, AllDebridDirectDebridResolver(store, service(server)).resolve(stream(), 1, 2))
            assertEquals(2, server.requestCount)
            assertEquals("/v4/magnet/upload", server.takeRequest().path)
            assertEquals("/v4.1/magnet/status", server.takeRequest().path)
        }
    }

    private fun service(server: MockWebServer): AllDebridService = AllDebridService(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(com.nuvio.tv.data.remote.dto.AllDebridMagnetCollectionAdapterFactory).addLast(KotlinJsonAdapterFactory()).build()))
            .build().create(AllDebridApi::class.java)
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun stream() = Stream(
        name = "Show", title = "Episode 2", description = null, url = null, ytId = null,
        infoHash = HASH, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = "Test", addonLogo = null,
        clientResolve = StreamClientResolve(type = "debrid", infoHash = HASH, fileIdx = null,
            magnetUri = null, sources = null, torrentName = null, filename = null,
            mediaType = "series", mediaId = null, mediaOnlyId = null, title = "Show",
            season = 1, episode = 2, service = DebridProviders.ALL_DEBRID_ID,
            serviceIndex = null, serviceExtension = null, isCached = true)
    )

    private companion object {
        const val TOKEN = "test-secret-token"
        const val HASH = "0123456789012345678901234567890123456789"
        val filesResponse = """{"status":"success","data":{"magnets":[{"id":42,"files":[{"n":"Show","e":[{"n":"Show.S01E01.mkv","s":700,"l":"https://alldebrid.com/f/episode1"},{"n":"Show.S01E02.mkv","s":200,"l":"https://alldebrid.com/f/episode2"}]}]}]}}"""
    }
}
