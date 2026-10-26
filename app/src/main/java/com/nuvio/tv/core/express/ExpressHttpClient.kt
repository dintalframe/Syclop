package com.nuvio.tv.core.express

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class ExpressException(message: String) : IOException(message)
enum class ExpressHttpFailure { REQUEST_FAILED, TIMEOUT, RATE_LIMITED }
class ExpressHttpException(
    val failure: ExpressHttpFailure,
    val retryAfterMillis: Long? = null
) : ExpressException(when (failure) {
    ExpressHttpFailure.REQUEST_FAILED -> "Provider network request failed."
    ExpressHttpFailure.TIMEOUT -> "Provider request timed out."
    ExpressHttpFailure.RATE_LIMITED -> "Provider is rate limited; its retry period has not elapsed."
})
data class ExpressDocument(val url: String, val text: String)

/** An isolated public-provider client: no shared auth, cookies, diagnostics or debrid headers. */
@Singleton
class ExpressHttpClient internal constructor(
    client: OkHttpClient,
    private val allowHttpForTests: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject constructor() : this(OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build())

    // Redirects must pass through validated destinations, origin limits and the search budget,
    // including when an internally supplied client has OkHttp's automatic redirects enabled.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private val permits = Semaphore(3)
    // A busy host cannot occupy all global HTTP slots. Cooldowns also apply across searches.
    private val hostPermits = ConcurrentHashMap<String, Semaphore>()
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun validatedUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw ExpressException("Enter a valid HTTPS provider URL.")
        if ((!url.isHttps && !allowHttpForTests) || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ExpressException("Provider URLs must use HTTPS without embedded credentials.")
        }
        return url
    }

    suspend fun get(
        value: String,
        maxBytes: Int = 4 * 1024 * 1024,
        beforeRedirect: ((HttpUrl) -> Unit)? = null
    ): ExpressDocument {
        var target = validatedUrl(value)
        repeat(4) { redirectIndex ->
            if (redirectIndex > 0) beforeRedirect?.invoke(target)
            val host = target.originKey()
            val result = hostPermits.getOrPut(host) { Semaphore(2) }.withPermit {
                val remaining = (cooldowns[host] ?: 0L) - clock()
                if (remaining > 0L) throw ExpressHttpException(ExpressHttpFailure.RATE_LIMITED, remaining)
                permits.withPermit { fetch(target, maxBytes) }
            }
            if (result.redirect == null) return ExpressDocument(target.toString(), result.text.orEmpty())
            target = validatedUrl(target.resolve(result.redirect)?.toString()
                ?: throw ExpressException("Provider returned an invalid redirect."))
        }
        throw ExpressException("Provider returned too many redirects.")
    }

    private data class Fetch(val text: String? = null, val redirect: String? = null)

    private suspend fun fetch(url: HttpUrl, maxBytes: Int): Fetch = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "Syclop/Express")
            .header("Accept", "application/json,text/html,application/xml;q=0.9,*/*;q=0.5").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ExpressHttpException(
                    if (e is InterruptedIOException) ExpressHttpFailure.TIMEOUT else ExpressHttpFailure.REQUEST_FAILED))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (response.code == 429) {
                            val wait = retryAfterMillis(response.header("Retry-After"), clock())
                            val until = clock().let { now -> if (Long.MAX_VALUE - now < wait) Long.MAX_VALUE else now + wait }
                            cooldowns.merge(url.originKey(), until, ::maxOf)
                            throw ExpressHttpException(ExpressHttpFailure.RATE_LIMITED, wait)
                        }
                        if (response.code in 300..399) {
                            val location = response.header("Location") ?: throw ExpressException("Provider redirect has no destination.")
                            if (continuation.isActive) continuation.resume(Fetch(redirect = location))
                            return
                        }
                        if (!response.isSuccessful) throw ExpressException("Provider returned HTTP ${response.code}.")
                        val body = response.body ?: throw ExpressException("Provider returned an empty response.")
                        if (body.contentLength() > maxBytes) throw ExpressException("Provider response exceeds the size limit.")
                        val output = ByteArrayOutputStream()
                        val input = body.byteStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > maxBytes) throw ExpressException("Provider response exceeds the size limit.")
                            output.write(buffer, 0, count)
                        }
                        if (continuation.isActive) continuation.resume(Fetch(text = output.toString("UTF-8")))
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        error as? ExpressException ?: ExpressException("Provider response could not be read safely."))
                }
            }
        })
    }

    private fun HttpUrl.originKey(): String = "$scheme://$host:$port"

    internal fun retryAfterMillis(value: String?, now: Long): Long {
        value?.trim()?.toLongOrNull()?.takeIf { it >= 0L }?.let {
            return if (it > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else it * 1_000L
        }
        val date = value?.let { runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT"); isLenient = false
            }.parse(it)?.time
        }.getOrNull() }
        return date?.let { (it - now).coerceAtLeast(0L) } ?: 60_000L
    }
}
