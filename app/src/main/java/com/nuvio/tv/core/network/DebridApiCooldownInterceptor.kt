package com.nuvio.tv.core.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** Shares provider cooldowns across native REST clients, including cloud requests. */
internal class DebridApiCooldownInterceptor(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : Interceptor {
    private data class Scope(val host: String, val account: ByteString, val endpoint: String?)

    private data class Cooldown(val startMs: Long, val durationMs: Long) {
        fun remainingMs(nowMs: Long): Long = durationMs - (nowMs - startMs).coerceAtLeast(0L)
    }

    private val cooldowns = LinkedHashMap<Scope, Cooldown>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host
        val isDebridApi = when (host) {
            "api.real-debrid.com" -> url.encodedPath.startsWith("/rest/1.0/")
            "api.alldebrid.com" -> true
            "api.torbox.app" -> url.encodedPath.startsWith("/v1/api/")
            "www.premiumize.me" -> url.encodedPath.startsWith("/api/")
            else -> false
        }
        if (!url.isHttps || url.port != 443 || !isDebridApi) return chain.proceed(request)
        val credential = request.header("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()?.takeIf(String::isNotEmpty) ?: return chain.proceed(request)
        // TorBox budgets are per endpoint, including a distinct uncached create limit.
        val endpoint = if (host == "api.torbox.app") "${request.method} ${url.encodedPath}" else null
        val scope = Scope(host, credential.encodeUtf8().sha256(), endpoint)
        val remainingMs = synchronized(cooldowns) {
            val remaining = cooldowns[scope]?.remainingMs(monotonicMillis()) ?: 0L
            if (remaining <= 0L) cooldowns.remove(scope)
            remaining
        }
        if (remainingMs > 0L) {
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(429).message("Debrid API cooldown")
                .header("Retry-After", ((remainingMs - 1L) / 1_000L + 1L).toString())
                .body("".toResponseBody()).build()
        }

        val response = chain.proceed(request)
        val responseUrl = response.request.url
        if (!responseUrl.isHttps || responseUrl.port != url.port || responseUrl.host != host) return response
        val retryAfter = response.header("Retry-After")
        if (response.code == 429 || host == "api.alldebrid.com" && shouldApplyBackendCooldown(response.code, retryAfter)) {
            val duration = retryAfterDelayMillis(retryAfter, System.currentTimeMillis(), 60_000L).coerceAtLeast(1_000L)
            synchronized(cooldowns) {
                val now = monotonicMillis()
                cooldowns.entries.removeAll { it.value.remainingMs(now) <= 0L }
                if (duration > (cooldowns[scope]?.remainingMs(now) ?: 0L)) {
                    cooldowns[scope] = Cooldown(now, duration)
                    if (cooldowns.size > 128) cooldowns.remove(cooldowns.keys.first())
                }
            }
        }
        return response
    }
}
