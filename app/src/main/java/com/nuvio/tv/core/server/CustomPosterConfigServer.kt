package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class CustomPosterConfigServer(
    private val currentPatternProvider: () -> String,
    private val onPatternChanged: (String) -> Unit,
    private val context: Context? = null,
    port: Int = 8092
) : NanoHTTPD(port) {
    private val pairing = LocalConfigSession()
    private val gson = Gson()

    fun pairingUrl(ipAddress: String): String = pairing.pairingUrl(ipAddress, listeningPort)

    override fun serve(session: IHTTPSession): Response = pairing.respond(session) {
        when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/api/pattern" -> serveCurrentPattern()
            session.method == Method.POST && session.uri == "/api/pattern" -> handlePatternUpdate(session)
            session.method == Method.POST && session.uri == "/api/clear" -> handleClear()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            CustomPosterWebPage.html(context)
        )

    private fun serveCurrentPattern(): Response {
        val pattern = currentPatternProvider()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("pattern" to pattern))
        )
    }

    private fun handlePatternUpdate(session: IHTTPSession): Response {
        val body = readConfigJsonBody(session)
        val pattern = runCatching { gson.fromJson(body, PatternRequest::class.java)?.pattern }.getOrNull()
        if (pattern == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                """{"error":"Missing pattern field"}"""
            )
        }
        onPatternChanged(pattern.trim())
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"saved"}"""
        )
    }

    private fun handleClear(): Response {
        onPatternChanged("")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"cleared"}"""
        )
    }

    private data class PatternRequest(val pattern: String?)

    companion object {
        fun startOnAvailablePort(
            currentPatternProvider: () -> String,
            onPatternChanged: (String) -> Unit,
            context: Context? = null,
            startPort: Int = 8092,
            maxAttempts: Int = 10
        ): CustomPosterConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = CustomPosterConfigServer(
                        currentPatternProvider = currentPatternProvider,
                        onPatternChanged = onPatternChanged,
                        context = context,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
