package com.nuvio.tv.data.simkl.catalog

import com.nuvio.tv.data.simkl.SimklApiConfiguration
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Independent public CDN transport: it never receives an account client, cookie jar or token. */
@Singleton
class SimklPublicCatalogClient internal constructor(
    private val configuration: SimklApiConfiguration,
    client: OkHttpClient,
    private val clock: () -> Long,
    timeoutMillis: Long = 18_000L
) {
    @Inject constructor(configuration: SimklApiConfiguration) : this(configuration, OkHttpClient(),
        { System.nanoTime() / 1_000_000L })
    private val transport = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(timeoutMillis.coerceIn(100, 18_000), TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).cache(null)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).build()

    private data class Cached(val value: Any, val expiresAt: Long)
    private val cache = object : LinkedHashMap<String, Cached>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 12
    }
    private val locks = Array(16) { Mutex() }
    private val requests = Semaphore(2)
    val configured: Boolean get() = configuration.clientId.matches(Regex("[A-Za-z0-9_-]{8,128}")) &&
        configuration.appName.matches(Regex("[a-z0-9_-]{1,64}")) &&
        configuration.appVersion.length in 1..80 && configuration.appVersion.none(Char::isISOControl)

    internal suspend fun trending(type: SimklFeedType, period: String, current: () -> Boolean): List<SimklPublicItem> {
        require(period in setOf("today", "week", "month")) { "Unknown Simkl period." }
        val path = "/discover/trending/${type.path}/${period}_100.json"
        return cached(path, current, ITEMS_LIMIT) { SimklPublicCatalogCodec.items(it, type) }
    }

    internal suspend fun genre(type: SimklFeedType, genre: String, current: () -> Boolean): List<SimklPublicItem> {
        // A selected genre always comes from the official index, never a constructed guessed slug.
        val entry = genres(type, current).singleOrNull { it.name.equals(genre, ignoreCase = true) }
            ?: return emptyList()
        val path = "/discover/trending/${type.path}/genre/${entry.slug}_month_500.json"
        return cached(path, current, ITEMS_LIMIT) { SimklPublicCatalogCodec.items(it, type) }.map { item ->
            // Simkl documents genre-file membership as authoritative, including when item.genres omits it.
            item.copy(preview = item.preview.copy(genres = (item.preview.genres + entry.name).distinct()))
        }
    }

    internal suspend fun genres(type: SimklFeedType, current: () -> Boolean): List<SimklGenre> =
        cached("/discover/trending/${type.path}/genre/index.json", current, INDEX_LIMIT,
            SimklPublicCatalogCodec::genres)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(path: String, current: () -> Boolean, limit: Int, decode: (String) -> T): T {
        require(configured) { "Syclop's Simkl application is not configured." }
        checkCurrent(current)
        val key = "${configuration.clientId}|$path"
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            checkCurrent(current)
            synchronized(cache) { cache[key]?.takeIf { it.expiresAt > clock() }?.value as? T }?.let {
                checkCurrent(current)
                return@withLock it
            }
            val value = requests.withPermit {
                checkCurrent(current)
                val body = request(path, limit)
                checkCurrent(current)
                withContext(Dispatchers.Default) { decode(body) }
            }
            checkCurrent(current)
            // CDN documents max-age=3600; cache only parsed validated data, not duplicate raw JSON.
            synchronized(cache) { cache[key] = Cached(value, deadline(clock(), CACHE_TTL_MS)) }
            value
        }
    }

    private suspend fun checkCurrent(current: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("The active catalog profile changed.")
    }

    private suspend fun request(path: String, limit: Int): String {
        val url = "https://data.simkl.in".toHttpUrl().newBuilder().encodedPath(path)
            .addQueryParameter("client_id", configuration.clientId)
            .addQueryParameter("app-name", configuration.appName)
            .addQueryParameter("app-version", configuration.appVersion).build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Accept-Encoding", "identity").header("User-Agent", "Syclop/${configuration.appVersion}").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Simkl catalog request failed."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use { result ->
                            if (result.code != 200) throw IOException("Simkl catalog request was rejected.")
                            val body = result.body ?: throw IOException("Simkl catalog response is empty.")
                            if (result.header("Content-Encoding").let { it != null && it != "identity" } ||
                                body.contentType()?.subtype?.let { it == "json" || it.endsWith("+json") } != true)
                                throw IOException("Simkl catalog response has an unsupported format.")
                            if (body.contentLength() > limit) throw IOException("Simkl catalog response is too large.")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8_192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Simkl catalog request cancelled.")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > limit) throw IOException("Simkl catalog response is too large.")
                                    output.write(buffer, 0, count)
                                }
                            }
                            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Simkl catalog response is unavailable or invalid."))
                    }
                }
            })
        }
    }

    companion object {
        internal const val ITEMS_LIMIT = 4 * 1024 * 1024
        internal const val INDEX_LIMIT = 128 * 1024
        internal const val CACHE_TTL_MS = 60 * 60 * 1000L
        private fun deadline(now: Long, ttl: Long) = if (now > Long.MAX_VALUE - ttl) Long.MAX_VALUE else now + ttl
    }
}
