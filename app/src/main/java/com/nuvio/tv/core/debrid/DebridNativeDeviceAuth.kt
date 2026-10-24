package com.nuvio.tv.core.debrid

import com.google.gson.JsonParser
import com.nuvio.tv.data.remote.api.AllDebridAuthApi
import com.nuvio.tv.data.remote.api.RealDebridOAuthApi
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response

/** Public service flows; secrets and untrusted server messages never enter diagnostics. */
@Singleton
class DebridNativeDeviceAuth internal constructor(
    private val realDebrid: RealDebridOAuthApi,
    private val allDebrid: AllDebridAuthApi,
    private val epochMillis: () -> Long,
    private val monotonicMillis: () -> Long
) {
    @Inject constructor(realDebrid: RealDebridOAuthApi, allDebrid: AllDebridAuthApi) :
        this(realDebrid, allDebrid, System::currentTimeMillis, { System.nanoTime() / 1_000_000L })

    suspend fun start(providerId: String): DebridDeviceAuthorization? {
        return when (providerId) {
        DebridProviders.REAL_DEBRID_ID -> {
            val response = realDebrid.deviceCode(OPEN_SOURCE_CLIENT_ID)
            val data = response.body()?.takeIf { response.isSuccessful }
            response.errorBody()?.close()
            val code = data?.deviceCode?.takeIf { it.isNotBlank() } ?: return null
            val userCode = data.userCode?.takeIf { it.matches(Regex("[A-Za-z0-9-]{2,64}")) } ?: return null
            val expires = data.expiresIn?.takeIf { it in 1..3600 } ?: return null
            val url = safeVerificationUrl(data.verificationUrl, "real-debrid.com", "/device") ?: return null
            DebridDeviceAuthorization(providerId, code, userCode, url, url,
                data.interval?.takeIf { it in 1..60 }?.coerceAtLeast(5) ?: 5,
                "${expires}s", expiresAtMonotonicMillis = monotonicMillis() + expires * 1000L)
        }
        DebridProviders.ALL_DEBRID_ID -> {
            val response = allDebrid.getPin()
            val data = response.body()?.takeIf { response.isSuccessful && it.successful }?.data
            response.errorBody()?.close()
            val pin = data?.pin?.takeIf { it.matches(Regex("[A-Za-z0-9]{2,32}")) } ?: return null
            val check = data.check?.takeIf { it.isNotBlank() } ?: return null
            val expires = data.expiresIn?.takeIf { it in 1..600 } ?: return null
            if (safeVerificationUrl(data.userUrl, "alldebrid.com", "/pin/") == null) return null
            val base = safeVerificationUrl(data.baseUrl, "alldebrid.com", "/pin/") ?: return null
            val complete = base.toHttpUrlOrNull()!!.newBuilder().query(null).setQueryParameter("pin", pin).build().toString()
            DebridDeviceAuthorization(providerId, pin, pin, base, complete, 5, "${expires}s",
                checkToken = check, expiresAtMonotonicMillis = monotonicMillis() + expires * 1000L)
        }
        else -> null
        }
    }

    suspend fun poll(session: DebridDeviceAuthorization): DebridDeviceAuthorizationTokenResult {
        if (expired(session)) return DebridDeviceAuthorizationTokenResult.Expired
        return when (session.providerId) {
            DebridProviders.ALL_DEBRID_ID -> {
                val check = session.checkToken ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                val response = allDebrid.checkPin(session.userCode, check)
                val envelope = response.body()
                response.errorBody()?.close()
                if (response.code() == 429 || response.code() == 503) return DebridDeviceAuthorizationTokenResult.SlowDown
                if (envelope?.error?.code in setOf("PIN_EXPIRED", "PIN_INVALID")) return DebridDeviceAuthorizationTokenResult.Expired
                val data = envelope?.takeIf { response.isSuccessful && it.successful }?.data
                    ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                if (expired(session) || data.expiresIn?.let { it <= 0 } == true) return DebridDeviceAuthorizationTokenResult.Expired
                when (data.activated) {
                    false -> DebridDeviceAuthorizationTokenResult.Pending
                    true -> data.apikey?.takeIf { it.isNotBlank() }?.let { DebridDeviceAuthorizationTokenResult.Authorized(it) }
                        ?: DebridDeviceAuthorizationTokenResult.Failed(null)
                    null -> DebridDeviceAuthorizationTokenResult.Failed(null)
                }
            }
            DebridProviders.REAL_DEBRID_ID -> {
                val response = realDebrid.deviceCredentials(OPEN_SOURCE_CLIENT_ID, session.deviceCode)
                if (!response.isSuccessful) return rdPollingFailure(response)
                val credentials = response.body() ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                val clientId = credentials.clientId?.takeIf { it.isNotBlank() } ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                val secret = credentials.clientSecret?.takeIf { it.isNotBlank() } ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                if (expired(session)) return DebridDeviceAuthorizationTokenResult.Expired
                val tokenResponse = realDebrid.token(clientId, secret, session.deviceCode)
                if (!tokenResponse.isSuccessful) return rdPollingFailure(tokenResponse, allowPending = false)
                val token = tokenResponse.body() ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                val access = token.accessToken?.takeIf { it.isNotBlank() } ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                val refresh = token.refreshToken?.takeIf { it.isNotBlank() } ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                val expires = token.expiresIn?.takeIf { it in 1..31_536_000 } ?: return DebridDeviceAuthorizationTokenResult.Failed(null)
                if (token.tokenType?.equals("Bearer", true) == false) return DebridDeviceAuthorizationTokenResult.Failed(null)
                if (expired(session)) return DebridDeviceAuthorizationTokenResult.Expired
                DebridDeviceAuthorizationTokenResult.Authorized(access,
                    RealDebridOAuthState(clientId, secret, access, refresh, epochMillis() + expires * 1000L))
            }
            else -> DebridDeviceAuthorizationTokenResult.Unsupported
        }
    }

    fun expired(session: DebridDeviceAuthorization): Boolean =
        session.expiresAtMonotonicMillis?.let { monotonicMillis() >= it } == true

    private fun rdPollingFailure(response: Response<*>, allowPending: Boolean = true): DebridDeviceAuthorizationTokenResult {
        val body = response.errorBody()?.use { error ->
            val source = error.source()
            source.request(8192)
            source.buffer.clone().readUtf8(minOf(source.buffer.size, 8192L))
        }
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val code = runCatching { json?.get("error_code")?.asInt }.getOrNull()
        val name = runCatching { json?.get("error")?.asString }.getOrNull()
        return when {
            response.code() == 429 || code in setOf(5, 34) || name == "slow_down" -> DebridDeviceAuthorizationTokenResult.SlowDown
            code in setOf(9, 14, 22) || name in setOf("access_denied", "authorization_declined") -> DebridDeviceAuthorizationTokenResult.Denied
            code == 8 || name in setOf("expired_token", "invalid_grant") -> DebridDeviceAuthorizationTokenResult.Expired
            allowPending && (name == "authorization_pending" || (response.code() == 403 && code == null)) -> DebridDeviceAuthorizationTokenResult.Pending
            else -> DebridDeviceAuthorizationTokenResult.Failed(null)
        }
    }

    companion object {
        // Published by RD specifically for open-source applications; not copied from another app.
        // https://api.real-debrid.com/ : Opensource Apps
        const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"

        internal fun safeVerificationUrl(value: String?, host: String, path: String): String? {
            val url = value?.toHttpUrlOrNull() ?: return null
            return url.takeIf { it.isHttps && it.host == host && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() && it.fragment == null &&
                it.encodedPath.trimEnd('/') == path.trimEnd('/') }?.toString()
        }
    }
}
