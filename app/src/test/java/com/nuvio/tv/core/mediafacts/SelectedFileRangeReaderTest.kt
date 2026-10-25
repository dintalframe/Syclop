package com.nuvio.tv.core.mediafacts

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class SelectedFileRangeReaderTest {
    private fun response(first: Int, last: Int, total: Int, body: String) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", "bytes $first-$last/$total").setBody(body)
    private fun reader(server: MockWebServer, guard: () -> Boolean = { true }, maxRequests: Int = 16, maxBytes: Long = 32) =
        SelectedFileRangeReader(server.url("/file?synthetic=private").toString(), guard, chunkBytes = 4,
            maxRequests = maxRequests, maxBytes = maxBytes, allowLocalHttpForTests = true)

    @Test fun `range cache reads bounded bytes without credentials and learns selected file length`() {
        MockWebServer().use { server ->
            server.enqueue(response(0,3,12,"abcd"))
            reader(server).use { source ->
                val bytes = ByteArray(3)
                assertEquals(3, source.readAt(1,bytes,0,3)); assertEquals("bcd",String(bytes))
                assertEquals(2, source.readAt(1,bytes,0,2)); assertEquals(1,source.requestCount)
                assertEquals(12L,source.knownSize); assertEquals(4L,source.bodyBytes)
                val request = server.takeRequest(1,TimeUnit.SECONDS)!!
                assertEquals("bytes=0-3",request.getHeader("Range"))
                assertEquals("identity",request.getHeader("Accept-Encoding"))
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            }
        }
    }
    @Test fun `ignored ranges and redirects fail without following or downloading the full object`() {
        MockWebServer().use { server ->
            for(code in listOf(200,302)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location",server.url("/other")).setBody("large body"))
                reader(server).use { source -> assertThrows(IOException::class.java) { source.prime() }; assertEquals(0L,source.bodyBytes) }
            }
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun `invalid range origin span total and changed file validator are rejected`() {
        MockWebServer().use { server ->
            for(range in listOf("bytes 1-3/12","bytes 0-4/12","bytes 0-3/*","bytes 0-3/3","garbage")) {
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range",range).setBody("abcd"))
                reader(server).use { source -> assertThrows(IOException::class.java) { source.prime() } }
            }
            server.enqueue(response(0,3,8,"abcd").setHeader("ETag","\"A\""))
            server.enqueue(response(4,7,8,"efgh").setHeader("ETag","\"B\""))
            reader(server).use { source -> source.prime(); assertThrows(IOException::class.java) { source.readAt(4,ByteArray(1),0,1) } }
        }
    }
    @Test fun `short non-final ranges are unknown rather than a cached false EOF`() {
        MockWebServer().use { server ->
            server.enqueue(response(0,1,12,"ab"))
            reader(server).use { source -> assertThrows(IOException::class.java) { source.prime() }; assertNull(source.knownSize) }
            server.enqueue(response(0,1,2,"ab"))
            reader(server).use { source -> source.prime(); assertEquals(-1,source.readAt(2,ByteArray(1),0,1)) }
        }
    }
    @Test fun `request and byte budgets stop before an extra request`() {
        MockWebServer().use { server ->
            server.enqueue(response(0,3,12,"abcd"))
            reader(server,maxRequests=1,maxBytes=4).use { source ->
                source.prime()
                assertThrows(IOException::class.java) { source.readAt(4,ByteArray(1),0,1) }
                assertEquals(1,source.requestCount); assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun `a revoked initiating scope dispatches no request`() {
        MockWebServer().use { server ->
            reader(server,guard={ false }).use { source -> assertThrows(IOException::class.java) { source.prime() } }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun `scope revoked during response cannot publish file bytes or metadata`() {
        MockWebServer().use { server ->
            val allowed = AtomicBoolean(true)
            server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                allowed.set(false); return response(0,3,12,"abcd")
            } }
            reader(server,guard=allowed::get).use { source ->
                assertThrows(IOException::class.java) { source.prime() }
                assertNull(source.knownSize)
            }
        }
    }
    @Test fun `close cancels the actual in-flight HTTP call without exposing its URL`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val source=reader(server); val pool=Executors.newSingleThreadExecutor()
            try {
                val pending=pool.submit<Throwable?> { runCatching { source.prime() }.exceptionOrNull() }
                assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
                source.close()
                val failure=pending.get(2,TimeUnit.SECONDS)
                assertTrue(failure is IOException); assertFalse(failure.toString().contains("synthetic"))
            } finally { source.close();pool.shutdownNow() }
        }
    }
    @Test fun `monotonic deadline rejects even cached reads after expiry`() {
        MockWebServer().use { server ->
            var nanos=0L
            server.enqueue(response(0,3,12,"abcd"))
            SelectedFileRangeReader(server.url("/file").toString(),{ true },nowNanos={nanos},budgetNanos=4_000_000_000L,
                chunkBytes=4,maxBytes=16,allowLocalHttpForTests=true).use { source ->
                source.prime(); nanos=4_000_000_000L
                assertThrows(IOException::class.java) { source.readAt(0,ByteArray(1),0,1) }
                assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun `unexpected encoding or mismatched body length cannot become verified facts`() {
        MockWebServer().use { server ->
            server.enqueue(response(0,3,12,"abcde"))
            server.enqueue(response(0,3,12,"abcd").setHeader("Content-Encoding","gzip"))
            repeat(2) { reader(server).use { source -> assertThrows(IOException::class.java) { source.prime() };assertNull(source.knownSize) } }
        }
    }
    @Test fun `production transport refuses HTTP even if a caller supplies a positive guard`() {
        MockWebServer().use { server ->
            SelectedFileRangeReader(server.url("/file").toString(),{true}).use { source ->
                assertThrows(IOException::class.java) { source.prime() }
            }
            assertEquals(0,server.requestCount)
        }
    }
}
