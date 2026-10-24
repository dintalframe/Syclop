package com.nuvio.tv.core.player

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

internal data class ExternalPlayerResumePlan(
    val positionMs: Long,
    val intPositionMs: Int,
    val useLongPosition: Boolean,
    val sendPosition: Boolean,
    val startFromBeginning: Boolean
)

/** Receiving-player wire formats. This class has no Android dependency. */
internal object ExternalPlayerInteropRules {
    const val UNSUPPORTED_HEADERS_MESSAGE =
        "This player cannot receive this stream's required headers. Use the internal player."
    private const val MAX_SUBTITLES = 32
    private const val MAX_SKIP_SEGMENTS_CHARS = 16_384
    private val skipTypes = setOf("intro", "recap", "outro", "op", "ed", "mixed-op", "mixed-ed",
        "opening", "ending", "credits", "end-credits")

    private fun isPackage(packageName: String?, base: String): Boolean =
        packageName == base || packageName?.startsWith("$base.") == true

    fun resumePlan(packageName: String?, positionMs: Long, startFromBeginning: Boolean): ExternalPlayerResumePlan {
        val position = if (startFromBeginning) 0L else positionMs.coerceAtLeast(0L)
        return ExternalPlayerResumePlan(
            positionMs = position,
            intPositionMs = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            useLongPosition = isPackage(packageName, "org.videolan.vlc"),
            sendPosition = startFromBeginning || position > 0L,
            startFromBeginning = startFromBeginning
        )
    }

    /** MX's format is alternating name/value strings, never `name: value` strings. */
    fun headerPairs(packageName: String?, headers: Map<String, String>?): Array<String> {
        if (headers.isNullOrEmpty()) return emptyArray()
        require(!isPackage(packageName, "com.brouken.player") && !isPackage(packageName, "org.videolan.vlc")) {
            UNSUPPORTED_HEADERS_MESSAGE
        }
        require(headers.size <= 64 && headers.all { (name, value) ->
            name.length in 1..128 && name.all { it.isLetterOrDigit() && it.code < 128 || it in "!#$%&'*+-.^_`|~" } &&
                value.length <= 8_192 && value.none { it == '\r' || it == '\n' || it == '\u0000' }
        } && headers.entries.sumOf { it.key.length.toLong() + it.value.length } <= 32_768L) {
            UNSUPPORTED_HEADERS_MESSAGE
        }
        return headers.entries.flatMap { listOf(it.key, it.value) }.toTypedArray()
    }

    /** Only this app's already-downloaded subtitle cache can be granted to another player. */
    fun cachedSubtitles(appPackage: String, subtitles: List<SubtitleInput>?): List<SubtitleInput> =
        subtitles.orEmpty().asSequence().filter { subtitle ->
            runCatching {
                val uri = URI(subtitle.url)
                val parts = uri.path?.split('/') ?: return@runCatching false
                uri.scheme == "content" && uri.rawAuthority == "$appPackage.fileprovider" &&
                    uri.rawQuery == null && uri.rawFragment == null && parts.size == 4 &&
                    parts[0].isEmpty() && parts[1] == "cache" && parts[2] == "subtitles" &&
                    parts[3].length in 1..100 && parts[3] !in setOf(".", "..") &&
                    parts[3].all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
            }.getOrDefault(false)
        }.take(MAX_SUBTITLES).map {
            it.copy(name = it.name.take(256), lang = it.lang.take(32))
        }.toList()

    fun subtitleFilename(subtitle: SubtitleInput): String =
        runCatching { URI(subtitle.url).path.substringAfterLast('/') }.getOrDefault("subtitle.srt")

    /** Reconstruct the small timing contract; provider metadata or URLs never pass through. */
    fun skipSegments(json: String?): String? {
        if (json == null || json.length > MAX_SKIP_SEGMENTS_CHARS) return null
        return runCatching {
            val segments = Json.parseToJsonElement(json) as? JsonArray ?: return@runCatching null
            if (segments.size > 64) return@runCatching null
            JsonArray(segments.map { element ->
                val item = element as? JsonObject ?: return@runCatching null
                val type = (item["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type !in skipTypes) return@runCatching null
                val start = (item["start"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                    ?: return@runCatching null
                val end = (item["end"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                    ?: return@runCatching null
                if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start || end > 86_400.0) {
                    return@runCatching null
                }
                buildJsonObject {
                    put("type", type)
                    put("start", start)
                    put("end", end)
                }
            }).toString()
        }.getOrNull()
    }

    fun parseResult(
        resultOk: Boolean,
        action: String?,
        positionMs: Long?,
        durationMs: Long?,
        endBy: String?
    ): ExternalPlayerResult? {
        val position = positionMs?.takeIf { it > 0L }
        val duration = durationMs?.takeIf { it > 0L }
        val completed = endBy == "playback_completion"
        // Preserve only the exact mpv EOF shape; a generic empty RESULT_OK is not completion.
        val mpvFinished = resultOk && action == "is.xyz.mpv.MPVActivity.result" &&
            position == null && duration == null && endBy == null
        if (position == null && !completed && !mpvFinished) return null
        return ExternalPlayerResult(
            positionMs = position ?: duration ?: 0L,
            durationMs = duration,
            endedByUser = !completed && !mpvFinished
        )
    }
}
