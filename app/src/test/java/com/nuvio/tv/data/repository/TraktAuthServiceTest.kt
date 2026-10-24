package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.trakt.TraktAppRegistration
import com.nuvio.tv.core.trakt.TraktAppRegistrationStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktAuthServiceTest {
    @Test
    fun `refresh token 400 clears credentials and prevents another refresh`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>()
        var authState = authenticatedState().copy(registrationClientId = "syclop-client-id-123456")
        var grantRevision = 0L

        every { traktAuthDataStore.activeProfileId } returns 1
        every { traktAuthDataStore.activeProfileSelectionRevision } returns 0L
        every { traktAuthDataStore.grantRevision(1) } answers { grantRevision }
        coEvery { traktAuthDataStore.getCurrentState() } answers { authState }
        coEvery { traktAuthDataStore.getCurrentState(1) } answers { authState }
        coEvery { traktAuthDataStore.clearAuthIfCurrent(any(), any(), any()) } coAnswers {
            if (firstArg<TraktAuthState>() == authState && secondArg<Long>() == grantRevision && thirdArg<() -> Boolean>()()) {
                authState = TraktAuthState(profileId = 1)
                grantRevision += 1L
                true
            } else false
        }
        coEvery { authSessionNoticeDataStore.markTraktReconnectRequired() } returns Unit
        coEvery { traktApi.refreshToken(any()) } returns Response.error(400, "invalid_grant".toResponseBody())

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore,
            appRegistrationStore = mockk<TraktAppRegistrationStore> {
                every { current() } returns TraktAppRegistration("syclop-client-id-123456")
            }
        )

        assertFalse(service.refreshTokenIfNeeded(force = true))
        assertFalse(service.refreshTokenIfNeeded(force = true))

        coVerify(exactly = 1) { traktApi.refreshToken(any()) }
        coVerify(exactly = 1) { authSessionNoticeDataStore.markTraktReconnectRequired() }
        coVerify(exactly = 1) { traktAuthDataStore.clearAuthIfCurrent(any(), 0L, any()) }
        coVerify(exactly = 0) { traktAuthDataStore.clearAuthForProfile(any()) }
    }

    @Test
    fun `public application ID alone can start viewer device authorization`() = runTest {
        val api = mockk<TraktApi>()
        val auth = mockk<TraktAuthDataStore>()
        val registration = TraktAppRegistration("syclop-client-id-123456")
        val code = TraktDeviceCodeResponseDto("private-device", "ABCD1234", "https://auth.trakt.tv/activate", 600, 5)
        coEvery { auth.getCurrentState() } returns TraktAuthState(profileId = 3)
        coEvery { auth.saveDeviceFlow(code, 3, registration.clientId) } returns Unit
        coEvery { api.requestDeviceCode(any()) } returns Response.success(code)
        val service = TraktAuthService(mockk(relaxed = true), api, auth, mockk(relaxed = true),
            mockk<TraktAppRegistrationStore> { every { current() } returns registration })
        assertTrue(service.hasRequiredCredentials())
        assertTrue(service.startDeviceAuth().isSuccess)
        coVerify { api.requestDeviceCode(match { it.clientId == registration.clientId }) }
        coVerify { auth.saveDeviceFlow(code, 3, registration.clientId) }
    }

    @Test
    fun `replacing application registration clears only the mismatched profile token`() = runTest {
        val api = mockk<TraktApi>()
        val auth = mockk<TraktAuthDataStore>()
        coEvery { auth.getCurrentState() } returns authenticatedState().copy(profileId = 7, registrationClientId = "old-syclop-client")
        coEvery { auth.clearAuthForProfile(7) } returns Unit
        val service = TraktAuthService(mockk(relaxed = true), api, auth, mockk(relaxed = true),
            mockk<TraktAppRegistrationStore> { every { current() } returns TraktAppRegistration("new-syclop-client-123456") })
        assertFalse(service.getCurrentAuthState().isAuthenticated)
        coVerify(exactly = 1) { auth.clearAuthForProfile(7) }
        coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    private fun authenticatedState(): TraktAuthState {
        return TraktAuthState(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            tokenType = "bearer",
            createdAt = 1L,
            expiresIn = 3600
        )
    }
}
