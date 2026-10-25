package com.nuvio.tv.data.catalog

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Endpoint-only clone: byte limits apply before Retrofit buffers errors or Moshi parses success. */
internal object RatedKidsHttpClient {
    const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    private val paths = setOf("/movies/popular", "/shows/popular")
    private val queryKeys = setOf("genres", "certifications", "subgenres", "extended", "page", "limit")

    fun create(base: OkHttpClient, testEndpointHost: String? = null): OkHttpClient {
        require(testEndpointHost == null || testEndpointHost in setOf("127.0.0.1", "localhost", "::1"))
        return base.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).cache(null).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor { chain ->
                val request = chain.request()
                if (!allowed(request.url, testEndpointHost) || request.method != "GET" || request.body != null ||
                    request.url.encodedPath !in paths || request.url.queryParameterNames.any { it !in queryKeys })
                    throw IOException("Unsupported public Kids catalog endpoint.")
                val response = chain.proceed(request.newBuilder().removeHeader("Authorization")
                    .removeHeader("Proxy-Authorization").removeHeader("Cookie").build())
                val body = response.body ?: return@addInterceptor response
                val subtype = body.contentType()?.subtype
                if (body.contentLength() > MAX_RESPONSE_BYTES ||
                    response.isSuccessful && subtype != "json" && subtype?.endsWith("+json") != true) {
                    body.close()
                    throw IOException("Public Kids catalog response exceeds its supported format or size.")
                }
                response.newBuilder().body(bounded(body)).build()
            }.build()
    }

    private fun allowed(url: HttpUrl, testHost: String?): Boolean =
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            (url.isHttps && url.host == "api.trakt.tv" && url.port == 443 ||
                testHost != null && url.host == testHost && url.host in setOf("127.0.0.1", "localhost", "::1"))

    private fun bounded(body: ResponseBody): ResponseBody {
        val source = object : ForwardingSource(body.source()) {
            private var bytes = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, minOf(byteCount, MAX_RESPONSE_BYTES - bytes + 1L))
                if (count > 0L) {
                    bytes += count
                    if (bytes > MAX_RESPONSE_BYTES) {
                        super.close()
                        throw IOException("Public Kids catalog response exceeds its size limit.")
                    }
                }
                return count
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): BufferedSource = source
        }
    }
}
