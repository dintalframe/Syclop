package com.nuvio.tv.core.trakt

import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

class TraktAppRegistrationTest {
    @Test fun `public application client ID enables normal device auth without a client secret`() {
        val registration = validatedTraktRegistration("  syclop-public-client-1234  ", "")
        assertTrue(registration.isConfigured)
        assertEquals("syclop-public-client-1234", registration.clientId)
        assertEquals(TraktAppRegistration.DEFAULT_REDIRECT_URI, registration.redirectUri)
        assertFalse(registration.toString().contains(registration.clientId))
        assertFalse(validatedTraktRegistration("", "").isConfigured)
    }

    @Test fun `application registration rejects malformed values without requesting a user password`() {
        assertTrue(runCatching { validatedTraktRegistration("not a client id", "") }.isFailure)
        assertTrue(runCatching { validatedTraktRegistration("syclop-public-client-1234", "http://untrusted/callback") }.isFailure)
        assertTrue(runCatching { validatedTraktRegistration("syclop-public-client-1234", "syclop://trakt/callback") }.isFailure)
        assertEquals("https://github.com/dintalframe", validatedTraktRegistration("syclop-public-client-1234", "https://github.com/dintalframe").redirectUri)
    }

    @Test fun `native OAuth payloads contain no client secret and diagnostics redact user tokens`() {
        val moshi = Moshi.Builder().build()
        val poll = TraktDeviceTokenRequestDto("device-private", "syclop-client")
        val refresh = TraktRefreshTokenRequestDto("refresh-private", "syclop-client", TraktAppRegistration.DEFAULT_REDIRECT_URI)
        val revoke = TraktRevokeRequestDto("access-private", "syclop-client")
        for (json in listOf(moshi.adapter(TraktDeviceTokenRequestDto::class.java).toJson(poll),
            moshi.adapter(TraktRefreshTokenRequestDto::class.java).toJson(refresh),
            moshi.adapter(TraktRevokeRequestDto::class.java).toJson(revoke))) {
            assertFalse(json.contains("client_secret"))
        }
        for (value in listOf(poll, refresh, revoke)) assertFalse(value.toString().contains("private"))
    }
}
