package com.nuvio.tv.core.anime

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections

/** A native proof is not a Stream field and cannot be minted from provider JSON or filenames. */
class NativeAnimeEpisodeProof private constructor(
    val malId: Long,
    val malEpisode: Int,
    val tvdbShowId: Long,
    val tvdbSeason: Int,
    val tvdbEpisode: Int,
    val absoluteEpisode: Int?,
    private val tvdbEpisodeId: Long,
    internal val titleIdentity: NativeAnimeTitleIdentity?
) {
    val hasTrustedTitleIdentity: Boolean get() = titleIdentity?.malId == malId

    val cacheKey: String = MessageDigest.getInstance("SHA-256")
        .digest("native-anime-v2|$malId|$malEpisode|$tvdbShowId|$tvdbSeason|$tvdbEpisode|$absoluteEpisode|$tvdbEpisodeId|${titleIdentity?.fingerprint ?: "unbound"}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    internal fun withIdentity(identity: NativeAnimeTitleIdentity): NativeAnimeEpisodeProof? =
        identity.takeIf { it.malId == malId }?.let {
            NativeAnimeEpisodeProof(malId, malEpisode, tvdbShowId, tvdbSeason, tvdbEpisode,
                absoluteEpisode, tvdbEpisodeId, it)
        }

    /** Both declared franchise title and exact coordinates must match the actual basename. */
    fun matchesFile(path: String): Boolean {
        if (path.isBlank() || path.length > 4_096 || path.any(Char::isISOControl)) return false
        val normalized = Normalizer.normalize(path, Normalizer.Form.NFKC)
        if (normalized.length > 4_096 || !hasTrustedTitleIdentity || titleIdentity?.matchesRelease(normalized) != true) return false
        val file = normalized.replace('\\', '/').substringAfterLast('/')
        if (file.isBlank() || compound.containsMatchIn(file) ||
            range.findAll(file).any { !resolutionSuffix.matches(it.groupValues[1]) } ||
            fraction.findAll(file).any { !resolutionSuffix.matches(it.groupValues[1]) }) return false
        val pairs = seasonEpisode.findAll(file).toList()
        if (pairs.any { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toIntOrNull() != tvdbSeason || (it.groupValues[2].ifEmpty { it.groupValues[4] }).toIntOrNull() != tvdbEpisode }) return false
        // Standalone season markers are ambiguous alongside absolute labels.
        val remainder = StringBuilder(file).also { text -> pairs.forEach { match ->
            for (index in match.range) text.setCharAt(index, ' ')
        } }.toString()
        if (standaloneSeason.containsMatchIn(remainder)) return false
        val absolute = (eAbsolute.findAll(remainder) + dashAbsolute.findAll(remainder)).toList()
        if (absolute.any { absoluteEpisode == null || it.groupValues[1].toIntOrNull() != absoluteEpisode }) return false
        return pairs.isNotEmpty() || absolute.isNotEmpty()
    }

    override fun toString(): String = "NativeAnimeEpisodeProof(validated=true)"

    companion object {
        private val seasonEpisode = Regex("(?<![a-z0-9])(?:s([0-9]{1,3})[ ._-]*e([0-9]{1,5})|([0-9]{1,3})x([0-9]{1,5}))(?![a-z0-9])", RegexOption.IGNORE_CASE)
        private val standaloneSeason = Regex("(?<![a-z0-9])(?:s|season[ ._-]*)([0-9]{1,3})(?![a-z0-9])", RegexOption.IGNORE_CASE)
        private val eAbsolute = Regex("(?<![a-z0-9])e([0-9]{1,5})(?![a-z0-9])", RegexOption.IGNORE_CASE)
        private val dashAbsolute = Regex("(?<![0-9])-\\s*([0-9]{1,5})(?![a-z0-9])", RegexOption.IGNORE_CASE)
        private val range = Regex("[0-9]\\s*(?:[-~+&_]|to)\\s*(?:s[0-9]+e|e)?([0-9]+[a-z0-9]*)(?![a-z0-9])", RegexOption.IGNORE_CASE)
        // A known resolution suffix is not a second episode number; every other numbered
        // suffix (including v2 or unknown words) stays ambiguous and must fail closed.
        private val resolutionSuffix = Regex("(?:360|480|576|720|1080|1440|2160|4320)p", RegexOption.IGNORE_CASE)
        private val compound = Regex("(?:e[0-9]+){2}|[0-9]+x[0-9]+x[0-9]+", RegexOption.IGNORE_CASE)
        private val fraction = Regex("(?:e|-|[0-9]+x)\\s*[0-9]+\\.([0-9]+[a-z0-9]*)(?![a-z0-9])", RegexOption.IGNORE_CASE)

        // The only constructor path performs the complete bounded public-response validation.
        internal fun decodeMapping(json: String, expectedMalId: Long): NativeAnimeMappingRecord? = try {
            Decoder.decode(json, expectedMalId)
        } catch (_: Exception) { null }
    }

    private object Decoder {
        private const val MAX_BODY = 2 * 1024 * 1024
        private const val MAX_EPISODES = 4_000
        private val rootFields = setOf("titles", "episodes", "episodeCount", "specialCount", "images", "mappings")
        private val mappingFields = setOf("animeplanet_id", "kitsu_id", "mal_id", "type", "anilist_id", "anisearch_id", "anidb_id", "notifymoe_id", "livechart_id", "thetvdb_id", "imdb_id", "themoviedb_id")
        private val mappingNumbers = setOf("kitsu_id", "anilist_id", "anisearch_id", "anidb_id", "livechart_id")
        private val episodeFields = setOf("tvdbShowId", "tvdbId", "seasonNumber", "episodeNumber", "absoluteEpisodeNumber", "title", "airDate", "airDateUtc", "runtime", "overview", "image", "episode", "anidbEid", "length", "airdate", "rating", "summary", "finaleType", "airedBeforeEpisodeNumber", "airedBeforeSeasonNumber")
        private val episodeStrings = setOf("airDate", "airDateUtc", "overview", "image", "episode", "airdate", "summary", "finaleType")
        private val episodeNumbers = episodeFields - episodeStrings - setOf("title", "rating")
        private val positiveKey = Regex("[1-9][0-9]{0,3}")
        private val specialKey = Regex("S[1-9][0-9]{0,3}")
        private val language = Regex("[A-Za-z0-9-]{1,32}")
        private val integer = Regex("0|[1-9][0-9]{0,9}")

        fun decode(json: String, expectedMalId: Long): NativeAnimeMappingRecord? {
            if (expectedMalId !in 1..Int.MAX_VALUE.toLong() || json.length > MAX_BODY || json.toByteArray(Charsets.UTF_8).size > MAX_BODY) return null
            val root = strictTree(json).objectOrNull() ?: return null
            if (!root.only(rootFields)) return null
            if (!root.optional("titles", ::titles) || !root.optional("episodeCount") { it.whole(0, MAX_EPISODES.toLong()) != null } ||
                !root.optional("specialCount") { it.whole(0, MAX_EPISODES.toLong()) != null }) return null
            if (!root.optional("images") { images -> images.isJsonArray && images.asJsonArray.size() <= 32 && images.asJsonArray.all { image ->
                image.objectOrNull()?.let { item -> item.only(setOf("coverType", "url")) && item.entrySet().all { it.value.stringOrNull() != null } } == true
            } }) return null
            // Titles are metadata from this exact expected-MAL public mapping, never a
            // provider query/release claim. Missing/generic titles cannot mint playable proof.
            val trustedTitles = root["titles"].objectOrNull()?.entrySet()?.mapNotNull { it.value.stringOrNull() }
                ?: return null
            val titleIdentity = NativeAnimeTitleIdentity.create(expectedMalId,
                trustedTitles.firstOrNull().orEmpty(), trustedTitles.drop(1)) ?: return null
            val mappings = root["mappings"].objectOrNull() ?: return null
            if (!mappings.only(mappingFields) || mappings["mal_id"].whole(1, Int.MAX_VALUE.toLong()) != expectedMalId) return null
            val show = mappings["thetvdb_id"].whole(1, Int.MAX_VALUE.toLong()) ?: return null
            if (mappings.entrySet().any { (name, value) -> when (name) {
                "mal_id", "thetvdb_id" -> false
                in mappingNumbers -> !value.isJsonNull && value.whole(1, Int.MAX_VALUE.toLong()) == null
                else -> !value.isJsonNull && value.stringOrNull() == null
            } }) return null
            val episodes = root["episodes"].objectOrNull() ?: return null
            if (episodes.size() !in 1..MAX_EPISODES) return null
            val result = linkedMapOf<Int, NativeAnimeEpisodeProof>()
            val coordinates = mutableSetOf<Pair<Int,Int>>()
            val episodeIds = mutableSetOf<Long>()
            val absolutes = mutableSetOf<Int>()
            for ((key, value) in episodes.entrySet()) {
                val special = specialKey.matches(key)
                if ((!positiveKey.matches(key) && !special) || key.removePrefix("S").toInt() > MAX_EPISODES) return null
                val row = value.objectOrNull() ?: return null
                if (!row.only(episodeFields) || row["episode"].stringOrNull() != key) return null
                if (episodeStrings.any { !row.optional(it) { text -> text.stringOrNull() != null } } ||
                    !row.optional("title", ::titles) || !row.optional("rating") { rating ->
                        (rating.stringOrNull()?.toDoubleOrNull() ?: rating.finiteNumber())?.let { it in 0.0..10.0 } == true
                    } || episodeNumbers.any { !row.optional(it) { number -> number.finiteNumber()?.let { it in 0.0..Int.MAX_VALUE.toDouble() } == true } }) return null
                if (special) continue // No season-zero/special/decimal proof is ever minted.
                val malEpisode = key.toInt()
                val rowShow = row["tvdbShowId"].whole(1, Int.MAX_VALUE.toLong()) ?: return null
                val episodeId = row["tvdbId"].whole(1, Int.MAX_VALUE.toLong()) ?: return null
                val season = row["seasonNumber"].whole(1, 1_000)?.toInt() ?: return null
                val episode = row["episodeNumber"].whole(1, MAX_EPISODES.toLong())?.toInt() ?: return null
                val absolute = row["absoluteEpisodeNumber"]?.takeUnless { it.isJsonNull }?.let {
                    it.whole(1, 100_000)?.toInt() ?: return null
                }
                if (rowShow != show || !coordinates.add(season to episode) || !episodeIds.add(episodeId) ||
                    (absolute != null && !absolutes.add(absolute))) return null
                result[malEpisode] = NativeAnimeEpisodeProof(expectedMalId, malEpisode, show, season, episode, absolute, episodeId, titleIdentity)
            }
            return result.takeIf { it.isNotEmpty() }?.let { NativeAnimeMappingRecord(expectedMalId, it) }
        }

        private fun titles(value: JsonElement): Boolean = value.isJsonObject && value.asJsonObject.size() <= 128 &&
            value.asJsonObject.entrySet().all { (key, text) -> language.matches(key) && text.stringOrNull() != null }
        private fun JsonObject.only(allowed: Set<String>) = keySet().all(allowed::contains)
        private fun JsonObject.optional(name: String, valid: (JsonElement) -> Boolean): Boolean = get(name).let { it == null || it.isJsonNull || valid(it) }
        private fun JsonElement?.objectOrNull() = this?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonElement?.stringOrNull() = this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        private fun JsonElement?.whole(minimum: Long, maximum: Long): Long? = this
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && integer.matches(it.toString()) }
            ?.asString?.toLongOrNull()?.takeIf { it in minimum..maximum }
        private fun JsonElement.finiteNumber(): Double? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asDouble?.takeIf(Double::isFinite)

        /** JsonParser would silently retain the last duplicate key; this reader rejects it. */
        private fun strictTree(json: String): JsonElement {
            var nodes = 0
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                fun read(depth: Int): JsonElement {
                    require(depth <= 16 && ++nodes <= 100_000)
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().also { objectValue ->
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName(); require(key.length <= 128 && !objectValue.has(key) && objectValue.size() < 4_500)
                                objectValue.add(key, read(depth + 1))
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                            reader.beginArray()
                            while (reader.hasNext()) { require(array.size() < 4_500); array.add(read(depth + 1)) }
                            reader.endArray()
                        }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 16_384) })
                        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString().also { require(it.length <= 32) }))
                        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                        else -> error("Unsupported JSON token")
                    }
                }
                val value = read(0); require(reader.peek() == JsonToken.END_DOCUMENT); return value
            }
        }
    }
}

internal class NativeAnimeMappingRecord(val malId: Long, episodes: Map<Int, NativeAnimeEpisodeProof>) {
    val episodes: Map<Int, NativeAnimeEpisodeProof> = Collections.unmodifiableMap(LinkedHashMap(episodes))
}

internal object NativeAnimeMappingCodec {
    fun parse(json: String, expectedMalId: Long): NativeAnimeMappingRecord? = NativeAnimeEpisodeProof.decodeMapping(json, expectedMalId)
}
