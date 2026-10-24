package com.nuvio.tv.data.simkl

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SimklAuthRepositoryTest {
    @Test fun `device flow sends public client and write scope without a browser secret or PKCE`() = runTest {
        val h = Harness(this)
        h.oauthResponses += device()
        val session = h.repository.startPinAuth()
        assertEquals("ABCD-EFGH", session.userCode)
        assertEquals("https://simkl.com/pin?user_code=ABCD-EFGH", session.verificationUriComplete)
        assertEquals(SimklConnectionMode.AWAITING_APPROVAL, h.storage.state.value.mode)
        val request = h.oauthRequests.single()
        assertEquals("/oauth2/device", request.first)
        assertEquals(mapOf("client_id" to "test-public-client", "scope" to "media:read media:write"), request.second)
    }

    @Test fun `successful approval saves full V2 pair and fetches viewer identity`() = runTest {
        val h = Harness(this); h.start()
        h.oauthResponses += tokens()
        assertEquals(SimklPinPollResult.Authorized, h.repository.pollPin())
        assertEquals("test-access", h.storage.oauthAuthorization()?.tokens?.accessToken)
        assertEquals("test-refresh", h.storage.oauthAuthorization()?.tokens?.refreshToken)
        assertEquals("Viewer", h.storage.state.value.username)
        assertEquals("GET", h.restRequests.single().first)
        assertTrue(h.restRequests.single().second.contains("/users/settings?"))
        assertNull(h.storage.state.value.pinSession)
        assertEquals(SimklV2OAuthClient.DEVICE_GRANT, h.oauthRequests.last().second["grant_type"])
        assertEquals("test-device-code", h.oauthRequests.last().second["device_code"])
    }

    @Test fun `identity fetch failure preserves approved encrypted authorization`() = runTest {
        val h = Harness(this); h.start(); h.settingsStatus = 400; h.oauthResponses += tokens()
        assertEquals(SimklPinPollResult.Authorized, h.repository.pollPin())
        assertTrue(h.storage.state.value.isAuthenticated)
        assertNull(h.storage.state.value.username)
    }

    @Test fun `decline remains pending only until the monotonic device deadline`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += oauthErrorResponse("authorization_pending")
        assertEquals(SimklPinPollResult.Pending, h.repository.pollPin())
        h.monotonic = 900_001; h.now = 0 // A wall-clock change must not extend the authorization.
        assertEquals(SimklPinPollResult.Expired, h.repository.pollPin())
        assertEquals(2, h.oauthRequests.size)
        assertEquals(SimklAuthError.PIN_EXPIRED, h.storage.state.value.error)
    }

    @Test fun `slow down adds five seconds and actually prevents an early network poll`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += oauthErrorResponse("slow_down")
        assertEquals(SimklPinPollResult.Pending, h.repository.pollPin())
        assertEquals(10, h.storage.state.value.pinSession?.intervalSeconds)
        h.advance(5_000)
        assertEquals(SimklPinPollResult.Pending, h.repository.pollPin())
        assertEquals(2, h.oauthRequests.size)
        h.advance(5_000); h.oauthResponses += oauthErrorResponse("authorization_pending")
        h.repository.pollPin()
        assertEquals(3, h.oauthRequests.size)
    }

    @Test fun `cancelled request cannot store a late successful approval`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += tokens()
        h.beforeOAuthResponse = { h.repository.cancelPinAuth() }
        assertEquals(SimklPinPollResult.Invalidated, h.repository.pollPin())
        assertNull(h.storage.oauthAuthorization())
        assertNull(h.storage.state.value.pinSession)
    }

    @Test fun `profile switch away and back invalidates a late approval`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += tokens()
        h.beforeOAuthResponse = { h.storage.selectProfile(2); h.storage.selectProfile(1) }
        assertEquals(SimklPinPollResult.Invalidated, h.repository.pollPin())
        assertNull(h.storage.oauthAuthorization())
    }

    @Test fun `cancel during device request prevents creating an orphan visible code`() = runTest {
        val h = Harness(this); h.oauthResponses += device()
        h.beforeOAuthResponse = { h.repository.cancelPinAuth() }
        try { h.repository.startPinAuth(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertNull(h.storage.state.value.pinSession)
    }

    @Test fun `missing client ID cannot contact the service`() = runTest {
        val h = Harness(this, clientId = "")
        try { h.repository.startPinAuth(); fail("Expected configuration error") }
        catch (e: SimklAuthException) { assertEquals(SimklAuthError.MISSING_CLIENT_ID, e.error) }
        assertTrue(h.oauthRequests.isEmpty())
    }

    @Test fun `untrusted verification destinations and malformed deadlines are rejected`() = runTest {
        for (body in listOf(
            deviceBody().replace("https://simkl.com/pin", "https://simkl.com.evil.example/pin"),
            deviceBody().replace("https://simkl.com/pin", "https://user@simkl.com/pin"),
            deviceBody().replace("https://simkl.com/pin", "https://simkl.com/pin?redirect=elsewhere"),
            deviceBody().replace("900", "9223372036854775807"),
            deviceBody().replace("\"interval\":5", "\"interval\":0")
        )) {
            val h = Harness(this); h.oauthResponses += SimklRawHttpResponse(200, body)
            try { h.repository.startPinAuth(); fail("Expected invalid response") }
            catch (e: SimklAuthException) { assertEquals(SimklAuthError.INVALID_PIN_RESPONSE, e.error) }
            assertFalse(h.storage.state.value.isAuthenticated)
        }
    }

    @Test fun `read only token is not saved as a fully connected tracking account`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += tokens(scope = "media:read")
        try { h.repository.pollPin(); fail("Expected missing write scope") }
        catch (e: SimklAuthException) { assertEquals(SimklAuthError.INSUFFICIENT_SCOPE, e.error) }
        assertNull(h.storage.oauthAuthorization())
    }

    @Test fun `disconnect clears locally before revoking only the captured viewer grant`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += tokens(); h.repository.pollPin()
        h.oauthResponses += SimklRawHttpResponse(200, "")
        h.repository.disconnect()
        assertNull(h.storage.oauthAuthorization())
        h.storage.selectProfile(2)
        runCurrent()
        assertEquals("/oauth2/revoke", h.oauthRequests.last().first)
        assertEquals("test-refresh", h.oauthRequests.last().second["token"])
        assertNull(h.storage.oauthAuthorization())
    }

    @Test fun `cancellation is propagated instead of converted into a network error`() = runTest {
        val h = Harness(this); h.beforeOAuthResponse = { throw CancellationException("Cancelled") }
        try { h.repository.startPinAuth(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertNull(h.storage.oauthAuthorization())
    }

    @Test fun `logout captured by viewer A cannot disconnect viewer B before coroutine starts`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += tokens(); h.repository.pollPin()
        val initiatingScope = h.repository.currentScope()
        h.storage.selectProfile(2)
        h.storage.saveV2Tokens(tokens("viewer-b", "refresh-b").v2Tokens(h.now), h.storage.currentScope())
        assertNull(h.repository.disconnect(initiatingScope))
        assertEquals("viewer-b", h.storage.authorization()?.accessToken)
        assertFalse(h.oauthRequests.any { it.first == "/oauth2/revoke" })
    }

    @Test fun `logout result cannot publish UI after another viewer becomes active`() = runTest {
        val h = Harness(this); h.start(); h.oauthResponses += tokens(); h.repository.pollPin()
        h.oauthResponses += SimklRawHttpResponse(200, "")
        val clearedScope = h.repository.disconnect(h.repository.currentScope())!!
        assertTrue(h.repository.isCurrentScope(clearedScope))
        h.storage.selectProfile(2)
        assertFalse(h.repository.isCurrentScope(clearedScope))
        runCurrent()
    }

    private class Harness(scope: CoroutineScope, clientId: String = "test-public-client") {
        var now = 1_000_000L
        var monotonic = 0L
        val persistence = MemorySimklV2Persistence()
        val storage = SimklV2AuthStore(persistence)
        val oauthResponses = ArrayDeque<SimklRawHttpResponse>()
        val oauthRequests = mutableListOf<Pair<String, Map<String, String>>>()
        var beforeOAuthResponse: (() -> Unit)? = null
        var settingsStatus = 200
        val restRequests = mutableListOf<Pair<String, String>>()
        val configuration = SimklApiConfiguration(clientId, "syclop", "test")
        val oauth = SimklV2OAuthClient(SimklV2OAuthTransport { path, fields ->
            oauthRequests += path to fields
            beforeOAuthResponse?.invoke()
            oauthResponses.removeFirst()
        }, configuration)
        val tokenManager = SimklV2TokenManager(storage, oauth) { now }
        val client = SimklApiClient(SimklHttpEngine { method, url, _, _ ->
            restRequests += method to url
            SimklRawHttpResponse(settingsStatus, """{"user":{"name":"Viewer"},"account":{"id":42}}""")
        }, configuration, storage::authorization, { }, nowEpochMs = { now },
            sleep = { advance(it) }, renewAuthorization = tokenManager::authorization)
        val repository = SimklAuthRepository(client, configuration, storage, oauth, { now }, { monotonic }, scope)
        fun advance(ms: Long) { now += ms; monotonic += ms }
        suspend fun start() { oauthResponses += device(); repository.startPinAuth(); advance(5_000) }
    }
}

internal class MemorySimklV2Persistence : SimklV2AuthPersistence {
    val values = mutableMapOf<Int, String>()
    var failWrites = false
    var failReadsFor: Set<Int> = emptySet()
    override fun read(profileId: Int): String? {
        if (profileId in failReadsFor) throw IOException("Test persistence read unavailable")
        return values[profileId]
    }
    override fun write(profileId: Int, value: String?) {
        if (failWrites) throw IOException("Test persistence unavailable")
        if (value == null) values.remove(profileId) else values[profileId] = value
    }
    override fun clear() { values.clear() }
}

internal fun deviceBody() = """{"device_code":"test-device-code","user_code":"ABCD-EFGH","verification_uri":"https://simkl.com/pin","expires_in":900,"interval":5}"""
internal fun device() = SimklRawHttpResponse(200, deviceBody())
internal fun oauthErrorResponse(code: String, status: Int = 400) = SimklRawHttpResponse(status, """{"error":"$code"}""")
internal fun tokens(access: String = "test-access", refresh: String = "test-refresh", scope: String = "media:read media:write") =
    SimklRawHttpResponse(200, """{"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer","expires_in":604800,"scope":"$scope"}""")
