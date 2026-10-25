package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** Expendable enrichment only. Validate JSON before Gson can bypass Kotlin constructors. */
internal object ContinueWatchingCacheCodec {
    const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_ITEMS = 2_000
    private val gson = Gson()
    private val identity = setOf("contentId", "contentType", "name", "videoId")
    private val commonText = setOf("poster", "backdrop", "logo", "episodeTitle", "episodeDescription", "releaseInfo", "contentLanguage")

    fun nextUp(json: String): List<CachedNextUpItem> = decode(json, true, CachedNextUpItem::class.java)
    fun inProgress(json: String): List<CachedInProgressItem> = decode(json, false, CachedInProgressItem::class.java)

    private fun <T : Any> decode(json: String, next: Boolean, type: Class<T>): List<T> {
        if (json.length > MAX_BYTES) return emptyList()
        val root = try { JsonParser.parseString(json) } catch (_: JsonParseException) { return emptyList() }
        if (!root.isJsonArray || root.asJsonArray.size() > MAX_ITEMS) return emptyList()
        return root.asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val row = element.asJsonObject
            if (!valid(row, next)) return@mapNotNull null
            try { gson.fromJson(row, type) } catch (_: JsonParseException) { null }
        }
    }

    private fun valid(row: JsonObject, next: Boolean): Boolean {
        if (identity.any { name -> row[name]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() && it.asString.length <= 4_096 } != true }) return false
        val text = commonText + if (next) setOf("thumbnail", "released", "airDateLabel") else setOf("episodeThumbnail")
        if (text.any { !optional(row[it]) { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 65_536 } }) return false
        if (!whole(row["lastWatched"], nonnegative = true)) return false
        if (next) {
            if (!whole(row["season"], int = true, nonnegative = true) || !whole(row["episode"], int = true, nonnegative = true) || !whole(row["sortTimestamp"])) return false
            if (!optional(row["releaseTimestamp"]) { whole(it) }) return false
            if (!optional(row["imdbRating"]) { decimal(it, 10.0) }) return false
            if (!optional(row["seedSeason"]) { whole(it, int = true, nonnegative = true) } || !optional(row["seedEpisode"]) { whole(it, int = true, nonnegative = true) }) return false
            if (!flag(row, "hasAired", true) || !flag(row, "isReleaseAlert", false) || !flag(row, "isNewSeasonRelease", false)) return false
        } else {
            if (!whole(row["position"], nonnegative = true) || !whole(row["duration"], nonnegative = true)) return false
            if (!optional(row["season"]) { whole(it, int = true, nonnegative = true) } || !optional(row["episode"]) { whole(it, int = true, nonnegative = true) }) return false
            if (!optional(row["progressPercent"]) { decimal(it, 100.0) } || !optional(row["episodeImdbRating"]) { decimal(it, 10.0) }) return false
        }
        if (!flag(row, "ratingVisible", true)) return false
        // Older snapshots predate optional genres; explicit null/wrong shapes are corrupted rows.
        if (!row.has("genres")) row.add("genres", com.google.gson.JsonArray())
        val genres = row["genres"]
        return genres.isJsonArray && genres.asJsonArray.size() <= 256 && genres.asJsonArray.all {
            it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length <= 1_024
        }
    }

    private fun optional(value: JsonElement?, valid: (JsonElement) -> Boolean): Boolean =
        value == null || value.isJsonNull || valid(value)

    private fun flag(row: JsonObject, name: String, default: Boolean): Boolean {
        if (!row.has(name)) row.addProperty(name, default)
        val value = row[name]
        return value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
    }

    private fun whole(value: JsonElement?, int: Boolean = false, nonnegative: Boolean = false): Boolean {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
        return try {
            val number = value.asBigDecimal.toBigIntegerExact()
            (!nonnegative || number.signum() >= 0) && number >= java.math.BigInteger.valueOf(if (int) Int.MIN_VALUE.toLong() else Long.MIN_VALUE) &&
                number <= java.math.BigInteger.valueOf(if (int) Int.MAX_VALUE.toLong() else Long.MAX_VALUE)
        } catch (_: ArithmeticException) { false } catch (_: NumberFormatException) { false }
    }

    private fun decimal(value: JsonElement, maximum: Double): Boolean =
        value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
            value.asDouble.let { it.isFinite() && it in 0.0..maximum }
}
