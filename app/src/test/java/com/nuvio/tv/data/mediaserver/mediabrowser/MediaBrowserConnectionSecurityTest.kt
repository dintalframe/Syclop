package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBrowserConnectionSecurityTest {
    @Test
    fun aLocalNameResolvingToAPublicAddressIsBlockedBeforeConnecting() = runTest {
        val lookups = AtomicInteger()
        val http = OkHttpClient.Builder().dns {
            lookups.incrementAndGet()
            listOf(InetAddress.getByName("8.8.8.8"))
        }.build()
        val error = runCatching {
            JellyfinProvider(http, testIdentity).signIn("http://den.local:8096", "viewer", "fixture-password", true)
        }.exceptionOrNull() as ServerException
        assertEquals(ServerFailure.INSECURE_CONNECTION, error.failure)
        assertEquals(1, lookups.get())
    }

    @Test
    fun configuredProxiesCannotBypassTheLocalHttpDestinationCheck() = runTest {
        for (type in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
            val lookups = AtomicInteger()
            val connections = AtomicInteger()
            val http = OkHttpClient.Builder()
                .proxy(Proxy(type, InetSocketAddress("127.0.0.1", 9)))
                .dns {
                    lookups.incrementAndGet()
                    listOf(InetAddress.getByName("203.0.113.10"))
                }
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        connections.incrementAndGet()
                        throw IOException("Stop before opening a socket")
                    }
                })
                .build()
            val error = runCatching {
                JellyfinProvider(http, testIdentity).signIn("http://den.local:8096", "viewer", "fixture-password", true)
            }.exceptionOrNull() as ServerException
            assertEquals(type.toString(), ServerFailure.INSECURE_CONNECTION, error.failure)
            assertEquals(type.toString(), 1, lookups.get())
            assertEquals(type.toString(), 0, connections.get())
        }
    }

    @Test
    fun httpsKeepsConfiguredProxyRouting() = runTest {
        val configuredProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))
        val selectedProxy = AtomicReference<Proxy>()
        val http = OkHttpClient.Builder()
            .proxy(configuredProxy)
            .dns { error("The numeric proxy must not resolve the destination locally") }
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    selectedProxy.set(proxy)
                    throw IOException("Stop before opening a socket")
                }
            })
            .build()
        val error = runCatching {
            JellyfinProvider(http, testIdentity).signIn("https://media.example", "viewer", "fixture-password")
        }.exceptionOrNull() as ServerException
        assertEquals(ServerFailure.UNREACHABLE, error.failure)
        assertEquals(configuredProxy, selectedProxy.get())
    }

    @Test
    fun refusesUnapprovedLocalHttpAndPublicHttpBeforeAnyNetworkRequest() = runTest {
        for ((address, approved) in listOf("http://den.local:8096" to false, "http://8.8.8.8" to true)) {
            val http = TestHttp()
            val error = runCatching {
                JellyfinProvider(http.client, testIdentity).signIn(address, "viewer", "fixture-password", approved)
            }.exceptionOrNull() as ServerException
            assertEquals(ServerFailure.INSECURE_CONNECTION, error.failure)
            assertTrue(http.requests.isEmpty())
        }
    }

    @Test
    fun discoveryNeverMovesCredentialsToAnotherOriginOrDowngradesHttps() = runTest {
        for (target in listOf("http://trusted.example/System/Info/Public", "https://other.example/System/Info/Public")) {
            val requests = mutableListOf<Request>()
            val http = redirectingClient(target, requests)
            val error = runCatching {
                JellyfinProvider(http, testIdentity).signIn("https://trusted.example", "viewer", "fixture-password")
            }.exceptionOrNull() as ServerException
            assertEquals(ServerFailure.INSECURE_CONNECTION, error.failure)
            assertEquals(1, requests.size)
            assertEquals("GET", requests.single().method)
        }
    }

    @Test
    fun sameOriginReverseProxyRedirectPreservesSignIn() = runTest {
        val requests = mutableListOf<Request>()
        val http = redirectingClient("/jellyfin/System/Info/Public", requests)
        val result = JellyfinProvider(http, testIdentity).signIn("https://trusted.example", "viewer", "fixture-password")
        assertEquals("https://trusted.example/jellyfin", result.address)
        assertEquals("https://trusted.example/jellyfin/Users/AuthenticateByName", requests.last().url.toString())
        assertEquals("POST", requests.last().method)
    }

    private fun redirectingClient(target: String, requests: MutableList<Request>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val redirected = requests.size == 1
            val body = if (request.method == "POST") {
                """{"User":{"Id":"u1","Name":"viewer"},"AccessToken":"fixture-token","ServerId":"s1"}"""
            } else """{"ServerName":"Den","Version":"10.10.7","Id":"s1"}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (redirected) 302 else 200).message("Fixture")
                .apply { if (redirected) header("Location", target) }
                .body(body.toResponseBody()).build()
        }.build()
}
