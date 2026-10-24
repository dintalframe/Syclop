package com.nuvio.tv.core.debrid

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DebridSelectedFileHeadProbeTest {
    @Test fun `HEAD reads only same-file size and sends no addon credential headers`()=runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","video/mp4").setHeader("Content-Length","123456"))
            assertEquals(123456L,DebridSelectedFileHeadProbe(OkHttpClient(),true).size(server.url("/selected-file").toString()))
            val request=server.takeRequest();assertEquals("HEAD",request.method)
            assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"))
            assertEquals("identity",request.getHeader("Accept-Encoding"));assertEquals(0L,request.bodySize)
        }
    }
    @Test fun `redirect is not followed and does not become file metadata`()=runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
            val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            assertNull(DebridSelectedFileHeadProbe(client,true).size(server.url("/file").toString()))
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun `HTML errors and malformed or compressed lengths are rejected`()=runTest {
        for(headers in listOf(listOf("text/html","123","identity"),listOf("video/mp4","-1","identity"),
            listOf("video/mp4","123","gzip"))) MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type",headers[0]).setHeader("Content-Length",headers[1]).setHeader("Content-Encoding",headers[2]))
            assertNull(DebridSelectedFileHeadProbe(OkHttpClient(),true).size(server.url("/file").toString()))
        }
    }
    @Test fun `production probe rejects HTTP credentials and nonstandard TLS ports before contact`()=runTest {
        val probe=DebridSelectedFileHeadProbe()
        assertNull(probe.size("http://example.org/file"));assertNull(probe.size("https://user:password@example.org/file"))
        assertNull(probe.size("https://example.org:8443/file"))
    }
    @Test fun `cancelling pending HEAD cancels underlying network call`()=runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled=AtomicInteger()
            val client=OkHttpClient.Builder().eventListener(object:EventListener(){override fun canceled(call:Call){cancelled.incrementAndGet()}}).build()
            val job=launch(Dispatchers.Default){DebridSelectedFileHeadProbe(client,true).size(server.url("/file").toString())}
            withContext(Dispatchers.IO){assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}
            withContext(Dispatchers.Default){withTimeout(3000){job.cancelAndJoin()}}
            assertTrue(cancelled.get()>0)
        }
    }
}
