package com.nuvio.tv.data.mal

import java.net.URI
import java.util.Base64

/** Public routing state only. It contains no verifier, authorization code or account token. */
object MalRelayState {
    const val REGISTERED_CALLBACK = "https://dintalframe.github.io/Syclop/mal/"
    const val PREFIX = "syclop-mal-v1."

    fun isRegisteredRedirect(uri: String) = uri == REGISTERED_CALLBACK

    fun privateReturnUri(ip: String?): String? {
        if (ip == null || !isPrivateIpv4(ip)) return null
        return "http://$ip:${MalRules.CALLBACK_PORT}${MalRules.CALLBACK_PATH}"
    }

    fun isPrivateReturnUri(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val ip = uri.host ?: return false
        return privateReturnUri(ip) == value && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null
    }

    fun create(nonce: String, returnUri: String): String {
        require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
        val nonceBytes = Base64.getUrlDecoder().decode(nonce)
        require(nonceBytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes) == nonce)
        require(isPrivateReturnUri(returnUri)) { "The callback needs the TV's current private IPv4 address." }
        val json = "{\"nonce\":\"$nonce\",\"return_uri\":\"$returnUri\"}"
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val numbers = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
            val number = part.toIntOrNull() ?: return false
            if (number !in 0..255 || number.toString() != part) return false
            number
        }
        return numbers[0] == 10 || numbers[0] == 172 && numbers[1] in 16..31 ||
            numbers[0] == 192 && numbers[1] == 168
    }
}
