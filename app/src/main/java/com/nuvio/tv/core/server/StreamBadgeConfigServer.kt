package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeRulesParser
import com.nuvio.tv.core.streams.StreamBadgeSettings
import com.nuvio.tv.core.streams.StreamBadgePatternException
import com.nuvio.tv.core.streams.validatePatterns
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StreamBadgeConfigServer(
    private val currentSettingsProvider: () -> StreamBadgeSettings,
    private val onSettingsChanged: (StreamBadgeSettings) -> Unit,
    private val context: Context? = null,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8091
) : NanoHTTPD(port) {
    private val gson = Gson()
    private val settingsMapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val pairing = LocalConfigSession()
    private val downloader = BadgeConfigDownloader()
    @OptIn(ExperimentalSerializationApi::class)
    private val badgeJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun pairingUrl(ipAddress: String): String = pairing.pairingUrl(ipAddress, listeningPort)

    override fun serve(session: IHTTPSession): Response = pairing.respond(session) {
        when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/logo.png" -> serveLogo()
            session.method == Method.GET && session.uri == "/api/settings" -> serveSettings()
            session.method == Method.POST && session.uri == "/api/settings" -> handleSettingsUpdate(session)
            session.method == Method.POST && session.uri == "/api/badges/import" -> handleBadgeImport(session)
            session.method == Method.POST && session.uri == "/api/badges/active" -> handleBadgeActive(session)
            session.method == Method.POST && session.uri == "/api/badges/delete" -> handleBadgeDelete(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            StreamBadgeWebPage.html(context)
        )

    private fun serveLogo(): Response {
        val bytes = logoProvider?.invoke()
        return if (bytes != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveSettings(): Response {
        val settings = currentSettingsProvider()
        val rulesJson = badgeJson.encodeToString(StreamBadgeRules.serializer(), settings.rules.normalized())
        val responseJson = """{"settings":{"streamBadgeRules":$rulesJson,"showFileSizeBadges":${settings.showFileSizeBadges},"badgePlacement":"${settings.badgePlacement.name}"}}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", responseJson)
    }

    private fun handleSettingsUpdate(session: IHTTPSession): Response {
        val parsed = parseBodyMap(session)
        val currentSettings = currentSettingsProvider()
        val streamBadgeRules = parseStreamBadgeRules(parsed.get("streamBadgeRules")) ?: currentSettings.rules.normalized()
        val showFileSizeBadges = (parsed.get("showFileSizeBadges") as? Boolean) ?: currentSettings.showFileSizeBadges
        val badgePlacement = (parsed.get("badgePlacement") as? String)
            .toStreamBadgePlacement()
            ?: currentSettings.badgePlacement
        onSettingsChanged(
            currentSettings.copy(
                rules = streamBadgeRules,
                showFileSizeBadges = showFileSizeBadges,
                badgePlacement = badgePlacement
            )
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(mapOf("status" to "saved")))
    }

    private fun handleBadgeImport(session: IHTTPSession): Response {
        val parsed = parseBodyMap(session)
        val rawSourceUrl = (parsed.get("sourceUrl") as? String).orEmpty().trim()
        val pastedPayload = (parsed.get("payload") as? String).orEmpty()
        if (rawSourceUrl.isBlank() && pastedPayload.isBlank()) {
            return errorResponse(context?.getString(com.nuvio.tv.R.string.web_stream_badge_error_url_required) ?: "Enter a badge JSON URL.")
        }
        if (pastedPayload.isBlank() &&
            !rawSourceUrl.startsWith("https://", ignoreCase = true) &&
            !rawSourceUrl.startsWith("http://", ignoreCase = true)
        ) {
            return errorResponse(context?.getString(com.nuvio.tv.R.string.web_stream_badge_error_url_scheme) ?: "Badge URL must start with http:// or https://.")
        }

        val currentSettings = currentSettingsProvider()
        val currentRules = currentSettings.rules.normalized()
        val sourceUrl = rawSourceUrl.ifBlank { "Pasted badge rules" }
        val isExistingImport = currentRules.imports.any { import ->
            import.sourceUrl.equals(sourceUrl, ignoreCase = true)
        }
        if (!isExistingImport && currentRules.imports.size >= STREAM_BADGE_IMPORT_LIMIT) {
            return errorResponse(context?.getString(com.nuvio.tv.R.string.web_stream_badge_error_import_limit, STREAM_BADGE_IMPORT_LIMIT) ?: "You can import up to $STREAM_BADGE_IMPORT_LIMIT badge URLs.")
        }

        return try {
            val payload = pastedPayload.ifBlank { downloader.fetch(sourceUrl) }
            com.nuvio.tv.core.express.validateExpressJsonBounds(payload)
            val parsedImport = StreamBadgeRulesParser.parse(
                sourceUrl = sourceUrl,
                payload = payload
            )
            val rules = currentRules.upsert(parsedImport, activate = true)
            onSettingsChanged(currentSettings.copy(rules = rules))
            val rulesJson = badgeJson.encodeToString(StreamBadgeRules.serializer(), rules)
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                """{"status":"imported","streamBadgeRules":$rulesJson}"""
            )
        } catch (error: StreamBadgePatternException) {
            errorResponse(error.message ?: badgeImportFailedMessage())
        } catch (_: Exception) {
            errorResponse(badgeImportFailedMessage())
        }
    }

    private fun handleBadgeActive(session: IHTTPSession): Response {
        val sourceUrl = (parseBodyMap(session).get("sourceUrl") as? String).orEmpty()
        val currentSettings = currentSettingsProvider()
        val rules = currentSettings.rules.normalized().setActiveSource(sourceUrl)
        onSettingsChanged(currentSettings.copy(rules = rules))
        val rulesJson = badgeJson.encodeToString(StreamBadgeRules.serializer(), rules)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"saved","streamBadgeRules":$rulesJson}"""
        )
    }

    private fun handleBadgeDelete(session: IHTTPSession): Response {
        val sourceUrl = (parseBodyMap(session).get("sourceUrl") as? String).orEmpty()
        val currentSettings = currentSettingsProvider()
        val rules = currentSettings.rules.normalized().removeSource(sourceUrl)
        onSettingsChanged(currentSettings.copy(rules = rules))
        val rulesJson = badgeJson.encodeToString(StreamBadgeRules.serializer(), rules)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"deleted","streamBadgeRules":$rulesJson}"""
        )
    }

    private fun parseBodyMap(session: IHTTPSession): Map<String, Any?> {
        val body = readConfigJsonBody(session)
        return try {
            gson.fromJson<Map<String, Any?>>(body, settingsMapType)
                ?: throw IllegalArgumentException("Empty settings")
        } catch (_: Exception) {
            throw InvalidConfigRequest(Response.Status.BAD_REQUEST, "Invalid settings JSON")
        }
    }

    private fun parseStreamBadgeRules(value: Any?): StreamBadgeRules? {
        if (value == null) return null
        return try {
            badgeJson.decodeFromString<StreamBadgeRules>(gson.toJson(value)).also { it.validatePatterns() }.normalized()
        } catch (error: StreamBadgePatternException) {
            throw InvalidConfigRequest(Response.Status.BAD_REQUEST, error.message ?: badgeImportFailedMessage())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun String?.toStreamBadgePlacement(): StreamBadgePlacement? =
        StreamBadgePlacement.entries.firstOrNull { placement ->
            placement.name.equals(this, ignoreCase = true)
        }

    private fun badgeImportFailedMessage(): String =
        context?.getString(com.nuvio.tv.R.string.web_stream_badge_import_error) ?: "Badge import failed."

    private fun errorResponse(message: String): Response =
        newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("error" to message))
        )

    companion object {
        fun startOnAvailablePort(
            currentSettingsProvider: () -> StreamBadgeSettings,
            onSettingsChanged: (StreamBadgeSettings) -> Unit,
            context: Context? = null,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8091,
            maxAttempts: Int = 10
        ): StreamBadgeConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = StreamBadgeConfigServer(
                        currentSettingsProvider = currentSettingsProvider,
                        onSettingsChanged = onSettingsChanged,
                        context = context,
                        logoProvider = logoProvider,
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
