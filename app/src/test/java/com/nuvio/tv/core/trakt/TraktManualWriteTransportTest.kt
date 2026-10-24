package com.nuvio.tv.core.trakt

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class TraktManualWriteTransportTest {
    private fun client() = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS)
        .addInterceptor(TraktManualWriteInterceptor()).build()
    @Test fun `checkin503 Retry After zero cannot cause automatic duplicate POST`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(200))
            client().newCall(Request.Builder().url(server.url("/checkin")).post("{}".toRequestBody()).build()).execute().use {
                assertEquals(503, it.code)
            }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `bodyless cancellation DELETE also cannot replay503`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(204))
            client().newCall(Request.Builder().url(server.url("/checkin")).delete().build()).execute().use { assertEquals(503,it.code) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `disconnect after receiving checkin never replays the accepted request`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200))
            try { client().newCall(Request.Builder().url(server.url("/checkin")).post("{}".toRequestBody()).build()).execute().use { fail() } }
            catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `redirect cannot forward private Authorization to another endpoint`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
            client().newCall(Request.Builder().url(server.url("/checkin")).header("Authorization", "Bearer test-private")
                .post("{}".toRequestBody()).build()).execute().use { assertEquals(307,it.code) }
            assertEquals(1, server.requestCount)
        }
    }
}
