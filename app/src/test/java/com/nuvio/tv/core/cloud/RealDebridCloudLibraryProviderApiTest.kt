package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RealDebridCloudLibraryProviderApiTest {
    @Test
    fun `cloud listing pages through all jobs without querying pending job details`() = runTest {
        MockWebServer().use { server ->
            val firstPage = (1..100).joinToString(",", "[", "]") { """{"id":"$it","filename":"Job $it","status":"downloading","progress":10}""" }
            server.enqueue(json(firstPage).setHeader("X-Total-Count", "101"))
            server.enqueue(json("""[{"id":"101","filename":"Last job","status":"queued"}]""").setHeader("X-Total-Count", "101"))
            val items = provider(server).listItems(TOKEN).getOrThrow()
            assertEquals(101, items.size)
            assertTrue(items.all { it.files.isEmpty() })
            val first = server.takeRequest()
            assertEquals("/torrents?page=1&limit=100", first.path)
            assertEquals("Bearer $TOKEN", first.getHeader("Authorization"))
            assertFalse(first.path.orEmpty().contains(TOKEN))
            assertEquals("/torrents?page=2&limit=100", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `downloaded cloud job lists selected videos without storing authenticated links`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""[{"id":"job","filename":"Show","status":"downloaded"}]"""))
            server.enqueue(json("""{"id":"job","filename":"Show","status":"downloaded","progress":100,"files":[{"id":1,"path":"/Show/Episode1.mkv","bytes":100,"selected":1},{"id":2,"path":"/Show/Episode2.mkv","bytes":200,"selected":0},{"id":3,"path":"/readme.txt","selected":1}],"links":["https://rd.example/private"]}"""))
            val item = provider(server).listItems(TOKEN).getOrThrow().single()
            assertEquals(DebridProviders.REAL_DEBRID_ID, item.providerId)
            assertEquals(3, item.files.size)
            assertEquals("1", item.playableFiles.single().id)
            assertEquals(1f, item.progressFraction)
            assertTrue(item.files.all { it.playbackUrl == null })
            assertEquals("GET", server.takeRequest().method)
            assertEquals("/torrents/info/job", server.takeRequest().path)
        }
    }

    @Test
    fun `cloud playback refreshes sole selected file and unrestricts without changing cloud job`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"id":"job","status":"downloaded","files":[{"id":2,"path":"/Show/Show.S01E02.mkv","bytes":200,"selected":1}],"links":["https://rd.example/episode2"]}"""))
            server.enqueue(json("""{"download":"https://cdn.example/fresh.mkv","filename":"Show.S01E02.mkv","filesize":200}"""))
            val result = provider(server).resolvePlayback(TOKEN, item(), file())
            assertTrue(result is CloudLibraryPlaybackResult.Success)
            assertEquals("https://cdn.example/fresh.mkv", (result as CloudLibraryPlaybackResult.Success).url)
            assertEquals("GET", server.takeRequest().method)
            val unlock = server.takeRequest()
            assertEquals("POST", unlock.method)
            assertEquals("/unrestrict/link", unlock.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `multi-file cloud playback verifies filename rather than trusting link order`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(multiFileJob))
            // Reversed links: the index hint points to episode1, which must never be played.
            server.enqueue(json("""{"download":"https://cdn.example/wrong.mkv","filename":"Show.S01E01.mkv","filesize":100}"""))
            server.enqueue(json("""{"download":"https://cdn.example/right.mkv","filename":"Show.S01E02.mkv","filesize":200}"""))
            val result = provider(server).resolvePlayback(TOKEN, item(), file())
            assertTrue(result is CloudLibraryPlaybackResult.Success)
            assertEquals("https://cdn.example/right.mkv", (result as CloudLibraryPlaybackResult.Success).url)
            assertEquals(3, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            repeat(2) { assertEquals("/unrestrict/link", server.takeRequest().path) }
        }
    }

    @Test
    fun `duplicate cloud basenames cannot identify a selected file ambiguously`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"id":"job","status":"downloaded","files":[{"id":1,"path":"/a/Show.S01E02.mkv","selected":1},{"id":2,"path":"/Show/Show.S01E02.mkv","selected":1}],"links":["https://rd.example/a","https://rd.example/b"]}"""))
            repeat(2) { server.enqueue(json("""{"download":"https://cdn.example/ambiguous.mkv","filename":"Show.S01E02.mkv"}""")) }
            val result = provider(server).resolvePlayback(TOKEN, item(), file())
            assertTrue(result is CloudLibraryPlaybackResult.Failed)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `matching cloud filename with contradictory size is rejected`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(multiFileJob))
            repeat(2) { server.enqueue(json("""{"download":"https://cdn.example/wrong.mkv","filename":"Show.S01E02.mkv","filesize":999}""")) }
            assertTrue(provider(server).resolvePlayback(TOKEN, item(), file()) is CloudLibraryPlaybackResult.Failed)
        }
    }

    @Test
    fun `cloud errors never expose server text credentials or authenticated source URLs`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"error":"$TOKEN https://private.example/$TOKEN"}""").setResponseCode(401))
            val error = provider(server).listItems(TOKEN).exceptionOrNull()
            assertNotNull(error)
            assertFalse(error.toString().contains(TOKEN))
            assertFalse(error.toString().contains("private.example"))
        }
    }

    @Test
    fun `cloud browsing skips job removed after initial listing`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""[{"id":"removed","status":"downloaded"}]"""))
            server.enqueue(json("""{"error":"unknown_resource"}""").setResponseCode(404))
            assertTrue(provider(server).listItems(TOKEN).getOrThrow().isEmpty())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `cloud cancellation propagates without further API calls`() = runTest {
        val api = mockk<RealDebridApi>()
        coEvery { api.getTorrentInfo(any(), any()) } throws CancellationException("cancelled")
        try {
            RealDebridCloudLibraryProviderApi(api).resolvePlayback(TOKEN, item(), file())
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    private fun provider(server: MockWebServer) = RealDebridCloudLibraryProviderApi(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
            .build().create(RealDebridApi::class.java)
    )

    private fun item() = CloudLibraryItem(providerId = DebridProviders.REAL_DEBRID_ID,
        providerName = "Real-Debrid", id = "job", type = CloudLibraryItemType.Torrent, name = "Show")

    private fun file() = CloudLibraryFile(id = "2", name = "Show/Show.S01E02.mkv", sizeBytes = 200)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TOKEN = "test-private-token"
        val multiFileJob = """{"id":"job","status":"downloaded","files":[{"id":1,"path":"/Show/Show.S01E01.mkv","bytes":100,"selected":1},{"id":2,"path":"/Show/Show.S01E02.mkv","bytes":200,"selected":1}],"links":["https://rd.example/episode2","https://rd.example/episode1"]}"""
    }
}
