package com.nuvio.tv.core.server

import com.nuvio.tv.core.express.validateExpressJsonBounds
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal class BadgeConfigDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    fun fetch(value: String): String {
        var target = validatedUrl(value)
        var addresses = resolve(target)
        val allowPrivateNetwork = addresses.all(::isPrivateNetwork)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        repeat(4) {
            check(allowPrivateNetwork || addresses.none(::isPrivateNetwork)) { "Invalid badge source" }
            check(target.isHttps || addresses.all(::isPrivateNetwork)) { "Badge source requires HTTPS" }
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Badge source timed out" }
            val host = target.host
            val pinnedAddresses = addresses
            val downloadClient = client.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(remaining, TimeUnit.NANOSECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname != host) throw UnknownHostException("Unexpected badge host")
                        return pinnedAddresses
                    }
                })
                .build()
            val request = Request.Builder().url(target).header("Accept", "application/json").build()
            val redirected = downloadClient.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val location = response.header("Location") ?: error("Invalid badge redirect")
                    val next = validatedUrl(target.resolve(location)?.toString().orEmpty())
                    check(!target.isHttps || next.isHttps) { "Invalid badge redirect" }
                    next
                } else {
                    check(response.isSuccessful) { "Badge source request failed" }
                    val body = response.body
                    check(body.contentLength() <= MAX_CONFIG_BODY_BYTES) { "Badge source exceeds the size limit" }
                    val source = body.source()
                    source.request(MAX_CONFIG_BODY_BYTES + 1L)
                    check(source.buffer.size <= MAX_CONFIG_BODY_BYTES) { "Badge source exceeds the size limit" }
                    return source.readUtf8().also(::validateExpressJsonBounds)
                }
            }
            target = redirected
            addresses = resolve(target)
        }
        error("Too many badge redirects")
    }

    private fun validatedUrl(value: String): HttpUrl {
        require(value.length <= 4096) { "Invalid badge source" }
        val url = value.toHttpUrlOrNull() ?: error("Invalid badge source")
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Invalid badge source" }
        return url
    }

    private fun resolve(url: HttpUrl): List<InetAddress> {
        val addresses = client.dns.lookup(url.host)
        require(addresses.isNotEmpty() && addresses.none {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isMulticastAddress
        }) { "Invalid badge source address" }
        require(addresses.all(::isPrivateNetwork) || addresses.none(::isPrivateNetwork)) { "Ambiguous badge source address" }
        return addresses
    }

    private fun isPrivateNetwork(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress) return true
        val bytes = address.address
        if (address is Inet6Address) return (bytes[0].toInt() and 0xfe) == 0xfc
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }
}
