package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.AllDebridAuthApi
import com.nuvio.tv.data.remote.api.RealDebridOAuthApi
import com.nuvio.tv.data.remote.dto.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class DebridNativeDeviceAuthTest {
    private val rd = mockk<RealDebridOAuthApi>()
    private val ad = mockk<AllDebridAuthApi>()
    private var elapsed = 1000L
    private val auth = DebridNativeDeviceAuth(rd, ad, { 10_000L }, { elapsed })

    @Test fun `AllDebrid public PIN flow ignores pending injected key and returns only activated key`() = runTest {
        coEvery { ad.getPin() } returns Response.success(AllDebridEnvelope("success", AllDebridPinDto("ABCD", "check-secret", 600,
            "https://alldebrid.com/pin/?pin=ABCD", "https://alldebrid.com/pin/")))
        coEvery { ad.checkPin("ABCD", "check-secret") } returnsMany listOf(
            Response.success(AllDebridEnvelope("success", AllDebridPinCheckDto(false, "must-not-save", 590))),
            Response.success(AllDebridEnvelope("success", AllDebridPinCheckDto(true, "native-key", 580))))
        val session = auth.start(DebridProviders.ALL_DEBRID_ID)!!
        assertEquals(5, session.intervalSeconds)
        assertEquals("https://alldebrid.com/pin/?pin=ABCD", session.friendlyVerificationUrl)
        assertEquals(DebridDeviceAuthorizationTokenResult.Pending, auth.poll(session))
        assertEquals("native-key", (auth.poll(session) as DebridDeviceAuthorizationTokenResult.Authorized).accessToken)
        assertFalse(session.toString().contains("check-secret"))
    }

    @Test fun `AllDebrid PIN errors expire and malformed authorization never succeeds`() = runTest {
        val session = adSession()
        coEvery { ad.checkPin("ABCD", "check") } returnsMany listOf(
            Response.success(AllDebridEnvelope(error = AllDebridErrorDto("PIN_EXPIRED"), status = "error")),
            Response.success(AllDebridEnvelope("success", AllDebridPinCheckDto(true, null, 300))),
            Response.success(AllDebridEnvelope("success", AllDebridPinCheckDto(null, "injected", 300))))
        assertEquals(DebridDeviceAuthorizationTokenResult.Expired, auth.poll(session))
        assertTrue(auth.poll(session) is DebridDeviceAuthorizationTokenResult.Failed)
        assertTrue(auth.poll(session) is DebridDeviceAuthorizationTokenResult.Failed)
    }

    @Test fun `local PIN deadline expires without another request`() = runTest {
        elapsed = 700_000
        assertEquals(DebridDeviceAuthorizationTokenResult.Expired, auth.poll(adSession()))
    }

    @Test fun `verification URLs reject hostile hosts credentials ports and paths`() {
        listOf("http://alldebrid.com/pin/", "https://alldebrid.com.evil.test/pin/", "https://user@alldebrid.com/pin/",
            "https://alldebrid.com:444/pin/", "https://alldebrid.com/other", "https://alldebrid.com/pin/#secret")
            .forEach { assertNull(DebridNativeDeviceAuth.safeVerificationUrl(it, "alldebrid.com", "/pin/")) }
        assertNotNull(DebridNativeDeviceAuth.safeVerificationUrl("https://alldebrid.com/pin/", "alldebrid.com", "/pin/"))
    }

    @Test fun `RealDebrid open source flow uses returned user credentials and keeps refresh token`() = runTest {
        coEvery { rd.deviceCode(DebridNativeDeviceAuth.OPEN_SOURCE_CLIENT_ID, "yes") } returns Response.success(
            RealDebridDeviceCodeDto("device-secret", "ABCD12", 1, 1800, "https://real-debrid.com/device"))
        coEvery { rd.deviceCredentials(DebridNativeDeviceAuth.OPEN_SOURCE_CLIENT_ID, "device-secret") } returns Response.success(
            RealDebridDeviceCredentialsDto("user-client", "user-secret"))
        coEvery { rd.token("user-client", "user-secret", "device-secret", any()) } returns Response.success(
            RealDebridOAuthTokenDto("private-access-value", "refresh", 3600, "Bearer"))
        val session = auth.start(DebridProviders.REAL_DEBRID_ID)!!
        assertEquals(5, session.intervalSeconds)
        val token = auth.poll(session) as DebridDeviceAuthorizationTokenResult.Authorized
        assertEquals("private-access-value", token.accessToken)
        assertEquals("user-client", token.realDebridOAuth?.clientId)
        assertEquals("refresh", token.realDebridOAuth?.refreshToken)
        assertEquals(3_610_000L, token.realDebridOAuth?.expiresAtEpochMillis)
        assertFalse(token.toString().contains("private-access-value"))
        assertFalse(token.realDebridOAuth.toString().contains("user-secret"))
    }

    @Test fun `RealDebrid pending denial slowdown and expiry are distinct and bounded`() = runTest {
        val session = rdSession()
        coEvery { rd.deviceCredentials(any(), "device") } returnsMany listOf(
            error(403, "{}"), error(403, "{\"error_code\":9}"), error(429, "{\"error_code\":34}"),
            error(403, "{\"error_code\":8}"))
        assertEquals(DebridDeviceAuthorizationTokenResult.Pending, auth.poll(session))
        assertEquals(DebridDeviceAuthorizationTokenResult.Denied, auth.poll(session))
        assertEquals(DebridDeviceAuthorizationTokenResult.SlowDown, auth.poll(session))
        assertEquals(DebridDeviceAuthorizationTokenResult.Expired, auth.poll(session))
        elapsed = 100_000
        assertEquals(DebridDeviceAuthorizationTokenResult.Expired, auth.poll(session))
    }

    @Test fun `invalid expiry and hostile RealDebrid verification URL never start a session`() = runTest {
        coEvery { rd.deviceCode(any(), any()) } returnsMany listOf(
            Response.success(RealDebridDeviceCodeDto("d", "CODE", 5, -1, "https://real-debrid.com/device")),
            Response.success(RealDebridDeviceCodeDto("d", "CODE", 5, 1800, "https://evil.test/device")))
        assertNull(auth.start(DebridProviders.REAL_DEBRID_ID))
        assertNull(auth.start(DebridProviders.REAL_DEBRID_ID))
    }

    @Test fun `cancelled PIN request propagates cancellation`() = runTest {
        coEvery { ad.checkPin(any(), any()) } throws CancellationException("cancelled")
        try { auth.poll(adSession()); fail("Expected cancellation") } catch (_: CancellationException) { }
    }

    @Test fun `profile ticket rejects late login even after switching back`() {
        val guard = DebridAuthProfileGuard(1)
        val ticket = guard.capture(1)
        guard.observe(2)
        guard.observe(1)
        assertFalse(guard.valid(ticket, 1))
        assertTrue(guard.valid(guard.capture(1), 1))
    }

    private fun adSession() = DebridDeviceAuthorization(DebridProviders.ALL_DEBRID_ID, "ABCD", "ABCD", "", "", 5, null,
        checkToken = "check", expiresAtMonotonicMillis = 601_000)
    private fun rdSession() = DebridDeviceAuthorization(DebridProviders.REAL_DEBRID_ID, "device", "CODE", "", "", 5, null,
        expiresAtMonotonicMillis = 50_000)
    private fun <T> error(status: Int, body: String): Response<T> = Response.error(status, body.toResponseBody("application/json".toMediaType()))
}
