package com.nuvio.tv.ui.screens.player

import android.content.Context
import com.nuvio.tv.core.di.NetworkModule
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ProtocolException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class PlaybackNetworkingSslVerificationTest {

    @get:Rule
    val cache = TemporaryFolder()

    private val playbackHeaders = mapOf(
        "Authorization" to "Bearer owned-fixture",
        "Cookie" to "session=owned-fixture",
        "X-Playback-Key" to "owned-fixture",
        "Referer" to "https://origin.example/fixture",
        "User-Agent" to "Owned fixture player",
        "Accept-Language" to "en"
    )

    @Test
    fun `addon requests reject untrusted TLS while local HTTP still works`() {
        val context = mockk<Context>()
        every { context.cacheDir } returns cache.root
        val client = NetworkModule.provideAddonOkHttpClient(context, OkHttpClient())
        HeaderRecordingServer(testTls()).use { server ->
            val error = assertThrows(SSLHandshakeException::class.java) {
                client.newCall(request("https://127.0.0.1:${server.port}/addon")).execute().close()
            }
            assertCertificateFailure(error)
            assertTrue(server.requestsTo("/addon").isEmpty())
        }
        HeaderRecordingServer().use { server ->
            client.newCall(request("http://127.0.0.1:${server.port}/addon")).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals(1, server.requestsTo("/addon").size)
        }
    }

    @Test
    fun `addon and cached subtitle redirects cannot downgrade trusted HTTPS`() {
        val context = mockk<Context>()
        every { context.cacheDir } returns cache.root
        val tls = testTls()
        val trusted = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, tls.trustManager).build()
        val client = NetworkModule.provideAddonOkHttpClient(context, trusted)
        HeaderRecordingServer().use { clear ->
            HeaderRecordingServer(tls).use { secure ->
                val clearBase = "http://127.0.0.1:${clear.port}"
                val secureBase = "https://127.0.0.1:${secure.port}"
                clear.redirects["/start"] = "$secureBase/addon"
                secure.redirects["/addon"] = "$secureBase/subtitle"
                secure.redirects["/subtitle"] = "$clearBase/downgraded.srt"
                assertThrows(ProtocolException::class.java) {
                    client.newCall(request("$clearBase/start")).execute().close()
                }
                assertEquals(1, secure.requestsTo("/addon").size)
                assertEquals(1, secure.requestsTo("/subtitle").size)
                assertTrue(clear.requestsTo("/downgraded.srt").isEmpty())
            }
        }
    }

    @Test
    fun `addon requests keep HTTP upgrades and same protocol redirects working`() {
        val context = mockk<Context>()
        every { context.cacheDir } returns cache.root
        val tls = testTls()
        val trusted = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, tls.trustManager).build()
        val client = NetworkModule.provideAddonOkHttpClient(context, trusted)
        HeaderRecordingServer().use { clear ->
            HeaderRecordingServer(tls).use { secure ->
                val secureBase = "https://127.0.0.1:${secure.port}"
                clear.redirects["/start"] = "$secureBase/upgrade"
                secure.redirects["/upgrade"] = "/subtitle.srt"
                client.newCall(request("http://127.0.0.1:${clear.port}/start")).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("$secureBase/subtitle.srt", response.request.url.toString())
                }
                assertEquals(1, secure.requestsTo("/subtitle.srt").size)
            }
        }
    }

    @Test
    fun `untrusted media and subtitle requests fail before sending HTTP`() {
        HeaderRecordingServer(testTls()).use { server ->
            val base = "https://127.0.0.1:${server.port}"
            val mediaError = assertThrows(SSLHandshakeException::class.java) {
                PlayerPlaybackNetworking.playbackHttpClient.newCall(request("$base/media")).execute().close()
            }
            assertCertificateFailure(mediaError)
            assertEquals(1, server.connectionCount)
            assertTrue(server.requestsTo("/media").isEmpty())

            val subtitleError = assertThrows(SSLHandshakeException::class.java) {
                executeSubtitleRequest(request("$base/subtitle.srt")).close()
            }
            assertCertificateFailure(subtitleError)
            assertEquals(2, server.connectionCount)
            assertTrue(server.requestsTo("/subtitle.srt").isEmpty())
        }
    }

    @Test
    fun `clear HTTP and same protocol redirects still work`() {
        HeaderRecordingServer().use { server ->
            val base = "http://127.0.0.1:${server.port}"
            server.redirects["/start"] = "/landed"
            PlayerPlaybackNetworking.playbackHttpClient.newCall(request("$base/start")).execute().use {
                assertEquals(200, it.code)
                assertEquals("$base/landed", it.request.url.toString())
            }
            assertEquals(1, server.requestsTo("/landed").size)
        }
    }

    @Test
    fun `HTTP upgrades to trusted HTTPS and follows HTTPS redirects`() {
        val tls = testTls()
        HeaderRecordingServer().use { clear ->
            HeaderRecordingServer(tls).use { secure ->
                val secureBase = "https://127.0.0.1:${secure.port}"
                clear.redirects["/start"] = "$secureBase/upgrade"
                secure.redirects["/upgrade"] = "/landed"
                val client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                    .sslSocketFactory(tls.socketFactory, tls.trustManager)
                    .build()
                client.newCall(request("http://127.0.0.1:${clear.port}/start")).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("$secureBase/landed", it.request.url.toString())
                }
                assertEquals(1, secure.requestsTo("/upgrade").size)
                assertEquals(1, secure.requestsTo("/landed").size)
            }
        }
    }

    @Test
    fun `a redirect back to HTTP is blocked after a successful HTTPS upgrade`() {
        val tls = testTls()
        HeaderRecordingServer().use { clear ->
            HeaderRecordingServer(tls).use { secure ->
                val clearBase = "http://127.0.0.1:${clear.port}"
                clear.redirects["/start"] = "https://127.0.0.1:${secure.port}/upgrade"
                secure.redirects["/upgrade"] = "$clearBase/downgrade"
                val client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                    .sslSocketFactory(tls.socketFactory, tls.trustManager)
                    .build()
                assertThrows(ProtocolException::class.java) {
                    client.newCall(request("$clearBase/start")).execute().close()
                }
                assertEquals(1, secure.requestsTo("/upgrade").size)
                assertTrue(clear.requestsTo("/downgrade").isEmpty())
            }
        }
    }

    @Test
    fun `configured headers stay on the playback origin across redirects and new segment requests`() {
        val tls = testTls()
        HeaderRecordingServer(tls).use { origin ->
            HeaderRecordingServer(tls).use { cdn ->
                val originUrl = "https://127.0.0.1:${origin.port}"
                val cdnUrl = "https://127.0.0.1:${cdn.port}"
                origin.redirects["/start"] = "/same-origin"
                origin.redirects["/same-origin"] = "$cdnUrl/manifest?ticket=owned-fixture"
                val client = PlayerPlaybackNetworking.createHttpClient(playbackHeaders, credentialOriginUrl = originUrl)
                    .newBuilder().sslSocketFactory(tls.socketFactory, tls.trustManager).build()
                val initial = Request.Builder().url("$originUrl/start").header("Range", "bytes=0-7").build()
                client.newCall(initial).execute().use { assertEquals(200, it.code) }
                client.newCall(request("$cdnUrl/segment")).execute().use { assertEquals(200, it.code) }

                listOf("/start", "/same-origin").forEach { path ->
                    val sent = origin.requestsTo(path).single()
                    assertEquals("Bearer owned-fixture", sent["authorization"])
                    assertEquals("session=owned-fixture", sent["cookie"])
                    assertEquals("owned-fixture", sent["x-playback-key"])
                }
                val manifest = cdn.requestsTo("/manifest?ticket=owned-fixture").single()
                assertPortableOnly(manifest)
                assertEquals("bytes=0-7", manifest["range"])
                assertPortableOnly(cdn.requestsTo("/segment").single())
            }
        }
    }

    @Test
    fun `parallel resolved ranges and tagged AFR redirects do not send playback credentials to the CDN`() {
        val tls = testTls()
        HeaderRecordingServer(tls).use { origin ->
            HeaderRecordingServer(tls).use { cdn ->
                val originUrl = "https://127.0.0.1:${origin.port}"
                val cdnUrl = "https://127.0.0.1:${cdn.port}"
                origin.redirects["/afr"] = "$cdnUrl/afr-landed"
                val client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                    .sslSocketFactory(tls.socketFactory, tls.trustManager)
                    .build()
                val parallelClient = client.withScopedPlaybackHeaders(originUrl, playbackHeaders.keys)
                val range = ownedRangeRequest("$cdnUrl/resolved-range").build()
                parallelClient.newCall(range).execute().use { assertEquals(200, it.code) }
                val afr = ownedRangeRequest("$originUrl/afr").scopePlaybackHeaders(originUrl, playbackHeaders.keys).build()
                client.newCall(afr).execute().use { assertEquals(200, it.code) }

                assertEquals("owned-fixture", origin.requestsTo("/afr").single()["x-playback-key"])
                listOf("/resolved-range", "/afr-landed").forEach { path ->
                    val sent = cdn.requestsTo(path).single()
                    assertPortableOnly(sent)
                    assertEquals("bytes=0-7", sent["range"])
                }
            }
        }
    }

    private fun ownedRangeRequest(url: String): Request.Builder =
        Request.Builder().url(url).header("Range", "bytes=0-7").apply {
            playbackHeaders.forEach { (name, value) -> header(name, value) }
        }

    private fun assertPortableOnly(headers: Map<String, String>) {
        assertNull(headers["authorization"])
        assertNull(headers["cookie"])
        assertNull(headers["x-playback-key"])
        assertNull(headers["referer"])
        assertEquals("Owned fixture player", headers["user-agent"])
        assertEquals("en", headers["accept-language"])
    }

    private fun assertCertificateFailure(error: SSLHandshakeException) {
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any {
            it is CertificateException || it is CertPathValidatorException
        })
    }

    private fun request(url: String): Request = Request.Builder().url(url).build()
}
