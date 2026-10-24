package com.nuvio.tv.data.simkl

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import java.io.IOException

class OkHttpSimklEngine(private val client: OkHttpClient) : SimklHttpEngine {
    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String
    ): SimklRawHttpResponse {
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE)
        when (method) {
            SimklHttpMethod.GET.name -> builder.get()
            SimklHttpMethod.POST.name -> builder.post(requestBody)
            SimklHttpMethod.DELETE.name -> builder.delete(requestBody)
            else -> throw IllegalArgumentException("Unsupported Simkl method")
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Simkl network request failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val result = SimklRawHttpResponse(it.code, readSimklResponseBodyOrEmpty(it.body),
                                it.headers.toMultimap().mapValues { entry -> entry.value.joinToString(",") })
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Simkl response could not be read"))
                    }
                }
            })
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal const val SIMKL_MAX_RESPONSE_BODY_BYTES = 64L * 1024L * 1024L

private fun readSimklResponseBodyOrEmpty(responseBody: ResponseBody?): String =
    responseBody?.let { body ->
        readSimklResponseBody(body.source(), body.contentLength())
    }.orEmpty()

internal fun readSimklResponseBody(
    source: BufferedSource,
    contentLength: Long,
    maxResponseBodyBytes: Long = SIMKL_MAX_RESPONSE_BODY_BYTES
): String {
    if (contentLength > maxResponseBodyBytes) {
        throw IOException("Simkl response body exceeds limit")
    }
    val buffer = Buffer()
    var totalBytes = 0L
    while (true) {
        val readSize = minOf(8_192L, maxResponseBodyBytes - totalBytes + 1L)
        val read = source.read(buffer, readSize)
        if (read == -1L) break
        totalBytes += read
        if (totalBytes > maxResponseBodyBytes) {
            throw IOException("Simkl response body exceeds limit")
        }
    }
    return buffer.readString(Charsets.UTF_8)
}
