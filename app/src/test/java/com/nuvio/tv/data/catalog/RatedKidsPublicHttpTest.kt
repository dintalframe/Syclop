package com.nuvio.tv.data.catalog

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.repository.TraktAuthService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RatedKidsPublicHttpTest {
    @Test fun `public movie and show filters use bounded batch requests without account authorization or body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""[{"title":"Movie fixture","ids":{"imdb":"tt1234567"},"certification":"G","genres":["family"]}]""", 3))
            server.enqueue(json("""[{"title":"Education fixture","ids":{"imdb":"tt2345678"},"certification":"TV-Y","genres":["children"],"subgenres":["educational"]}]""", 1))
            val source = source(server)
            val movie = withTimeout(3_000) { source.page(RatedKidsCatalogCodecTest.descriptor("family-movies"), 2) }
            val education = withTimeout(3_000) { source.page(RatedKidsCatalogCodecTest.descriptor("education-shows"), 1) }
            assertTrue(movie.hasMore); assertFalse(education.hasMore)
            assertTrue(education.items.single().genres.contains("Educational"))
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/movies/popular", first.requestUrl!!.encodedPath)
            assertEquals("family", first.requestUrl!!.queryParameter("genres"))
            assertEquals("g", first.requestUrl!!.queryParameter("certifications"))
            assertEquals("2", first.requestUrl!!.queryParameter("page"))
            assertEquals("/shows/popular", second.requestUrl!!.encodedPath)
            assertEquals("education,educational", second.requestUrl!!.queryParameter("subgenres"))
            assertEquals("tv-y,tv-y7,tv-y7-fv,tv-g", second.requestUrl!!.queryParameter("certifications"))
            for (request in listOf(first, second)) {
                assertEquals("GET", request.method); assertEquals(0L, request.bodySize)
                assertEquals("25", request.requestUrl!!.queryParameter("limit"))
                assertEquals("full,images", request.requestUrl!!.queryParameter("extended"))
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            }
        }
    }
    @Test fun `upstream HTTP errors and overfilled batch cannot return or disclose response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("synthetic-private-response"))
            val tooMany = "[" + List(26) { """{"title":"Fixture","ids":{"imdb":"tt1234567"},"certification":"G","genres":["family"]}""" }.joinToString(",") + "]"
            server.enqueue(json(tooMany, 2))
            val source = source(server)
            repeat(2) {
                val error = runCatching { withTimeout(3_000) { source.page(RatedKidsCatalogCodecTest.descriptor("family-movies"), 1) } }.exceptionOrNull()
                assertNotNull(error); assertFalse(error!!.toString().contains("synthetic-private-response"))
            }
        }
    }
    @Test fun `known and chunked successful or error bodies are bounded before Retrofit or Moshi buffering`() = runBlocking {
        for (status in listOf(200, 403)) for (chunked in listOf(false, true)) MockWebServer().use { server ->
            val content = " ".repeat(RatedKidsHttpClient.MAX_RESPONSE_BYTES.toInt() + 1)
            val response = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            if (chunked) response.setChunkedBody(content, 8_192) else response.setBody(content)
            server.enqueue(response)
            val error = runCatching { withTimeout(5_000) { source(server).page(RatedKidsCatalogCodecTest.descriptor("family-movies"), 1) } }.exceptionOrNull()
            assertNotNull("status=$status chunked=$chunked", error)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `cancelling a body being parsed cancels the original HTTP call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("[" + " ".repeat(100)).throttleBody(1, 1, TimeUnit.SECONDS))
            val call = AtomicReference<Call>(); val started = CompletableDeferred<Unit>()
            val source = source(server, call, started)
            val pending = async(Dispatchers.Default) { source.page(RatedKidsCatalogCodecTest.descriptor("family-movies"), 1) }
            withTimeout(3_000) { started.await() }
            withTimeout(3_000) { pending.cancelAndJoin() }
            assertTrue(call.get().isCanceled())
        }
    }
    private fun source(server: MockWebServer, call: AtomicReference<Call>? = null,
        bodyStarted: CompletableDeferred<Unit>? = null): TraktRatedKidsSource {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(RatedKidsHttpClient.create(OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(value: Call) { call?.set(value) }
                override fun responseBodyStart(value: Call) { bodyStarted?.complete(Unit) }
            }).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "synthetic-must-not-leave")
                    .header("Cookie", "synthetic=must-not-leave").build())
            }.build(), server.hostName)).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(TraktApi::class.java)
        val auth = mockk<TraktAuthService>()
        every { auth.hasRequiredCredentials() } returns true
        coEvery { auth.executePublicRequest<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Response<Any?>>()()
        }
        return TraktRatedKidsSource(api, auth)
    }
    private fun json(body: String, pages: Int) = MockResponse().setHeader("Content-Type", "application/json")
        .setHeader("X-Pagination-Page-Count", pages).setBody(body)
}
