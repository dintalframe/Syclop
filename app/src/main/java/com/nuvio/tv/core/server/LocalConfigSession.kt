package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.nuvio.tv.core.express.ExpressException
import com.nuvio.tv.core.express.validateExpressJsonBounds
import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import java.util.UUID

internal class LocalConfigSession {
    private val token = UUID.randomUUID().toString()
    private val authorization = "Bearer $token".toByteArray(Charsets.US_ASCII)

    fun pairingUrl(ipAddress: String, port: Int): String = "http://$ipAddress:$port/#$token"

    fun respond(session: NanoHTTPD.IHTTPSession, route: () -> NanoHTTPD.Response): NanoHTTPD.Response {
        val publicAsset = session.method == NanoHTTPD.Method.GET && session.uri in setOf("/", "/logo.png")
        val supplied = session.headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
        val response = if (!publicAsset && !MessageDigest.isEqual(authorization, supplied)) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Pairing required")
        } else {
            try {
                if (session.method == NanoHTTPD.Method.POST) configBodyLength(session)
                route()
            } catch (error: InvalidConfigRequest) {
                NanoHTTPD.newFixedLengthResponse(error.status, "application/json", Gson().toJson(mapOf("error" to error.message)))
            }
        }
        return response.apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Frame-Options", "DENY")
            if (status.requestStatus >= 400) addHeader("Connection", "close")
        }
    }

    companion object {
        fun browserScript(storageKey: String, pairingRequiredMessage: String?): String {
            val key = Gson().toJson(storageKey)
            val message = Gson().toJson(pairingRequiredMessage ?: "Scan the QR code on your TV again to connect.")
            return """
<script>
var localPairingKey = $key;
var localPairingToken = (function() {
  var fragment = window.location.hash.slice(1);
  if (fragment) {
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
    try { window.sessionStorage.setItem(localPairingKey, fragment); } catch (_) {}
    return fragment;
  }
  try { return window.sessionStorage.getItem(localPairingKey); } catch (_) { return null; }
})();
function requireLocalPairing() {
  localPairingToken = null;
  try { window.sessionStorage.removeItem(localPairingKey); } catch (_) {}
  var indicator = document.getElementById('connectionBar') || document.getElementById('status');
  if (indicator) {
    indicator.textContent = $message;
    indicator.className = indicator.id === 'connectionBar' ? 'connection-bar visible' : 'status error';
  }
  var error = new Error($message);
  error.name = 'LocalPairingRequired';
  throw error;
}
async function localConfigFetch(url, options) {
  if (!localPairingToken) requireLocalPairing();
  var target = new URL(url, window.location.href);
  if (target.origin !== window.location.origin || !target.pathname.startsWith('/api/')) {
    throw new Error('Invalid TV configuration request');
  }
  var opts = Object.assign({}, options || {});
  opts.mode = 'same-origin';
  opts.redirect = 'error';
  opts.cache = 'no-store';
  opts.credentials = 'omit';
  opts.headers = new Headers(opts.headers);
  opts.headers.set('Authorization', 'Bearer ' + localPairingToken);
  var response = await fetch(target.href, opts);
  if (response.status === 403) requireLocalPairing();
  return response;
}
</script>
""".trimIndent()
        }
    }
}

internal const val MAX_CONFIG_BODY_BYTES = 2 * 1024 * 1024

internal class InvalidConfigRequest(
    val status: NanoHTTPD.Response.Status,
    message: String
) : IllegalArgumentException(message)

private fun configBodyLength(session: NanoHTTPD.IHTTPSession): Int {
    if (session.headers.containsKey("transfer-encoding")) {
        throw InvalidConfigRequest(NanoHTTPD.Response.Status.BAD_REQUEST, "Unsupported request encoding")
    }
    val header = session.headers["content-length"] ?: return 0
    val length = header.toLongOrNull()
        ?: throw InvalidConfigRequest(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid request size")
    if (length < 0) throw InvalidConfigRequest(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid request size")
    if (length > MAX_CONFIG_BODY_BYTES) {
        throw InvalidConfigRequest(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, "Request exceeds the size limit")
    }
    return length.toInt()
}

internal fun readConfigJsonBody(session: NanoHTTPD.IHTTPSession): String {
    val length = configBodyLength(session)
    val buffer = ByteArray(length)
    var offset = 0
    val input = session.inputStream
    while (offset < length) {
        val read = input.read(buffer, offset, length - offset)
        if (read <= 0) throw InvalidConfigRequest(NanoHTTPD.Response.Status.BAD_REQUEST, "Incomplete request body")
        offset += read
    }
    val body = buffer.toString(Charsets.UTF_8)
    try {
        validateExpressJsonBounds(body)
    } catch (_: ExpressException) {
        throw InvalidConfigRequest(NanoHTTPD.Response.Status.BAD_REQUEST, "Request exceeds the nesting limit")
    }
    return body
}
