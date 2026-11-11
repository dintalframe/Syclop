package com.nuvio.tv.core.anime

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.Locale

internal data class KitsuAnimeEpisode(
    val id: Long,
    val season: Int,
    val relativeNumber: Int? = null,
    val canonicalTitle: String? = null,
    val airDate: LocalDate? = null
)

internal data class KitsuAnimeEpisodes(
    val canonicalTitle: String,
    val startYear: Int,
    val episodes: Map<Int, KitsuAnimeEpisode>
)

internal data class LinkedKitsuEpisodes(val anime: KitsuAnimeEpisodes, val malId: Long?, val aniListId: Long?)

private const val MAX_EPISODE_NUMBER = 4_000
private const val MAX_SEASON_NUMBER = 1_000
private const val MAX_MAPPING_COUNT = 128
private val kitsuResourceId = Regex("[1-9][0-9]{0,18}")
private val kitsuStartDate = Regex("(?:19|20)[0-9]{2}-[0-9]{2}-[0-9]{2}")
private val kitsuSeriesSubtypes = setOf("TV", "ONA", "OVA")

/** Decodes an already bounded, duplicate-key-checked JSON:API document. */
internal fun decodeKitsuAnimeEpisodes(root: JsonObject, expectedKitsuId: Long): KitsuAnimeEpisodes? {
    val anime = root["data"].objectOrNull() ?: return null
    val included = root["included"].arrayOrNull() ?: return null
    return decodeEpisodeRows(anime, included, expectedKitsuId)
}

/** Requires independent corroboration of the cross-catalog identity before decoding episodes. */
internal fun decodeLinkedKitsuAnimeEpisodes(
    root: JsonObject,
    expectedKitsuId: Long,
    expectedMalId: Long,
    expectedAniListId: Long?
): KitsuAnimeEpisodes? {
    if (expectedMalId <= 0 || (expectedAniListId != null && expectedAniListId <= 0)) return null
    val linked = decodeKitsuLinks(root, expectedKitsuId) ?: return null
    if (linked.malId != null && linked.malId != expectedMalId) return null
    if (expectedAniListId != null && linked.aniListId != null && linked.aniListId != expectedAniListId) return null
    return linked.anime.takeIf { linked.malId == expectedMalId ||
        (expectedAniListId != null && linked.aniListId == expectedAniListId) }
}

internal fun decodeKitsuLinks(root: JsonObject, expectedKitsuId: Long): LinkedKitsuEpisodes? {
    val anime = root["data"].objectOrNull() ?: return null
    val mappingIds = linkedResourceIds(anime, "mappings", MAX_MAPPING_COUNT) ?: return null
    val included = root["included"].arrayOrNull() ?: return null
    if (included.size() > mappingIds.size + MAX_EPISODE_NUMBER) return null

    val episodes = JsonArray()
    var malId: Long? = null
    var aniListId: Long? = null
    for (element in included) {
        val row = element.objectOrNull() ?: return null
        when (row["type"].stringOrNull()) {
            "episodes" -> episodes.add(row)
            "mappings" -> {
                val id = resourceId(row, "mappings") ?: return null
                if (!mappingIds.remove(id)) return null
                val attributes = row["attributes"].objectOrNull() ?: return null
                val site = attributes["externalSite"].stringOrNull()?.takeIf {
                    it.length in 1..128 && it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl)
                } ?: return null
                val externalId = attributes["externalId"].stringOrNull()?.takeIf {
                    it.length in 1..512 && it.isNotBlank() && it.none(Char::isISOControl)
                } ?: return null
                val service = site.substringBefore('/').lowercase(Locale.ROOT)
                if (service == "myanimelist" || service == "anilist") {
                    if (site != "$service/anime") return null
                    val id = externalId.positiveId() ?: return null
                    if (service == "myanimelist") {
                        if (malId != null) return null
                        malId = id
                    } else {
                        if (aniListId != null) return null
                        aniListId = id
                    }
                }
            }
            else -> return null
        }
    }
    if (mappingIds.isNotEmpty() || (malId == null && aniListId == null)) return null
    return decodeEpisodeRows(anime, episodes, expectedKitsuId)?.let { LinkedKitsuEpisodes(it, malId, aniListId) }
}

private fun decodeEpisodeRows(anime: JsonObject, included: JsonArray, expectedKitsuId: Long): KitsuAnimeEpisodes? {
    if (resourceId(anime, "anime") != expectedKitsuId) return null

    val attributes = anime["attributes"].objectOrNull() ?: return null
    val subtype = attributes["subtype"].stringOrNull()?.uppercase(Locale.ROOT)
    if (subtype !in kitsuSeriesSubtypes) return null
    val showType = attributes["showType"]
    if (showType != null && !showType.isJsonNull && showType.stringOrNull()?.uppercase(Locale.ROOT) != subtype) {
        return null
    }
    val title = attributes["canonicalTitle"].stringOrNull()?.takeIf {
        it.length <= 512 && it.any(Char::isLetterOrDigit) && it.none(Char::isISOControl)
    }?.trim() ?: return null
    val startDate = attributes["startDate"].stringOrNull()?.takeIf(kitsuStartDate::matches) ?: return null
    val startYear = try {
        LocalDate.parse(startDate).year
    } catch (_: DateTimeParseException) {
        return null
    }

    val remainingIds = linkedResourceIds(anime, "episodes", MAX_EPISODE_NUMBER) ?: return null
    if (included.size() != remainingIds.size) return null

    val episodes = linkedMapOf<Int, KitsuAnimeEpisode>()
    val relativeCoordinates = mutableSetOf<Pair<Int, Int>>()
    for (includedRow in included) {
        val row = includedRow.objectOrNull() ?: return null
        val id = resourceId(row, "episodes") ?: return null
        if (!remainingIds.remove(id)) return null
        val relationships = row["relationships"]
        if (relationships != null && !relationships.isJsonObject) return null
        val media = relationships?.asJsonObject?.get("media")
        if (media != null && !media.isJsonObject) return null
        // Kitsu supplies links only here; explicit reverse linkage must agree with the parent.
        val mediaData = media?.asJsonObject?.get("data")
        if (mediaData != null && resourceId(mediaData.objectOrNull(), "anime") != expectedKitsuId) return null

        val episode = row["attributes"].objectOrNull() ?: return null
        val number = episode["number"].positiveInt(MAX_EPISODE_NUMBER) ?: return null
        val season = episode["seasonNumber"].positiveInt(MAX_SEASON_NUMBER) ?: return null
        val relativeNumber = episode["relativeNumber"]?.takeUnless { it.isJsonNull }?.let {
            it.positiveInt(MAX_EPISODE_NUMBER) ?: return null
        }
        if (relativeNumber != null && !relativeCoordinates.add(season to relativeNumber)) return null
        val episodeTitle = episode["canonicalTitle"].stringOrNull()?.takeIf {
            it.length in 1..512 && it.none(Char::isISOControl)
        }
        val airDate = episode["airdate"].stringOrNull()?.takeIf(kitsuStartDate::matches)?.let {
            try { LocalDate.parse(it) } catch (_: DateTimeParseException) { null }
        }
        if (episodes.put(number, KitsuAnimeEpisode(id, season, relativeNumber, episodeTitle, airDate)) != null) return null
    }
    return KitsuAnimeEpisodes(title, startYear, Collections.unmodifiableMap(episodes))
}

private fun linkedResourceIds(anime: JsonObject, type: String, limit: Int): MutableSet<Long>? {
    val relationship = anime["relationships"].objectOrNull()?.get(type).objectOrNull()
    val linkage = relationship?.get("data").arrayOrNull() ?: return null
    if (linkage.size() !in 1..limit) return null
    val ids = mutableSetOf<Long>()
    for (link in linkage) {
        val id = resourceId(link.objectOrNull(), type) ?: return null
        if (!ids.add(id)) return null
    }
    return ids
}

private fun resourceId(resource: JsonObject?, type: String): Long? {
    if (resource?.get("type").stringOrNull() != type) return null
    return resource?.get("id").stringOrNull()?.positiveId()
}

private fun String.positiveId(): Long? = takeIf(kitsuResourceId::matches)?.toLongOrNull()

private fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonElement?.arrayOrNull(): JsonArray? = this?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonElement?.stringOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonElement?.positiveInt(maximum: Int): Int? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString?.toIntOrNull()?.takeIf { it in 1..maximum }
