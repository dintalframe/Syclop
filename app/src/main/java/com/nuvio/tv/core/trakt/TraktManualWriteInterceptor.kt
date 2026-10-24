package com.nuvio.tv.core.trakt

import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Prevent OkHttp follow-up retries (including503 Retry-After:0) for manual writes. */
class TraktManualWriteInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val guarded = if(request.method in setOf("POST", "DELETE", "PUT", "PATCH")) {
            val original = request.body ?: ByteArray(0).toRequestBody(null)
            val oneShot = object : RequestBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
            }
            request.newBuilder().method(request.method, oneShot).build()
        } else request
        return chain.proceed(guarded)
    }
}
