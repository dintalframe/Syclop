package com.nuvio.tv.data.simkl

import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class SimklV2OAuthHttpContractTest {
    @Test fun `device and token parameters are form encoded and private codes never enter URL`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(deviceBody()))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
            val configuration = SimklApiConfiguration("test-public-client", "syclop", "test", server.url("/").toString())
            val oauth = SimklV2OAuthClient(OkHttpSimklV2OAuthTransport(configuration), configuration)
            oauth.start(); oauth.poll("private-code+&=")
            val start = server.takeRequest(); val poll = server.takeRequest()
            assertEquals("/oauth2/device", start.path)
            assertEquals("/oauth2/token", poll.path)
            assertEquals("Syclop/test", start.getHeader("User-Agent"))
            assertTrue(start.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
            fun fields(body: String) = body.split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            assertEquals("media:read media:write", fields(start.body.readUtf8())["scope"])
            assertEquals("private-code+&=", fields(poll.body.readUtf8())["device_code"])
            assertNull(poll.getHeader("Authorization"))
            assertFalse(poll.requestUrl.toString().contains("private-code"))
        }
    }

    @Test fun `OAuth redirect is returned without contacting another destination`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/collect"))
            val configuration = SimklApiConfiguration("test-public-client", "syclop", "test", server.url("/").toString())
            val response = SimklV2OAuthClient(OkHttpSimklV2OAuthTransport(configuration), configuration).start()
            assertEquals(302, response.status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `cancelling device login cancels a real in-flight HTTP call`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = AtomicInteger()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.incrementAndGet() }
            }).build()
            val configuration = SimklApiConfiguration("test-public-client", "syclop", "test", server.url("/").toString())
            val oauth = SimklV2OAuthClient(OkHttpSimklV2OAuthTransport(configuration, client), configuration)
            val job = launch(Dispatchers.Default) { oauth.start() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Default) { withTimeout(3_000) { job.cancelAndJoin() } }
            assertTrue(cancelled.get() >= 1)
        }
    }

    @Test fun `untrusted error descriptions cannot enter account UI errors`() = runTest {
        val configuration = SimklApiConfiguration("test-public-client", "syclop", "test")
        val oauth = SimklV2OAuthClient(SimklV2OAuthTransport { _, _ ->
            throw java.io.IOException("https://user:private-key@example.org/")
        }, configuration)
        try { oauth.start(); fail("Expected safe network error") }
        catch (e: SimklAuthException) {
            assertEquals(SimklAuthError.NETWORK, e.error)
            assertFalse(e.toString().contains("private-key"))
            assertNull(e.cause)
        }
    }
}
