package com.nuvio.tv.core.sync

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey

/**
 * Fixed envelope names, independent of R8. Payload models are covered by the existing
 * domain.model keep rule. The a/b aliases are the verified earlier release wrappers,
 * not aliases for payload fields or arbitrary obfuscated classes.
 */
internal object PendingWatchMutationJson {
    private val gson = Gson()

    fun writeProgress(key: String, progress: WatchProgress): String = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("key", key)
        add("progress", gson.toJsonTree(progress))
    }.toString()

    fun writeWatched(key: WatchedMutationKey, item: WatchedItem): String = JsonObject().apply {
        addProperty("schemaVersion", 1)
        add("key", gson.toJsonTree(key))
        add("item", gson.toJsonTree(item))
    }.toString()

    fun writeWatchedKey(key: WatchedMutationKey): String = gson.toJson(key)

    fun readProgress(raw: String): Pair<String, WatchProgress>? = safely {
        val envelope = envelope(raw, "progress") ?: return@safely null
        val key = envelope.first.stringValue()?.takeIf(String::isNotBlank) ?: return@safely null
        val payload = envelope.second.objectValue() ?: return@safely null
        if (!validProgress(payload)) return@safely null
        // Missing old optional fields must not bypass Kotlin's non-null defaults via Gson.
        val normalized = payload.deepCopy().apply {
            if (!has("source")) addProperty("source", WatchProgress.SOURCE_LOCAL)
            if (!has("excludedNextUpSeasons")) add("excludedNextUpSeasons", gson.toJsonTree(emptySet<Int>()))
        }
        val progress = gson.fromJson(normalized, WatchProgress::class.java) ?: return@safely null
        val expectedKey = if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else progress.contentId
        if (key != expectedKey) return@safely null
        key to progress
    }

    fun readWatched(raw: String): Pair<WatchedMutationKey, WatchedItem>? = safely {
        val envelope = envelope(raw, "item") ?: return@safely null
        val key = watchedKey(envelope.first.objectValue() ?: return@safely null) ?: return@safely null
        val payload = envelope.second.objectValue() ?: return@safely null
        if (!validWatched(payload)) return@safely null
        val item = gson.fromJson(payload, WatchedItem::class.java) ?: return@safely null
        if (key != item.mutationKey()) return@safely null
        key to item
    }

    fun readWatchedKey(raw: String): WatchedMutationKey? = safely {
        watchedKey(JsonParser.parseString(raw).objectValue() ?: return@safely null)
    }

    private fun envelope(raw: String, payloadName: String): Pair<JsonElement, JsonElement>? {
        val value = JsonParser.parseString(raw).objectValue() ?: return null
        val names = value.keySet()
        return when {
            names == setOf("schemaVersion", "key", payloadName) && value["schemaVersion"].integerValue() == 1L ->
                value["key"] to value[payloadName]
            names == setOf("key", payloadName) -> value["key"] to value[payloadName]
            names == setOf("a", "b") -> value["a"] to value["b"]
            else -> null // Reject ambiguous/mixed aliases and unknown future schemas.
        }
    }

    private fun validProgress(value: JsonObject): Boolean =
        value.nonBlank("contentId") && value.nonBlank("contentType") &&
            value.string("name") && value.nonBlank("videoId") &&
            value.nonNegativeLong("position") && value.nonNegativeLong("duration") &&
            value.nonNegativeLong("lastWatched") && value.episodeNumbers() &&
            value.optionalStrings("poster", "backdrop", "logo", "episodeTitle", "addonBaseUrl",
                "trackingProviderId", "trackingProviderItemId", "trackingSourceUrl") &&
            (!value.has("source") || value.nonBlank("source")) &&
            value.optionalFiniteFloat("progressPercent", 0f, 100f) &&
            value.optionalFiniteFloat("completionThresholdOverride", 0f, 1f) &&
            value.optionalNonNegativeLongs("traktPlaybackId", "simklPlaybackId") &&
            value.optionalNonNegativeInts("traktMovieId", "traktShowId", "traktEpisodeId") &&
            (!value.has("excludedNextUpSeasons") || value["excludedNextUpSeasons"].let { seasons ->
                seasons.isJsonArray && seasons.asJsonArray.all { it.integerValue()?.let { number -> number in 0..Int.MAX_VALUE.toLong() } == true }
            })

    private fun validWatched(value: JsonObject): Boolean =
        value.nonBlank("contentId") && value.nonBlank("contentType") && value.string("title") &&
            value.nonNegativeLong("watchedAt") && value.episodeNumbers() &&
            value.optionalStrings("poster", "releaseInfo", "trackingProviderId", "trackingProviderItemId", "trackingSourceUrl")

    private fun watchedKey(value: JsonObject): WatchedMutationKey? {
        if (!value.nonBlank("contentId") || !value.episodeNumbers()) return null
        return WatchedMutationKey(value["contentId"].asString,
            value["season"]?.takeUnless(JsonElement::isJsonNull)?.asInt,
            value["episode"]?.takeUnless(JsonElement::isJsonNull)?.asInt)
    }

    private fun JsonObject.nonBlank(name: String) = get(name)?.stringValue()?.isNotBlank() == true
    private fun JsonObject.string(name: String) = get(name)?.stringValue() != null
    private fun JsonObject.nonNegativeLong(name: String) = get(name)?.integerValue()?.let { it >= 0L } == true
    private fun JsonObject.episodeNumbers() = optionalNonNegativeInts("season", "episode")
    private fun JsonObject.optionalNonNegativeInts(vararg names: String) = names.all { name ->
        get(name).let { it == null || it.isJsonNull || it.integerValue()?.let { number -> number in 0..Int.MAX_VALUE.toLong() } == true }
    }
    private fun JsonObject.optionalNonNegativeLongs(vararg names: String) = names.all { name ->
        get(name).let { it == null || it.isJsonNull || it.integerValue()?.let { number -> number >= 0L } == true }
    }
    private fun JsonObject.optionalStrings(vararg names: String) = names.all { name ->
        get(name).let { it == null || it.isJsonNull || it.stringValue() != null }
    }
    private fun JsonObject.optionalFiniteFloat(name: String, minimum: Float, maximum: Float): Boolean {
        val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return true
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
        val number = value.asFloat
        return number.isFinite() && number in minimum..maximum
    }
    private fun JsonElement.objectValue(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun JsonElement.stringValue(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonElement.integerValue(): Long? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal?.longValueExact()

    private inline fun <T> safely(block: () -> T?): T? = try { block() } catch (_: Exception) { null }
}
