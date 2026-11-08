package com.nuvio.tv.core.server

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class BadgeConfigDownloaderTest {
    private val json = """{"filters":[],"groups":[]}"""
    private val publicAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
    private val privateAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))

    @Test
    fun `validated https and explicitly entered private http sources are supported`() {
        for ((url, address) in listOf("https://badges.example/rules" to publicAddress, "http://192.168.1.20/rules" to privateAddress)) {
            val client = client(dns { listOf(address) }) { response(it, json.toResponseBody()) }
            assertEquals(json, BadgeConfigDownloader(client).fetch(url))
        }
    }

    @Test
    fun `configured proxies cannot bypass the validated destination pin`() {
        for (type in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
            var lookups = 0
            var selectedProxy: Proxy? = null
            var selectedAddress: InetSocketAddress? = null
            val client = OkHttpClient.Builder()
                .proxy(Proxy(type, InetSocketAddress("127.0.0.1", 9)))
                .dns(dns {
                    listOf(if (lookups++ == 0) publicAddress else InetAddress.getByName("127.0.0.1"))
                })
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        selectedProxy = proxy
                        selectedAddress = inetSocketAddress
                        throw IOException("Stop before opening a socket")
                    }
                })
                .build()
            assertThrows(IOException::class.java) {
                BadgeConfigDownloader(client).fetch("https://badges.example/rules")
            }
            assertEquals(type.toString(), Proxy.NO_PROXY, selectedProxy)
            assertEquals(type.toString(), publicAddress, selectedAddress?.address)
            assertEquals(type.toString(), 443, selectedAddress?.port)
            assertEquals(type.toString(), 1, lookups)
        }
    }

    @Test
    fun `public http embedded credentials and non http schemes are rejected`() {
        var requests = 0
        val client = client(dns { listOf(publicAddress) }) {
            requests++
            response(it, json.toResponseBody())
        }
        for (url in listOf("http://badges.example/rules", "https://secret@badges.example/rules", "file:///data/private", "https://badges.example/rules#secret")) {
            assertThrows(Exception::class.java) { BadgeConfigDownloader(client).fetch(url) }
        }
        assertEquals(0, requests)
    }

    @Test
    fun `redirect from public source cannot reach a private address`() {
        var requests = 0
        val client = client(dns { host -> listOf(if (host == "private.example") privateAddress else publicAddress) }) {
            requests++
            response(it, "".toResponseBody(), 302, "https://private.example/rules")
        }
        assertThrows(Exception::class.java) { BadgeConfigDownloader(client).fetch("https://badges.example/rules") }
        assertEquals(1, requests)
    }

    @Test
    fun `dns changes cannot rebind a public source into the local network`() {
        var lookups = 0
        var requests = 0
        val client = client(dns { listOf(if (lookups++ == 0) publicAddress else privateAddress) }) {
            requests++
            response(it, "".toResponseBody(), 302, "/redirected")
        }
        assertThrows(Exception::class.java) { BadgeConfigDownloader(client).fetch("https://badges.example/rules") }
        assertEquals(1, requests)
    }

    @Test
    fun `loopback link local and downgrade targets are rejected`() {
        for (address in listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("169.254.169.254"))) {
            var requests = 0
            val client = client(dns { listOf(address) }) {
                requests++
                response(it, json.toResponseBody())
            }
            assertThrows(Exception::class.java) { BadgeConfigDownloader(client).fetch("https://badges.example/rules") }
            assertEquals(0, requests)
        }
        val client = client(dns { listOf(publicAddress) }) {
            response(it, "".toResponseBody(), 302, "http://badges.example/rules")
        }
        assertThrows(Exception::class.java) { BadgeConfigDownloader(client).fetch("https://badges.example/rules") }
    }

    @Test
    fun `unknown length responses are bounded by actual bytes`() {
        val source = Buffer().write(ByteArray(MAX_CONFIG_BODY_BYTES + 1))
        val oversized = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        val client = client(dns { listOf(publicAddress) }) { response(it, oversized) }
        assertThrows(Exception::class.java) { BadgeConfigDownloader(client).fetch("https://badges.example/rules") }
    }

    @Test
    fun `declared oversized response is rejected before reading bytes`() {
        var reads = 0
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads++
                return super.read(sink, byteCount)
            }
        }.buffer()
        val oversized = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = MAX_CONFIG_BODY_BYTES + 1L
            override fun source(): BufferedSource = source
        }
        val client = client(dns { listOf(publicAddress) }) { response(it, oversized) }
        assertThrows(Exception::class.java) { BadgeConfigDownloader(client).fetch("https://badges.example/rules") }
        assertEquals(0, reads)
    }

    private fun client(dns: Dns, respond: (okhttp3.Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .addInterceptor { chain -> respond(chain.request()) }
        .build()

    private fun dns(resolve: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }

    private fun response(request: okhttp3.Request, body: ResponseBody, code: Int = 200, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body)
            .apply { if (location != null) header("Location", location) }.build()
}
