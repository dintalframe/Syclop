package com.nuvio.tv.data.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SimklV2TokenManagerTest {
    @Test fun `concurrent refreshes share one replacement and retain nonrotating refresh token`() = runTest {
        val h = Harness(); h.connect(expiresAt = h.now)
        h.responses += tokens("fresh-access")
        val expected = h.store.authorization()!!
        val authorizations = (1..5).map { async { h.manager.authorization(expected, false) } }.awaitAll()
        assertTrue(authorizations.all { it.accessToken == "fresh-access" })
        assertEquals(1, h.requests.size)
        assertEquals("test-refresh", h.store.oauthAuthorization()?.tokens?.refreshToken)
        assertEquals(h.now + 180L * 24 * 60 * 60 * 1000, h.store.oauthAuthorization()?.tokens?.refreshExpiresAtEpochMs)
    }

    @Test fun `failure to save refreshed pair preserves prior credentials and state`() = runTest {
        val h = Harness(); h.connect(expiresAt = h.now)
        h.responses += tokens("fresh-access"); h.persistence.failWrites = true
        try { h.manager.authorization(h.store.authorization()!!, false); fail("Expected persistence error") }
        catch (_: java.io.IOException) { }
        assertEquals("old-access", h.store.authorization()?.accessToken)
    }

    @Test fun `temporary refresh failure does not erase the existing grant`() = runTest {
        val h = Harness(); h.connect(expiresAt = h.now); h.responses += oauthErrorResponse("temporarily_unavailable", 503)
        try { h.manager.authorization(h.store.authorization()!!, false); fail("Expected temporary error") }
        catch (e: SimklAuthException) { assertEquals(SimklAuthError.NETWORK, e.error) }
        assertEquals("old-access", h.store.authorization()?.accessToken)
    }

    @Test fun `invalid refresh grant clears only its current profile`() = runTest {
        val h = Harness(); h.connect(expiresAt = h.now); h.responses += oauthErrorResponse("invalid_grant")
        try { h.manager.authorization(h.store.authorization()!!, false); fail("Expected reauthorization") }
        catch (e: SimklAuthException) { assertEquals(SimklAuthError.AUTHORIZATION_REVOKED, e.error) }
        assertNull(h.store.authorization())
    }

    @Test fun `profile change during refresh cannot save a token under another viewer`() = runTest {
        val h = Harness(); h.connect(expiresAt = h.now); h.responses += tokens("late-access")
        h.beforeResponse = { h.store.selectProfile(2) }
        try { h.manager.authorization(h.store.authorization()!!, false); fail("Expected profile cancellation") }
        catch (_: CancellationException) { }
        assertNull(h.store.authorization())
        h.store.selectProfile(1)
        assertEquals("old-access", h.store.authorization()?.accessToken)
    }

    @Test fun `old profile ticket is rejected before any network call`() = runTest {
        val h = Harness(); h.connect(); val old = h.store.authorization()!!
        h.store.selectProfile(2); h.store.selectProfile(1)
        try { h.manager.authorization(old, true); fail("Expected profile cancellation") }
        catch (_: CancellationException) { }
        assertTrue(h.requests.isEmpty())
    }

    @Test fun `normal request refreshes on401 and retries once even with no transient retries`() = runTest {
        val h = Harness(); h.connect(); h.responses += tokens("fresh-access")
        val seen = mutableListOf<String?>()
        val replies = ArrayDeque(listOf(oauthErrorResponse("invalid_token", 401), SimklRawHttpResponse(200, "{}")))
        val client = h.client(SimklHttpEngine { _, _, headers, _ -> seen += headers["Authorization"]; replies.removeFirst() })
        assertEquals(200, client.execute(SimklApiRequest(SimklHttpMethod.GET, "/users/settings",
            retryPolicy = SimklRetryPolicy.NEVER)).status)
        assertEquals(listOf("Bearer old-access", "Bearer fresh-access"), seen)
        assertEquals(1, h.requests.size)
    }

    @Test fun `second401 does not enter an infinite refresh loop`() = runTest {
        val h = Harness(); h.connect(); h.responses += tokens("fresh-access")
        var calls = 0
        val client = h.client(SimklHttpEngine { _, _, _, _ -> calls++; oauthErrorResponse("invalid_token", 401) })
        try { client.execute(SimklApiRequest(SimklHttpMethod.GET, "/users/settings")); fail("Expected authentication failure") }
        catch (e: SimklApiException) { assertEquals(401, e.status) }
        assertEquals(2, calls); assertEquals(1, h.requests.size)
        assertNull(h.store.authorization())
    }

    @Test fun `missing header error is not treated as token expiry`() = runTest {
        val h = Harness(); h.connect()
        val client = h.client(SimklHttpEngine { _, _, _, _ -> oauthErrorResponse("user_token_required", 401) })
        try { client.execute(SimklApiRequest(SimklHttpMethod.GET, "/users/settings")); fail("Expected header failure") }
        catch (e: SimklApiException) { assertEquals("user_token_required", e.errorCode) }
        assertTrue(h.requests.isEmpty()); assertNotNull(h.store.authorization())
    }

    private class Harness {
        val now = 1_000_000L
        val persistence = MemorySimklV2Persistence()
        val store = SimklV2AuthStore(persistence)
        val configuration = SimklApiConfiguration("test-public-client", "syclop", "test")
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val responses = ArrayDeque<SimklRawHttpResponse>()
        var beforeResponse: (() -> Unit)? = null
        val oauth = SimklV2OAuthClient(SimklV2OAuthTransport { path, fields ->
            requests += path to fields; delay(1); beforeResponse?.invoke(); responses.removeFirst()
        }, configuration)
        val manager = SimklV2TokenManager(store, oauth) { now }
        fun connect(expiresAt: Long = now + 600_000) {
            check(store.saveV2Tokens(SimklV2Tokens("old-access", "test-refresh", expiresAt,
                now + 20_000_000, "media:read media:write"), store.currentScope()))
        }
        fun client(engine: SimklHttpEngine) = SimklApiClient(engine, configuration, store::authorization,
            { auth -> store.clearAuth(SimklAuthError.AUTHORIZATION_REVOKED, auth.scope, auth.accessToken) },
            nowEpochMs = { now }, sleep = { }, retryJitterMs = { 0 }, renewAuthorization = manager::authorization)
    }
}
