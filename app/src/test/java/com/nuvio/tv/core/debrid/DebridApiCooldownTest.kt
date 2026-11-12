package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.network.DebridApiCooldownInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridApiCooldownTest {
    @Test fun `Real Debrid cooldown crosses clients and endpoints for the same credential`() {
        var requests = 0
        val provider = Interceptor { chain ->
            val status = if (++requests == 1) 429 else 200
            providerResponse(chain.request(), status, if (status == 429) "60" else null)
        }
        val playback = NetworkModule.provideDirectDebridOkHttpClient().newBuilder().addInterceptor(provider).build()
        val cloud = NetworkModule.provideDebridAuthClient().newBuilder().addInterceptor(provider).build()
        val origin = "https://api.real-debrid.com/rest/1.0/"

        assertEquals(429, playback.status(origin + "torrents/addMagnet", "rd-first-account"))
        assertEquals(429, cloud.status(origin + "torrents", "rd-first-account"))
        assertEquals(1, requests)
        assertEquals(200, cloud.status(origin + "torrents", "rd-other-account"))
        assertEquals(200, playback.status("https://api.alldebrid.com/v4/user", "rd-first-account"))
        assertEquals(3, requests)
    }

    @Test fun `TorBox cooldown follows the endpoint budget`() {
        var requests = 0
        val client = NetworkModule.provideDirectDebridOkHttpClient().newBuilder().addInterceptor { chain ->
            val status = if (++requests == 1) 429 else 200
            providerResponse(chain.request(), status, if (status == 429) "60" else null)
        }.build()
        val origin = "https://api.torbox.app/v1/api/torrents/"

        assertEquals(429, client.status(origin + "checkcached", "tb-account", """{"hashes":["first"]}"""))
        assertEquals(429, client.status(origin + "checkcached", "tb-account", """{"hashes":["second"]}"""))
        assertEquals(200, client.status(origin + "mylist", "tb-account"))
        assertEquals(2, requests)
    }

    @Test fun `AllDebrid Retry After on 503 cools down other calls but 451 does not`() {
        var requests = 0
        val client = NetworkModule.provideDirectDebridOkHttpClient().newBuilder().addInterceptor { chain ->
            val status = if (++requests == 1) 451 else 503
            providerResponse(chain.request(), status, if (status == 503) "60" else null)
        }.build()
        val origin = "https://api.alldebrid.com/v4/"

        assertEquals(451, client.status(origin + "magnet/upload", "ad-account"))
        assertEquals(503, client.status(origin + "magnet/upload", "ad-account"))
        assertEquals(429, client.status(origin + "user", "ad-account"))
        assertEquals(2, requests)
    }

    @Test fun `blocked calls report remaining time and never extend the provider cooldown`() {
        var now = 1_000L
        var requests = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(DebridApiCooldownInterceptor { now })
            .addInterceptor { chain ->
                val status = if (++requests == 1) 429 else 200
                providerResponse(chain.request(), status, if (status == 429) "4" else null)
            }.build()
        val request = Request.Builder().url("https://api.real-debrid.com/rest/1.0/user")
            .header("Authorization", "Bearer expiry-account").build()

        client.newCall(request).execute().use { assertEquals(429, it.code) }
        now += 2_500
        client.newCall(request).execute().use {
            assertEquals(429, it.code)
            assertEquals("2", it.header("Retry-After"))
        }
        assertEquals(1, requests)
        now += 1_500
        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(2, requests)
    }

    private fun OkHttpClient.status(url: String, credential: String, body: String? = null): Int {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $credential")
        if (body != null) request.post(body.toRequestBody())
        return newCall(request.build()).execute().use { it.code }
    }

    private fun providerResponse(request: Request, status: Int, retryAfter: String?): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test response")
            .body("".toResponseBody())
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
}
