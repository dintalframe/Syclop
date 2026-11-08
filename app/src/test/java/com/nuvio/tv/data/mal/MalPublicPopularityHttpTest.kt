package com.nuvio.tv.data.mal

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MalPublicPopularityHttpTest {
    @Test fun `popular sends public popularity ranking while existing top keeps all ranking`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"data":[{"node":{"id":5114,"title":"Series","media_type":"tv"}},
                    {"node":{"id":40028,"title":"Movie","media_type":"movie"}},
                    {"node":{"id":5114,"title":"Duplicate series","media_type":"tv"}}],
                 "paging":{"next":"https://api.myanimelist.net/v2/anime/ranking?offset=200"}}
            """.trimIndent()))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[],\"paging\":{}}"))
            val api = MalApiClient(OkHttpClient(), server.url("/v2").toString(), server.url("/token").toString())
            val popular = api.popular(100, "syclop-test-client")
            assertEquals(listOf(5114L, 40028L), popular.items.map { it.id })
            assertEquals(listOf("tv", "movie"), popular.items.map { it.mediaType })
            assertTrue(popular.hasMore)
            val top = api.ranking(0, "syclop-test-client")
            assertTrue(top.items.isEmpty())
            assertFalse(top.hasMore)
            val popularityRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
            val topRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
            for (request in listOf(popularityRequest, topRequest)) {
                assertEquals("GET", request.method)
                assertEquals("/v2/anime/ranking", request.requestUrl!!.encodedPath)
                assertEquals("syclop-test-client", request.getHeader("X-MAL-CLIENT-ID"))
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
                assertEquals("100", request.requestUrl!!.queryParameter("limit"))
                assertFalse(request.requestUrl!!.queryParameter("fields").isNullOrBlank())
            }
            assertEquals("bypopularity", popularityRequest.requestUrl!!.queryParameter("ranking_type"))
            assertEquals("100", popularityRequest.requestUrl!!.queryParameter("offset"))
            assertEquals("all", topRequest.requestUrl!!.queryParameter("ranking_type"))
            assertEquals("0", topRequest.requestUrl!!.queryParameter("offset"))
            // Returned pagination links are observations only, not remotely followed URLs.
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `invalid public registration or negative popular offset never sends a request`() = runBlocking {
        MockWebServer().use { server ->
            val api = MalApiClient(OkHttpClient(), server.url("/v2").toString(), server.url("/token").toString())
            for ((offset, clientId) in listOf(-1 to "syclop-test-client", 0 to "", 0 to "bad client id")) {
                try {
                    api.popular(offset, clientId)
                    fail("Invalid popularity request reached the transport")
                } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }
}
