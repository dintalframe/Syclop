package com.nuvio.tv.core.anime

import com.nuvio.tv.core.profile.ProfileManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

/** Public ID metadata only. Isolated from every account API, cookie jar and logging interceptor. */
@Singleton
class NativeAnimeEpisodeMappings internal constructor(
    client: OkHttpClient,
    private val clock: () -> Long,
    private val origin: () -> Pair<Int, Long> = { 0 to 0L },
    private val originChanges: Flow<*> = emptyFlow<Unit>(),
    timeoutMillis: Long = 8_000L
) {
    @Inject constructor(profiles: ProfileManager) : this(OkHttpClient(), { System.nanoTime() / 1_000_000L },
        { profiles.activeProfileId.value to profiles.activeProfileSelectionRevision }, profiles.activeProfileId)

    private val transport = client.newBuilder().authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(timeoutMillis.coerceIn(100L, 8_000L), TimeUnit.MILLISECONDS).build()
    private data class Cached(val value: NativeAnimeMappingRecord?, val expires: Long)
    private val cache = object : LinkedHashMap<Long, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cached>?) = size > 64
    }
    private val requests = Mutex()

    /** No network from sorting, metadata prefetch or a plain cache read. */
    internal fun cachedProof(nativeVideoId: String): NativeAnimeEpisodeProof? {
        val id = identity(nativeVideoId) ?: return null
        return synchronized(cache) { cache[id.first]?.takeIf { it.expires > clock() }?.value?.episodes?.get(id.second) }
    }

    /** At most one bounded request per uncached record. Caller and profile cancellation cancel the HTTP call. */
    internal suspend fun proof(nativeVideoId: String, isCurrent: () -> Boolean = { true }): NativeAnimeEpisodeProof? {
        val id = identity(nativeVideoId) ?: return null
        val selected = origin()
        fun current() = origin() == selected && isCurrent()
        if (!current()) throw CancellationException("The selected viewer changed.")
        return coroutineScope {
            val lookup = async {
                requests.withLock {
                    if (!current()) throw CancellationException("The selected viewer changed.")
                    synchronized(cache) { cache[id.first]?.takeIf { it.expires > clock() } }?.let { return@withLock it.value?.episodes?.get(id.second) }
                    val record = withTimeoutOrNull(10_000L) { fetch(id.first, ::current) }
                    if (!current()) throw CancellationException("The selected viewer changed.")
                    val ttl = if (record == null) NEGATIVE_TTL else POSITIVE_TTL
                    synchronized(cache) { cache[id.first] = Cached(record, deadline(clock(), ttl)) }
                    record?.episodes?.get(id.second)
                }
            }
            val watcher = launch {
                originChanges.collect {
                    if (!current()) lookup.cancel(CancellationException("The selected viewer changed."))
                }
            }
            try {
                val result = lookup.await()
                if (!current()) throw CancellationException("The selected viewer changed.")
                result
            } finally { watcher.cancel() }
        }
    }

    private suspend fun fetch(malId: Long, isCurrent: () -> Boolean): NativeAnimeMappingRecord? = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("https://api.ani.zip/mappings?mal_id=$malId")
            .header("Accept", "application/json").header("Accept-Encoding", "identity").get().build()
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val record = try {
                    response.use {
                        val body = it.body
                        if (!continuation.isActive || !isCurrent() || it.code != 200 || body == null ||
                            body.contentLength() > MAX_BODY || it.header("Content-Encoding").let { encoding -> encoding != null && encoding != "identity" } ||
                            body.contentType()?.subtype?.let { type -> type == "json" || type.endsWith("+json") } != true) return@use null
                        val source = body.source()
                        source.request(MAX_BODY + 1L)
                        if (!continuation.isActive || !isCurrent() || source.buffer.size > MAX_BODY) return@use null
                        val bytes = source.buffer.readByteArray()
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                        NativeAnimeMappingCodec.parse(text, malId)
                    }
                } catch (_: Exception) { null } // Never surface response text, URLs or arbitrary exception messages.
                if (continuation.isActive) continuation.resume(record)
            }
        })
    }

    internal companion object {
        private const val MAX_BODY = 2L * 1024L * 1024L
        private const val POSITIVE_TTL = 24L * 60L * 60L * 1000L
        private const val NEGATIVE_TTL = 5L * 60L * 1000L
        private val ID = Regex("^mal:([1-9][0-9]{0,9}):([1-9][0-9]{0,3})$")
        internal fun identity(value: String): Pair<Long, Int>? {
            val match = ID.matchEntire(value) ?: return null
            val id = match.groupValues[1].toLongOrNull() ?: return null
            val episode = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..4000 } ?: return null
            return id to episode
        }
        private fun deadline(now: Long, ttl: Long): Long = if (now > Long.MAX_VALUE - ttl) Long.MAX_VALUE else now + ttl
    }
}
