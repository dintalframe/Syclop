package com.nuvio.tv.core.debrid

data class DebridDeviceAuthorization(
    val providerId: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val friendlyVerificationUrl: String,
    val intervalSeconds: Int,
    val expiresAt: String?,
    val checkToken: String? = null,
    val expiresAtMonotonicMillis: Long? = null,
    val profileId: Int? = null,
    val profileRevision: Long? = null,
    val expectedCredential: String? = null
) {
    override fun toString(): String = "DebridDeviceAuthorization(providerId=$providerId, codes=[redacted])"
}

sealed interface DebridDeviceAuthorizationTokenResult {
    data class Authorized(val accessToken: String, val realDebridOAuth: RealDebridOAuthState? = null) : DebridDeviceAuthorizationTokenResult {
        override fun toString(): String = "Authorized(accessToken=[redacted])"
    }
    data object Pending : DebridDeviceAuthorizationTokenResult
    data object Expired : DebridDeviceAuthorizationTokenResult
    data object Denied : DebridDeviceAuthorizationTokenResult
    data object SlowDown : DebridDeviceAuthorizationTokenResult
    data object Unsupported : DebridDeviceAuthorizationTokenResult
    data class Failed(val message: String?) : DebridDeviceAuthorizationTokenResult
}
