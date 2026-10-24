package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.dto.PremiumizeDeviceAuthorizationDto
import com.nuvio.tv.data.remote.dto.PremiumizeDeviceTokenDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class PremiumizeDeviceAuthTest {
    private val api = mockk<PremiumizeApi>()
    private var elapsed = 1_000L
    private val auth = PremiumizeDeviceAuth(api) { elapsed }

    @Test fun `valid TV response receives a monotonic deadline and safe prefilled QR`() = runTest {
        coEvery { api.startDeviceAuthorization(clientId = "own-public-client") } returns device()
        val session = auth.start("own-public-client")!!
        assertEquals(601_000L, session.expiresAtMonotonicMillis)
        assertEquals("https://www.premiumize.me/device?user_code=kpwx-3m7r", session.friendlyVerificationUrl)
        assertEquals(5, session.intervalSeconds)
        assertFalse(session.toString().contains("device-secret"))
    }

    @Test fun `real HTTP400 errors distinguish pending slowdown denial and expiry`() = runTest {
        val outcomes = listOf("authorization_pending" to DebridDeviceAuthorizationTokenResult.Pending,
            "slow_down" to DebridDeviceAuthorizationTokenResult.SlowDown,
            "access_denied" to DebridDeviceAuthorizationTokenResult.Denied,
            "invalid_grant" to DebridDeviceAuthorizationTokenResult.Expired,
            "expired_token" to DebridDeviceAuthorizationTokenResult.Expired)
        for ((error, expected) in outcomes) {
            coEvery { api.redeemDeviceAuthorization(deviceCode = "device-secret", clientId = "own-public-client") } returns
                Response.error(400, "{\"error\":\"$error\",\"error_description\":\"private-detail\"}".toResponseBody("application/json".toMediaType()))
            assertEquals(expected, auth.poll("own-public-client", session()))
        }
    }

    @Test fun `unknown blank oversized and malformed errors fail rather than poll forever`() = runTest {
        for (body in listOf("{}", "not json", "{\"error\":\"unknown\"}", " ".repeat(8193))) {
            coEvery { api.redeemDeviceAuthorization(deviceCode = any(), clientId = any()) } returns
                Response.error(400, body.toResponseBody("application/json".toMediaType()))
            assertTrue(auth.poll("own-public-client", session()) is DebridDeviceAuthorizationTokenResult.Failed)
        }
    }

    @Test fun `local expiry and missing deadlines prevent any additional poll`() = runTest {
        elapsed = 601_000L
        assertEquals(DebridDeviceAuthorizationTokenResult.Expired, auth.poll("own-public-client", session()))
        assertTrue(auth.poll("own-public-client", session().copy(expiresAtMonotonicMillis = null)) is DebridDeviceAuthorizationTokenResult.Failed)
        coVerify(exactly = 0) { api.redeemDeviceAuthorization(any(), any(), any()) }
    }

    @Test fun `approval arriving after the local deadline is discarded`() = runTest {
        coEvery { api.redeemDeviceAuthorization(deviceCode = any(), clientId = any()) } answers {
            elapsed = 601_000L; Response.success(token())
        }
        assertEquals(DebridDeviceAuthorizationTokenResult.Expired, auth.poll("own-public-client", session()))
    }

    @Test fun `malformed token envelopes are not saved as successful credentials`() = runTest {
        for (body in listOf(token().copy(tokenType = "Basic"), token().copy(scope = "read"),
            token().copy(expiresIn = 0), token().copy(accessToken = "bad token"))) {
            coEvery { api.redeemDeviceAuthorization(deviceCode = any(), clientId = any()) } returns Response.success(body)
            assertTrue(auth.poll("own-public-client", session()) is DebridDeviceAuthorizationTokenResult.Failed)
        }
        coEvery { api.redeemDeviceAuthorization(deviceCode = any(), clientId = any()) } returns Response.success(token())
        assertEquals("private-access", (auth.poll("own-public-client", session()) as DebridDeviceAuthorizationTokenResult.Authorized).accessToken)
    }

    @Test fun `hostile verification addresses and malformed lifetimes never start a session`() = runTest {
        for (url in listOf("http://www.premiumize.me/device", "https://premiumize.me.evil.test/device",
            "https://user@www.premiumize.me/device", "https://www.premiumize.me:444/device",
            "https://www.premiumize.me/device?next=elsewhere", "https://www.premiumize.me/other")) {
            coEvery { api.startDeviceAuthorization(clientId = any()) } returns device(url)
            assertNull(auth.start("own-public-client"))
        }
        coEvery { api.startDeviceAuthorization(clientId = any()) } returns Response.success(device().body()!!.copy(expiresIn = Int.MAX_VALUE))
        assertNull(auth.start("own-public-client"))
    }

    @Test fun `cancelled noncooperative response cannot return an approval`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { api.redeemDeviceAuthorization(deviceCode = any(), clientId = any()) } coAnswers {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); Response.success(token()) }
        }
        var approved = false
        val job = launch { auth.poll("own-public-client", session()); approved = true }
        runCurrent(); entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertFalse(approved)
    }

    @Test fun `blank app registration never contacts Premiumize`() = runTest {
        try { auth.start(""); fail("Expected missing app registration") } catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { api.startDeviceAuthorization(any(), any()) }
    }

    @Test fun `closing a dialog invalidates its pending profile ticket without disconnecting accounts`() {
        val guard = DebridAuthProfileGuard(1)
        val ticket = guard.capture(1)
        guard.invalidate()
        assertFalse(guard.valid(ticket, 1))
        assertTrue(guard.valid(guard.capture(1), 1))
    }

    @Test fun `slowdown never lowers an already-large server polling interval`() {
        assertEquals(10, nextDebridDevicePollInterval(5))
        assertEquals(125, nextDebridDevicePollInterval(120))
        assertEquals(305, nextDebridDevicePollInterval(300))
        assertEquals(Int.MAX_VALUE, nextDebridDevicePollInterval(Int.MAX_VALUE))
    }

    private fun session() = DebridDeviceAuthorization(DebridProviders.PREMIUMIZE_ID, "device-secret", "kpwx-3m7r",
        "https://www.premiumize.me/device", "https://www.premiumize.me/device?user_code=kpwx-3m7r", 5, "600s",
        expiresAtMonotonicMillis = 601_000L)
    private fun device(url: String = "https://www.premiumize.me/device") = Response.success(
        PremiumizeDeviceAuthorizationDto("device-secret", "kpwx-3m7r", url, "https://evil.test/ignored", 600, 5))
    private fun token() = PremiumizeDeviceTokenDto("private-access", "Bearer", 3600, "full")
}
