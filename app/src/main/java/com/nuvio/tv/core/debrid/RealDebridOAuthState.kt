package com.nuvio.tv.core.debrid

import androidx.annotation.Keep

/** Per-user credentials returned by RD, never a shared application secret. */
@Keep
data class RealDebridOAuthState(
    val clientId: String,
    val clientSecret: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long
) {
    override fun toString() = "RealDebridOAuthState([redacted])"
}

data class ProfileRealDebridOAuth(val profileId: Int, val state: RealDebridOAuthState) {
    override fun toString() = "ProfileRealDebridOAuth(profileId=$profileId, credentials=[redacted])"
}
