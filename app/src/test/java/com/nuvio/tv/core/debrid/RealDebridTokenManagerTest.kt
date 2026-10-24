package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.RealDebridAuthStateStore
import com.nuvio.tv.data.remote.api.RealDebridOAuthApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridOAuthTokenDto
import com.nuvio.tv.core.di.NetworkModule
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealDebridTokenManagerTest {
    private val api = mockk<RealDebridOAuthApi>()
    private val store = MemoryStore()
    private val manager = RealDebridTokenManager(api, store, { 10_000L })

    @Test fun `expired access token refreshes once and rotates every credential atomically`() = runTest {
        store.state = old(expired = true)
        var calls = 0
        coEvery { api.token("client", "secret", "refresh-old", any()) } answers {
            calls += 1; Response.success(RealDebridOAuthTokenDto("new", "refresh-new", 3600, "Bearer"))
        }
        assertEquals("new", manager.authorizationToken("old"))
        assertEquals("new", manager.authorizationToken("old"))
        assertEquals("refresh-new", store.state?.refreshToken)
        assertEquals(3_610_000L, store.state?.expiresAtEpochMillis)
        assertEquals(1, calls)
    }

    @Test fun `concurrent unauthorized requests use a single refresh`() = runTest {
        var calls = 0
        coEvery { api.token(any(), any(), any(), any()) } coAnswers {
            calls += 1; delay(10); Response.success(RealDebridOAuthTokenDto("new", "rotated", 3600, "Bearer"))
        }
        val tokens = (1..10).map { async { manager.authorizationToken("old", unauthorized = true) } }.awaitAll()
        assertEquals(List(10) { "new" }, tokens)
        assertEquals(1, calls)
    }

    @Test fun `401 retries once with new token and never loops when server rejects again`() = runTest {
        coEvery { api.token(any(), any(), any(), any()) } returns Response.success(RealDebridOAuthTokenDto("new", "rotated", 3600, "Bearer"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            val client = OkHttpClient.Builder().addInterceptor(RealDebridAuthorizationInterceptor(manager)).build()
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(server.url("/user")).header("Authorization", "Bearer old").build()).execute().use {
                    assertEquals(401, it.code)
                }
            }
            assertEquals(2, server.requestCount)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `five simultaneous REST authorizations leave a separate OAuth queue available for refresh`() = runTest {
        MockWebServer().use { server ->
            val initialRequests = CountDownLatch(5)
            val tokenCalls = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/oauth/v2/token") {
                        tokenCalls.incrementAndGet()
                        return MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("""{"access_token":"new","refresh_token":"rotated","expires_in":3600,"token_type":"Bearer"}""")
                    }
                    if (request.getHeader("Authorization") == "Bearer old") {
                        initialRequests.countDown()
                        initialRequests.await(3, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(401)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
                }
            }
            val authClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            val converter = MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())
            val oauthApi = Retrofit.Builder().baseUrl(server.url("/oauth/v2/")).client(authClient)
                .addConverterFactory(converter).build().create(RealDebridOAuthApi::class.java)
            val refresh = RealDebridTokenManager(oauthApi, store, { 10_000L })
            val restClient = NetworkModule.provideRealDebridRestClient(authClient, refresh)
            val restApi = Retrofit.Builder().baseUrl(server.url("/rest/1.0/")).client(restClient)
                .addConverterFactory(converter).build().create(RealDebridApi::class.java)
            val codes = withContext(Dispatchers.IO) {
                (1..5).map { async {
                    val response = restApi.getUser("Bearer old")
                    response.body()?.close()
                    response.code()
                } }.awaitAll()
            }
            assertEquals(List(5) { 200 }, codes)
            assertEquals(1, tokenCalls.get())
            authClient.connectionPool.evictAll()
            restClient.connectionPool.evictAll()
        }
    }

    @Test fun `manual key has no hidden refresh or replacement`() = runTest {
        store.state = null
        store.manualKeys[1] = "manual-key"
        assertEquals("manual-key", manager.authorizationToken("manual-key", unauthorized = true))
        assertNull(store.state)
    }

    @Test fun `first unseen OAuth token from another viewer is rejected after process start`() = runTest {
        store.activeProfileId = 2
        try { manager.authorizationToken("old"); fail("Expected stored token owner guard") } catch (_: IOException) { }
        assertEquals("old", store.state?.accessToken)
    }

    @Test fun `unseen stored manual key from another viewer and unknown REST keys are rejected`() = runTest {
        store.state = null
        store.manualKeys[1] = "viewer-one-manual"
        store.activeProfileId = 2
        try { manager.authorizationToken("viewer-one-manual"); fail("Expected manual token owner guard") } catch (_: IOException) { }
        try { manager.authorizationToken("new-unregistered-key"); fail("Unknown keys need explicit validation transport") } catch (_: IOException) { }
    }

    @Test fun `explicit key validation uses isolated transport and accepts an unknown manual key before saving`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":42,"username":"viewer"}"""))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val validationApi = NetworkModule.provideRealDebridValidationApi(client,
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build())
            val validator = DebridAccountValidator(validationApi, mockk())
            assertTrue(validator.validate(DebridProviders.REAL_DEBRID_ID, "new-unregistered-key"))
            val request = server.takeRequest()
            assertEquals("/rest/1.0/user", request.path)
            assertEquals("Bearer new-unregistered-key", request.getHeader("Authorization"))
            assertNull(request.requestUrl!!.query)
            assertEquals("old", store.state?.accessToken)
        }
    }

    @Test fun `failed refresh preserves existing account and redacts server error`() = runTest {
        store.state = old(expired = true)
        coEvery { api.token(any(), any(), any(), any()) } returns Response.error(400,
            "secret-access-token https://private.example/token".toResponseBody("application/json".toMediaType()))
        try { manager.authorizationToken("old"); fail("Expected failure") } catch (error: IOException) {
            assertFalse(error.message.orEmpty().contains("secret-access-token"))
            assertFalse(error.message.orEmpty().contains("private.example"))
        }
        assertEquals("old", store.state?.accessToken)
        assertEquals("refresh-old", store.state?.refreshToken)
    }

    @Test fun `profile change during refresh rejects late credentials`() = runTest {
        store.state = old(expired = true)
        val requested = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { api.token(any(), any(), any(), any()) } coAnswers {
            requested.complete(Unit); finish.await()
            Response.success(RealDebridOAuthTokenDto("new", "rotated", 3600, "Bearer"))
        }
        val result = async { runCatching { manager.authorizationToken("old") } }
        requested.await()
        store.activeProfileId = 2
        finish.complete(Unit)
        assertTrue(result.await().isFailure)
        assertEquals("old", store.state?.accessToken)
    }

    @Test fun `known OAuth token cannot be reused after switching viewer or account`() = runTest {
        assertEquals("old", manager.authorizationToken("old"))
        store.activeProfileId = 2
        try { manager.authorizationToken("old"); fail("Expected viewer guard") } catch (_: IOException) { }
        store.activeProfileId = 1
        store.state = old().copy(clientId = "different-account", accessToken = "different")
        try { manager.authorizationToken("old"); fail("Expected account guard") } catch (_: IOException) { }
    }

    @Test fun `cancelled refresh never saves a token`() = runTest {
        store.state = old(expired = true)
        coEvery { api.token(any(), any(), any(), any()) } throws CancellationException("cancelled")
        try { manager.authorizationToken("old"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals("old", store.state?.accessToken)
    }

    @Test fun `incomplete refresh response cannot destroy valid refresh token`() = runTest {
        store.state = old(expired = true)
        coEvery { api.token(any(), any(), any(), any()) } returns Response.success(RealDebridOAuthTokenDto("new", null, 3600, "Bearer"))
        try { manager.authorizationToken("old"); fail("Expected failure") } catch (_: IOException) { }
        assertEquals("refresh-old", store.state?.refreshToken)
    }

    private fun old(expired: Boolean = false) = RealDebridOAuthState("client", "secret", "old", "refresh-old", if (expired) 1000 else 100_000)
    private inner class MemoryStore : RealDebridAuthStateStore {
        override var activeProfileId = 1
        var state: RealDebridOAuthState? = old()
        val manualKeys = mutableMapOf<Int, String>()
        override suspend fun ownerOfToken(token: String): Int? =
            if (state?.accessToken == token) 1 else manualKeys.entries.firstOrNull { it.value == token }?.key
        override suspend fun read(profileId: Int) = if (profileId == 1) state?.let { ProfileRealDebridOAuth(1, it) } else null
        override suspend fun replace(profileId: Int, expectedToken: String, state: RealDebridOAuthState): Boolean {
            if (activeProfileId != profileId || this.state?.accessToken != expectedToken) return false
            this.state = state
            return true
        }
    }
}
