package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.PlaybackSocketFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.util.concurrent.TimeUnit

private val CROSS_ORIGIN_PLAYBACK_HEADERS = setOf("accept", "accept-language", "connection", "range", "user-agent")

internal fun isOriginScopedPlaybackHeader(name: String): Boolean = name.lowercase() !in CROSS_ORIGIN_PLAYBACK_HEADERS

internal class PlaybackHeaderScope(sourceUrl: String?, names: Set<String>) {
    private val origin = sourceUrl?.toHttpUrlOrNull()
    private val scopedNames = names.filter(::isOriginScopedPlaybackHeader)

    fun permits(url: HttpUrl): Boolean = origin != null &&
        url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port

    fun filter(request: Request): Request {
        if (scopedNames.isEmpty() || permits(request.url)) return request
        return request.newBuilder().apply { scopedNames.forEach { removeHeader(it) } }.build()
    }
}

internal fun Request.Builder.scopePlaybackHeaders(originUrl: String, names: Set<String>): Request.Builder =
    tag(PlaybackHeaderScope::class.java, PlaybackHeaderScope(originUrl, names))

internal fun OkHttpClient.withScopedPlaybackHeaders(originUrl: String, names: Set<String>): OkHttpClient {
    if (names.none(::isOriginScopedPlaybackHeader)) return this
    val scope = PlaybackHeaderScope(originUrl, names)
    return newBuilder().addNetworkInterceptor { chain -> chain.proceed(scope.filter(chain.request())) }.build()
}

internal object PlayerPlaybackNetworking {
    private const val LOOPBACK_READ_TIMEOUT_SECONDS = 65L

    /** Shared media client with platform certificate and hostname verification. */
    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .socketFactory(PlaybackSocketFactory)
            .eventListenerFactory(PlaybackConnectionEvents)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor { chain ->
                // Check each redirect before OkHttp can send the next request. This keeps HTTP upgrades.
                val request = chain.request()
                val url = request.url
                val scopedRequest = request.tag(PlaybackHeaderScope::class.java)?.filter(request) ?: request
                val response = chain.proceed(scopedRequest)
                if (url.isHttps && response.isRedirect &&
                    response.header("Location")?.let(url::resolve)?.isHttps == false
                ) {
                    response.close()
                    throw ProtocolException("HTTPS to HTTP redirects are blocked for playback")
                }
                response
            }
            .build()
    }

    fun createHttpClient(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        credentialOriginUrl: String? = null
    ): OkHttpClient {
        val builder = playbackHttpClient.newBuilder()
        if (useLongReadTimeout) {
            builder.readTimeout(LOOPBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val proxyHeaders = defaultHeaders.filterKeys { key ->
            !key.equals("Range", ignoreCase = true)
        }
        if (proxyHeaders.isNotEmpty()) {
            val scope = PlaybackHeaderScope(credentialOriginUrl, proxyHeaders.keys)
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                val next = scope.filter(request).newBuilder()
                val inScope = scope.permits(request.url)
                for ((key, value) in proxyHeaders) {
                    if (inScope || !isOriginScopedPlaybackHeader(key)) next.header(key, value)
                }
                chain.proceed(next.build())
            }
        }
        return builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    @UnstableApi
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        credentialOriginUrl: String? = null
    ): DataSource.Factory {
        val requestHeaders = defaultHeaders.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()
        val client = createHttpClient(requestHeaders, useLongReadTimeout, credentialOriginUrl)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        credentialOriginUrl: String? = null
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context,
            createHttpDataSourceFactory(defaultHeaders, credentialOriginUrl = credentialOriginUrl)
        )
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}
