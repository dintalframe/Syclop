package com.nuvio.tv.data.simkl

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

fun interface SimklV2OAuthTransport {
    suspend fun post(path: String, fields: Map<String, String>): SimklRawHttpResponse
}

/** No REST authorization, cookies, redirects, diagnostics, retries, or response cache. */
class OkHttpSimklV2OAuthTransport(
    private val configuration: SimklApiConfiguration,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
) : SimklV2OAuthTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()

    override suspend fun post(path: String, fields: Map<String, String>): SimklRawHttpResponse {
        require(path in setOf("/oauth2/device", "/oauth2/token", "/oauth2/revoke"))
        val base = configuration.baseUrl.toHttpUrl()
        require(base.isHttps || base.host in setOf("127.0.0.1", "localhost", "::1"))
        // Simkl requires application identity in every endpoint URL, including OAuth POSTs.
        // Private device/refresh codes remain exclusively in the form body.
        val url = base.newBuilder().encodedPath(path).query(null).fragment(null)
            .addQueryParameter("client_id", configuration.clientId)
            .addQueryParameter("app-name", configuration.appName)
            .addQueryParameter("app-version", configuration.appVersion).build()
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url(url).post(body)
            .header("User-Agent", "Syclop/${configuration.appVersion}")
            .header("Accept", "application/json").header("Cache-Control", "no-store").build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(SimklAuthException(SimklAuthError.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val text = it.body?.let { value ->
                                readSimklResponseBody(value.source(), value.contentLength(), 64 * 1024L)
                            }.orEmpty()
                            if (continuation.isActive) continuation.resume(SimklRawHttpResponse(
                                it.code, text, it.headers.toMultimap().mapValues { header -> header.value.joinToString(",") }
                            ))
                        }
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(SimklAuthException(SimklAuthError.NETWORK))
                    }
                }
            })
        }
    }
}

class SimklV2OAuthClient(
    private val transport: SimklV2OAuthTransport,
    private val configuration: SimklApiConfiguration
) {
    suspend fun start() = post("/oauth2/device", mapOf("scope" to REQUIRED_SCOPE))
    suspend fun poll(deviceCode: String) = post("/oauth2/token", mapOf(
        "grant_type" to DEVICE_GRANT, "device_code" to deviceCode
    ))
    suspend fun refresh(refreshToken: String) = post("/oauth2/token", mapOf(
        "grant_type" to "refresh_token", "refresh_token" to refreshToken
    ))
    suspend fun revoke(refreshToken: String) = post("/oauth2/revoke", mapOf(
        "token" to refreshToken, "token_type_hint" to "refresh_token"
    ))

    private suspend fun post(path: String, fields: Map<String, String>): SimklRawHttpResponse {
        if (configuration.clientId.isBlank()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        return try { transport.post(path, fields + ("client_id" to configuration.clientId)) }
        catch (error: CancellationException) { throw error }
        catch (error: SimklAuthException) { throw error }
        catch (_: Exception) { throw SimklAuthException(SimklAuthError.NETWORK) }
    }

    companion object {
        const val REQUIRED_SCOPE = "media:read media:write"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }
}

internal fun SimklRawHttpResponse.oauthError(): String? = runCatching {
    (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
}.getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }

internal fun SimklRawHttpResponse.v2Tokens(now: Long): SimklV2Tokens {
    if (status !in 200..299) throw SimklAuthException(
        if (oauthError() == "invalid_client") SimklAuthError.MISSING_CLIENT_ID else SimklAuthError.NETWORK
    )
    val payload = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<SimklV2TokenResponse>(body) }
        .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE) }
    val expires = payload.expiresIn?.takeIf { it in 1L..31_536_000L }
        ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    if (!payload.tokenType.equals("Bearer", true)) throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    val scope = payload.scope.orEmpty().split(Regex("\\s+")).filter(String::isNotBlank).toSet()
    if (!scope.containsAll(listOf("media:read", "media:write"))) throw SimklAuthException(SimklAuthError.INSUFFICIENT_SCOPE)
    if (now < 0 || now > Long.MAX_VALUE - maxOf(REFRESH_WINDOW_MS, expires * 1000L)) throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    return SimklV2Tokens(payload.accessToken.orEmpty(), payload.refreshToken.orEmpty(),
        now + expires * 1000L, now + REFRESH_WINDOW_MS, scope.sorted().joinToString(" "))
        .takeIf { it.isValid() } ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
}

private const val REFRESH_WINDOW_MS = 180L * 24 * 60 * 60 * 1000
