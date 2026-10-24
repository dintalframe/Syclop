package com.nuvio.tv.data.mal

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

class MalApiCancellationTest {
    @Test fun `cancelling token exchange cancels the actual in-flight HTTP call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = AtomicInteger()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.incrementAndGet() }
            }).build()
            val api = MalApiClient(client, server.url("/v2").toString(), server.url("/token").toString())
            val job = launch(Dispatchers.Default) { api.exchange(malTestConfiguration, "one-time-test-code", "test-verifier") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(cancelled.get() >= 1)
        }
    }
    @Test fun `malformed token payload details never escape in a parser exception`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"private-test-secret\":"))
            val api = MalApiClient(OkHttpClient(), server.url("/v2").toString(), server.url("/token").toString())
            try { api.exchange(malTestConfiguration, "code", "verifier"); fail("Expected safe parser error") }
            catch (failure: java.io.IOException) { // Coroutine stack-trace recovery may add a safe IOException copy as cause.
                generateSequence<Throwable>(failure) { it.cause }.take(8).forEach { cause ->
                    assertFalse(cause.toString().contains("private-test-secret"))
                    assertEquals("MyAnimeList response could not be read.", cause.message)
                } }
        }
    }
}
