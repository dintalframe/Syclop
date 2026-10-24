package com.nuvio.tv.core.debrid

import com.google.gson.JsonParser
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.dto.PremiumizeDeviceTokenDto
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response

/** Premiumize's public TV flow; manual API keys never require this app registration. */
class PremiumizeDeviceAuth internal constructor(
    private val api: PremiumizeApi,
    private val monotonicMillis: () -> Long
) {
    @Inject constructor(api: PremiumizeApi) : this(api, { System.nanoTime() / 1_000_000L })

    suspend fun start(clientId: String): DebridDeviceAuthorization? {
        require(clientId.isNotBlank())
        currentCoroutineContext().ensureActive()
        val response = api.startDeviceAuthorization(clientId = clientId)
        currentCoroutineContext().ensureActive()
        if (!response.isSuccessful) { response.errorBody()?.close(); return null }
        val data = response.body() ?: return null
        val device = data.deviceCode?.takeIf { it.length in 1..4096 && it.none { c -> c <= ' ' } } ?: return null
        val user = data.userCode?.takeIf { it.matches(Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}")) } ?: return null
        val lifetime = data.expiresIn?.takeIf { it in 1..3600 } ?: return null
        val interval = data.interval?.takeIf { it in 1..300 }?.coerceAtLeast(5) ?: return null
        val verification = data.verificationUri?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host in setOf("www.premiumize.me", "premiumize.me") && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/device" &&
                it.query == null && it.fragment == null
        } ?: return null
        val deadline = runCatching { Math.addExact(monotonicMillis(), lifetime * 1000L) }.getOrNull() ?: return null
        // Rebuild the QR from the validated endpoint and public user code, never response-supplied extras.
        return DebridDeviceAuthorization(DebridProviders.PREMIUMIZE_ID, device, user,
            verification.toString(), verification.newBuilder().addQueryParameter("user_code", user).build().toString(),
            interval, "${lifetime}s", expiresAtMonotonicMillis = deadline)
    }

    suspend fun poll(clientId: String, session: DebridDeviceAuthorization): DebridDeviceAuthorizationTokenResult {
        require(clientId.isNotBlank())
        currentCoroutineContext().ensureActive()
        if (session.providerId != DebridProviders.PREMIUMIZE_ID || session.deviceCode.isBlank() ||
            session.expiresAtMonotonicMillis == null) return DebridDeviceAuthorizationTokenResult.Failed(null)
        if (expired(session)) return DebridDeviceAuthorizationTokenResult.Expired
        val response = api.redeemDeviceAuthorization(deviceCode = session.deviceCode, clientId = clientId)
        currentCoroutineContext().ensureActive()
        if (expired(session)) { response.errorBody()?.close(); return DebridDeviceAuthorizationTokenResult.Expired }
        if (response.isSuccessful) {
            val body = response.body()
            val token = body?.accessToken?.takeIf { it.length in 1..4096 && it.none { c -> c <= ' ' } }
            if (body != null && token != null && body.tokenType.equals("Bearer", true) &&
                body.scope.orEmpty().split(Regex("\\s+")).contains("full") &&
                body.expiresIn?.let { it in 1..31_536_000 } == true) {
                return DebridDeviceAuthorizationTokenResult.Authorized(token)
            }
        }
        return when (errorCode(response)) {
            "authorization_pending" -> DebridDeviceAuthorizationTokenResult.Pending
            "slow_down" -> DebridDeviceAuthorizationTokenResult.SlowDown
            "invalid_grant", "expired_token" -> DebridDeviceAuthorizationTokenResult.Expired
            "access_denied" -> DebridDeviceAuthorizationTokenResult.Denied
            else -> DebridDeviceAuthorizationTokenResult.Failed(null)
        }
    }

    private fun expired(session: DebridDeviceAuthorization) =
        session.expiresAtMonotonicMillis?.let { monotonicMillis() >= it } == true

    private fun errorCode(response: Response<PremiumizeDeviceTokenDto>): String? {
        if (response.isSuccessful) return response.body()?.error
        val text = response.errorBody()?.use { body ->
            val source = body.source()
            source.request(8193L)
            if (source.buffer.size > 8192L) null else source.buffer.readUtf8()
        } ?: return null
        return runCatching { JsonParser.parseString(text).asJsonObject.get("error")?.asString }.getOrNull()
            ?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
    }
}
