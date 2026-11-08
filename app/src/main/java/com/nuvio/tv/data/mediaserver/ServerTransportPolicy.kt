package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerTransportPolicy {
    fun localHttpHost(address: String): String? {
        val url = normalizeServerAddress(address)?.toHttpUrlOrNull() ?: return null
        if (url.isHttps) return null
        val host = url.host
        val local = when {
            ':' in host || host.matches(Regex("[0-9.]+")) ->
                runCatching { isLocalAddress(InetAddress.getByName(host)) }.getOrDefault(false)
            // Names are eligible for consent. The HTTP client's DNS guard pins them to local addresses.
            else -> true
        }
        return host.takeIf { local }
    }

    fun allowsCredentials(address: String, localHttpApproved: Boolean): Boolean {
        val normalized = normalizeServerAddress(address) ?: return false
        return normalized.toHttpUrlOrNull()?.isHttps == true ||
            localHttpApproved && localHttpHost(normalized) != null
    }

    fun isLocalAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.address.let { it.size == 16 && (it[0].toInt() and 0xfe) == 0xfc }
}
