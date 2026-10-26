package com.nuvio.tv.core.express

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ExpressHttpCooldownTest {
    @Test fun `429 Retry-After cooldown survives another search without another network call`() = runBlocking {
        MockWebServer().use { server ->
            val now = AtomicLong(1_000L)
            val http = ExpressHttpClient(OkHttpClient(), true, now::get)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            server.enqueue(MockResponse().setBody("{}"))
            repeat(2) {
                try { http.get(server.url("/source").toString()); fail("Expected rate limit") }
                catch (error: ExpressHttpException) {
                    assertEquals(ExpressHttpFailure.RATE_LIMITED, error.failure)
                    assertEquals(60_000L, error.retryAfterMillis)
                }
            }
            assertEquals(1, server.requestCount)
            now.addAndGet(60_000)
            assertEquals("{}", http.get(server.url("/source").toString()).text)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `HTTP date Retry-After and absent header produce bounded fixed diagnostics`() {
        val http = ExpressHttpClient(OkHttpClient(), true)
        assertEquals(119_000L, http.retryAfterMillis("Thu, 01 Jan 1970 00:02:00 GMT", 1_000L))
        assertEquals(0L, http.retryAfterMillis("Thu, 01 Jan 1970 00:00:00 GMT", 1_000L))
        assertEquals(60_000L, http.retryAfterMillis(null, 1_000L))
        assertEquals(60_000L, http.retryAfterMillis("credential-bearing-untrusted-text", 1_000L))
        assertEquals(Long.MAX_VALUE, http.retryAfterMillis(Long.MAX_VALUE.toString(), 1_000L))
    }

    @Test fun `one saturated origin leaves a global slot for another origin`() = runBlocking {
        MockWebServer().use { slow -> MockWebServer().use { healthy ->
            val firstTwo = CountDownLatch(2)
            slow.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    firstTwo.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            healthy.enqueue(MockResponse().setBody("healthy"))
            val http = ExpressHttpClient(OkHttpClient(), true)
            val jobs = (1..4).map { index -> launch(Dispatchers.Default) { http.get(slow.url("/$index").toString()) } }
            try {
                withContext(Dispatchers.IO) { assertTrue(firstTwo.await(3, TimeUnit.SECONDS)) }
                val result = withTimeout(3_000) { http.get(healthy.url("/source").toString()) }
                assertEquals("healthy", result.text)
                assertEquals(2, slow.requestCount)
            } finally { jobs.forEach { it.cancelAndJoin() } }
        } }
    }
}
