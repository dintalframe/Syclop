package com.nuvio.tv.core.express

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.mal.MalMetadataService
import com.nuvio.tv.data.mal.MalSourceMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ExpressMetadataResolverTest {
    @Test
    fun `Cinemeta supplies verified title year and external ID while coordinates stay exact`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"meta":{"id":"tt123","imdb_id":"tt123","name":"Verified & Title","releaseInfo":"2024–"}}"""))
            val tmdb = mockk<TmdbService>()
            coEvery { tmdb.tmdbToImdb(999, "series") } returns "tt123"
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val result = ExpressMetadataResolver(ExpressHttpClient(client, true), tmdb, mockk())
                .resolve(ExpressMediaRequest("series", "tmdb:999:2:3", 2, 3))
            assertEquals("Verified & Title", result.title)
            assertEquals(2024, result.year)
            assertEquals("tt123", result.imdbId)
            assertEquals(2, result.season)
            assertEquals(3, result.episode)
            assertNull(result.absoluteNumber)
            assertEquals("/meta/series/tt123.json", server.takeRequest().path)
            coVerify(exactly = 1) { tmdb.tmdbToImdb(999, "series") }
        }
    }

    @Test
    fun `conflicting episode coordinates are rejected before issuing source requests`() = runTest {
        val resolver = ExpressMetadataResolver(ExpressHttpClient(), mockk(), mockk())
        try {
            resolver.resolve(ExpressMediaRequest("series", "tt123:1:29", 2, 1))
            fail("Expected conflict rejection")
        } catch (error: ExpressException) {
            assertTrue(error.message.orEmpty().contains("conflicts"))
        }
    }

    @Test
    fun `MAL title and record episode remain explicit without guessed IMDb or franchise offset`() = runTest {
        val mal = mockk<MalMetadataService>()
        coEvery { mal.sourceMetadata(42) } returns MalSourceMetadata(42, "Example Second Season", 2026, "tv", 10)
        val result = ExpressMetadataResolver(ExpressHttpClient(), mockk(), mal)
            .resolve(ExpressMediaRequest("series", "mal:42:1", 1, 1))
        assertEquals("Example Second Season", result.title)
        assertEquals(2026, result.year)
        assertNull(result.imdbId)
        assertNull(result.season)
        assertEquals(1, result.episode)
        assertEquals(1, result.absoluteNumber)
        assertTrue(result.anime)
        assertFalse(result.variables().containsKey("showImdbId"))
    }

    @Test
    fun `MAL movie metadata does not manufacture episode coordinates`() = runTest {
        val mal = mockk<MalMetadataService>()
        coEvery { mal.sourceMetadata(42) } returns MalSourceMetadata(42, "Verified Movie", 2020, "movie", 1)
        val result = ExpressMetadataResolver(ExpressHttpClient(), mockk(), mal)
            .resolve(ExpressMediaRequest("movie", "mal:42"))
        assertEquals("movie", result.type)
        assertNull(result.episode)
        assertNull(result.absoluteNumber)
    }

    @Test
    fun `catalog response for another IMDb record is never used as a title guess`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"meta":{"id":"tt999","name":"Wrong item","year":2020}}"""))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/meta")).build())
            }.build()
            try {
                ExpressMetadataResolver(ExpressHttpClient(client, true), mockk(), mockk())
                    .resolve(ExpressMediaRequest("movie", "tt123"))
                fail("Expected ID mismatch rejection")
            } catch (_: ExpressException) { }
        }
    }
}
