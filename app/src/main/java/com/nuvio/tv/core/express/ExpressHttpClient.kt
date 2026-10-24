package com.nuvio.tv.core.express

import java.io.ByteArrayOutputStream
import java.io.IOException
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

class ExpressException(message: String) : IOException(message)
data class ExpressDocument(val url: String, val text: String)

/** An isolated public-provider client: no shared auth, cookies, diagnostics or debrid headers. */
@Singleton
class ExpressHttpClient internal constructor(
    private val client: OkHttpClient,
    private val allowHttpForTests: Boolean = false
) {
    @Inject constructor() : this(OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build())

    private val permits = Semaphore(3)

    fun validatedUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw ExpressException("Enter a valid HTTPS provider URL.")
        if ((!url.isHttps && !allowHttpForTests) || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ExpressException("Provider URLs must use HTTPS without embedded credentials.")
        }
        return url
    }

    suspend fun get(value: String, maxBytes: Int = 4 * 1024 * 1024): ExpressDocument = permits.withPermit {
        var target = validatedUrl(value)
        repeat(4) {
            val result = fetch(target, maxBytes)
            if (result.redirect == null) return@withPermit ExpressDocument(target.toString(), result.text.orEmpty())
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
                if (continuation.isActive) continuation.resumeWithException(ExpressException("Provider network request failed."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
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
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(ExpressException("Provider response could not be read safely."))
                }
            }
        })
    }
}
