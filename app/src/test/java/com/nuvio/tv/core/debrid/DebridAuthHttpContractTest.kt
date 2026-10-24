package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.AllDebridAuthApi
import com.nuvio.tv.data.remote.api.RealDebridOAuthApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class DebridAuthHttpContractTest {
    @Test fun `AllDebrid PIN start is public and check uses form POST`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"pin":"ABCD","check":"check-token","expires_in":600,"user_url":"https://alldebrid.com/pin/?pin=ABCD"}}"""))
            server.enqueue(json("""{"status":"success","data":{"activated":false,"expires_in":590}}"""))
            val api = retrofit(server).create(AllDebridAuthApi::class.java)
            assertEquals("ABCD", api.getPin().body()?.data?.pin)
            val start = server.takeRequest()
            assertEquals("GET", start.method)
            assertEquals("/v4.1/pin/get", start.path)
            assertNull(start.getHeader("Authorization"))
            assertTrue(start.requestUrl!!.queryParameterNames.isEmpty())
            assertEquals(false, api.checkPin("ABCD", "check-token").body()?.data?.activated)
            val check = server.takeRequest()
            assertEquals("POST", check.method)
            assertEquals("/v4/pin/check", check.path)
            assertEquals("application/x-www-form-urlencoded", check.getHeader("Content-Type"))
            assertEquals("pin=ABCD&check=check-token", check.body.readUtf8())
            assertNull(check.getHeader("Authorization"))
            assertNull(check.requestUrl!!.query)
        }
    }

    @Test fun `RD public device start and credential poll carry only their documented fields`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"device_code":"device-code","user_code":"USER","interval":5,"expires_in":600,"verification_url":"https://real-debrid.com/device"}"""))
            server.enqueue(json("""{"client_id":"user-client","client_secret":"user-secret"}"""))
            val api = retrofit(server).create(RealDebridOAuthApi::class.java)
            assertEquals("device-code", api.deviceCode("X245A4XAIBGVM").body()?.deviceCode)
            val start = server.takeRequest()
            assertEquals("GET", start.method)
            assertEquals("/device/code", start.requestUrl!!.encodedPath)
            assertEquals(setOf("client_id", "new_credentials"), start.requestUrl!!.queryParameterNames)
            assertEquals("X245A4XAIBGVM", start.requestUrl!!.queryParameter("client_id"))
            assertEquals("yes", start.requestUrl!!.queryParameter("new_credentials"))
            assertNull(start.getHeader("Authorization"))
            assertEquals("user-secret", api.deviceCredentials("X245A4XAIBGVM", "device-code").body()?.clientSecret)
            val poll = server.takeRequest()
            assertEquals("GET", poll.method)
            assertEquals("/device/credentials", poll.requestUrl!!.encodedPath)
            assertEquals(setOf("client_id", "code"), poll.requestUrl!!.queryParameterNames)
            assertEquals("device-code", poll.requestUrl!!.queryParameter("code"))
            assertFalse(poll.path.orEmpty().contains("user-secret"))
            assertNull(poll.getHeader("Authorization"))
        }
    }

    @Test fun `RD exchange sends user bound credentials only in POST form`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"access_token":"access","refresh_token":"refresh","expires_in":3600,"token_type":"Bearer"}"""))
            val api = retrofit(server).create(RealDebridOAuthApi::class.java)
            assertEquals("access", api.token("user-client", "secret + /", "device-code").body()?.accessToken)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/token", request.path)
            assertNull(request.requestUrl!!.query)
            assertNull(request.getHeader("Authorization"))
            val expected = FormBody.Builder().add("client_id", "user-client").add("client_secret", "secret + /")
                .add("code", "device-code").add("grant_type", "http://oauth.net/grant_type/device/1.0").build()
            val buffer = okio.Buffer().also(expected::writeTo)
            assertEquals(buffer.readUtf8(), request.body.readUtf8())
        }
    }

    @Test fun `cancelling Retrofit PIN polling cancels its in flight HTTP call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            val api = retrofit(server, client).create(AllDebridAuthApi::class.java)
            val polling = async(kotlinx.coroutines.Dispatchers.IO) { api.checkPin("ABCD", "private-check") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(1, client.dispatcher.runningCallsCount())
            polling.cancelAndJoin()
            assertTrue(polling.isCancelled)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) {
                kotlinx.coroutines.delay(10)
            }
            assertEquals(0, client.dispatcher.runningCallsCount())
            client.connectionPool.evictAll()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun retrofit(server: MockWebServer, client: OkHttpClient = OkHttpClient()) = Retrofit.Builder()
        .baseUrl(server.url("/")).client(client)
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
        .build()
}
