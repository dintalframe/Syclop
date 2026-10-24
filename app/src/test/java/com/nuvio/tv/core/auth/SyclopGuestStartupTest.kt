package com.nuvio.tv.core.auth

import com.nuvio.tv.core.network.SYCLOP_DISABLED_CLOUD_KEY
import com.nuvio.tv.core.network.SYCLOP_DISABLED_CLOUD_URL
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class SyclopGuestStartupTest {
    @Test
    fun localStartupAndSignOutPreservePrivateProfileAndAddonData() = runBlocking {
        val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
        val manager = AuthManager(
            auth = mockk(relaxed = true),
            postgrest = mockk(relaxed = true),
            httpClient = OkHttpClient(),
            customServerHttpClient = OkHttpClient(),
            authSessionNoticeDataStore = mockk(relaxed = true),
            accountLocalDataResetService = resetService,
            authDiagnosticReportRepository = mockk(relaxed = true),
            serverConfiguration = ServerConfiguration(
                backendUrl = SYCLOP_DISABLED_CLOUD_URL,
                publishableKey = SYCLOP_DISABLED_CLOUD_KEY,
                capabilities = ServerCapabilities(emailPasswordAuth = false, tvLogin = false),
                isCustom = false
            )
        )
        assertEquals(AuthState.SignedOut, manager.authState.value)
        manager.signOut()
        assertEquals(AuthState.SignedOut, manager.authState.value)
        coVerify(exactly = 0) { resetService.clearAfterSignOut() }
    }
}
