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
import java.util.Locale

/** A native proof is not a Stream field and cannot be minted from provider JSON or filenames. */
class NativeAnimeEpisodeProof private constructor(
    val malId: Long,
    val malEpisode: Int,
    val tvdbShowId: Long?,
    val tvdbSeason: Int?,
    val tvdbEpisode: Int?,
    val absoluteEpisode: Int?,
    private val tvdbEpisodeId: Long?,
    internal val titleIdentity: NativeAnimeTitleIdentity?,
    private val episodeTitles: Set<String> = emptySet(),
    private val verifiedParent: VerifiedParentTitle? = null,
    private val catalog: CatalogCoordinates? = null
) {
    val hasTrustedTitleIdentity: Boolean get() = titleIdentity?.malId == malId
    internal val verifiedParentImdbId: String? get() = verifiedParent?.imdbId
    internal val catalogOnly: Boolean get() = catalog != null && tvdbSeason == null
    internal val catalogSeason: Int? get() = if (catalog == null) 1 else catalog.season
    internal val needsCatalogYear: Boolean get() = catalog?.editionQualified == false
    internal val usesCatalogOrdinalForSearch: Boolean get() = catalog != null && absoluteEpisode == null

    internal fun isKitsuEpisode(kitsuId: Long, ordinal: Int): Boolean =
        malEpisode == ordinal && catalog?.identity?.startsWith("kitsu:$kitsuId:") == true

    val cacheKey: String = MessageDigest.getInstance("SHA-256")
        .digest(("native-anime-v3|$malId|$malEpisode|$tvdbShowId|$tvdbSeason|$tvdbEpisode|$absoluteEpisode|$tvdbEpisodeId|" +
            "${titleIdentity?.fingerprint ?: "unbound"}|${verifiedParent?.fingerprint ?: "no-parent"}" +
            catalog?.cacheSuffix.orEmpty()).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    internal fun withIdentity(identity: NativeAnimeTitleIdentity): NativeAnimeEpisodeProof? =
        identity.takeIf { it.malId == malId && catalog == null }?.let {
            NativeAnimeEpisodeProof(malId, malEpisode, tvdbShowId, tvdbSeason, tvdbEpisode,
                absoluteEpisode, tvdbEpisodeId, it, episodeTitles, verifiedParent)
        }

    internal fun queryTitles(): List<String> {
        val original = titleIdentity?.queryTitles().orEmpty()
        val parent = verifiedParent?.title ?: return original
        return (original.take(1) + parent + original.drop(1)).distinct().take(3)
    }

    /** A parent-series name alone is insufficient: its release must also prove this exact episode. */
    internal fun acceptsReleaseTitle(value: String): Boolean =
        if (catalog != null) matchesFile(value) else hasTrustedTitleIdentity && acceptsTitle(value, true)

    private fun acceptsTitle(value: String, parentNeedsCoordinates: Boolean): Boolean {
        val child = titleIdentity?.matchRelease(value) ?: NativeAnimeTitleIdentity.Match.NO_MATCH
        val parent = verifiedParent?.matchRelease(value) ?: NativeAnimeTitleIdentity.Match.NO_MATCH
        // Independently supported parent/child years may differ. An unknown-year alias cannot
        // erase the other's applicable contradiction; a parent override still needs this episode.
        if (child == NativeAnimeTitleIdentity.Match.SUPPORTED_YEAR) return true
        if (parent == NativeAnimeTitleIdentity.Match.SUPPORTED_YEAR)
            return !parentNeedsCoordinates || matchesCoordinates(value)
        if (child == NativeAnimeTitleIdentity.Match.YEAR_CONFLICT || parent == NativeAnimeTitleIdentity.Match.YEAR_CONFLICT) return false
        return child.accepted || (parent.accepted && (!parentNeedsCoordinates || matchesCoordinates(value)))
    }

    /** Both declared franchise title and exact coordinates must match the actual basename. */
    fun matchesFile(path: String): Boolean {
        if (!hasTrustedTitleIdentity) return false
        val coordinates = catalog ?: return acceptsTitle(path, false) && matchesCoordinates(path)
        val suffix = titleIdentity?.scopedEpisodeSuffix(path, requireYear = !coordinates.editionQualified) ?: return false
        return matchesCoordinates(suffix)
    }

    private fun matchesCoordinates(path: String): Boolean {
        if (path.isBlank() || path.length > 4_096 || path.any(Char::isISOControl)) return false
        val normalized = Normalizer.normalize(path, Normalizer.Form.NFKC)
        if (normalized.length > 4_096) return false
        val file = normalized.replace('\\', '/').substringAfterLast('/')
        if (file.isBlank() || compound.containsMatchIn(file) ||
            range.findAll(file).any { !resolutionSuffix.matches(it.groupValues[1]) } ||
            fraction.findAll(file).any { !resolutionSuffix.matches(it.groupValues[1]) }) return false
        val pairs = seasonEpisode.findAll(file).toList()
        val expectedSeason = tvdbSeason ?: catalog?.season
        val expectedEpisode = tvdbEpisode ?: catalog?.relativeEpisode
        if (pairs.any { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toIntOrNull() != expectedSeason ||
                (it.groupValues[2].ifEmpty { it.groupValues[4] }).toIntOrNull() != expectedEpisode }) return false
        // Standalone season markers are ambiguous alongside absolute labels.
        val remainder = StringBuilder(file).also { text -> pairs.forEach { match ->
            for (index in match.range) text.setCharAt(index, ' ')
        } }.toString()
        if (standaloneSeason.containsMatchIn(remainder)) return false
        val absolute = (eAbsolute.findAll(remainder) + dashAbsolute.findAll(remainder)).toList()
        // Kitsu numbers are scoped to the complete catalog title.
        val standaloneNumber = if (catalog != null) malEpisode else absoluteEpisode
        if (absolute.any { standaloneNumber == null || it.groupValues[1].toIntOrNull() != standaloneNumber }) return false
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
        private val namedEdition = Regex("\\b(?:season\\s*\\d+|\\d+(?:st|nd|rd|th)?\\s+season|(?:part|cour)\\s*\\d+)\\s*$", RegexOption.IGNORE_CASE)

        /** Only selects a proof lookup. Actual matching still validates the complete file. */
        internal fun hasStandaloneEpisodeHint(value: String): Boolean {
            if (value.length > 4_096) return false
            val file = Normalizer.normalize(value, Normalizer.Form.NFKC).replace('\\', '/').substringAfterLast('/')
            return !seasonEpisode.containsMatchIn(file) &&
                (eAbsolute.containsMatchIn(file) || dashAbsolute.containsMatchIn(file))
        }

        internal fun fromKitsu(malId: Long, kitsuId: Long, anime: KitsuAnimeEpisodes): NativeAnimeMappingRecord? {
            val yearSuffix = " (${anime.startYear})"
            val yearAlias = anime.canonicalTitle.takeUnless { it.endsWith(yearSuffix) }?.let { it + yearSuffix }
            val title = NativeAnimeTitleIdentity.create(malId, anime.canonicalTitle,
                listOfNotNull(yearAlias), preserveEdition = true) ?: return null
            val editionQualified = namedEdition.containsMatchIn(anime.canonicalTitle)
            val oneToOneSeason = anime.episodes.all { (number, episode) ->
                episode.season == 1 && (episode.relativeNumber == null || episode.relativeNumber == number)
            }
            return NativeAnimeMappingRecord(malId, anime.episodes.mapValues { (number, episode) ->
                NativeAnimeEpisodeProof(malId, number, null, null, null, null, null, title,
                    catalog = CatalogCoordinates("kitsu:$kitsuId:${episode.id}", episode.season,
                        episode.relativeNumber ?: number.takeIf { oneToOneSeason }, editionQualified))
            })
        }

        internal fun fromCorroboratedKitsu(json: String, kitsuId: Long, links: LinkedKitsuEpisodes): NativeAnimeMappingRecord? {
            val malId = corroborateKitsu(json, kitsuId, links) ?: return null
            val catalog = fromKitsu(malId, kitsuId, links.anime) ?: return null
            val mapped = try { Decoder.decode(json, malId, links.anime.episodes) }
                catch (_: Exception) { null } ?: return catalog
            return NativeAnimeMappingRecord(malId, catalog.episodes.mapValues { (ordinal, local) ->
                val tvdb = mapped.episodes[ordinal] ?: return@mapValues local
                NativeAnimeEpisodeProof(malId, ordinal, tvdb.tvdbShowId, tvdb.tvdbSeason, tvdb.tvdbEpisode,
                    tvdb.absoluteEpisode, tvdb.tvdbEpisodeId, local.titleIdentity, catalog = local.catalog)
            })
        }

        internal fun fromMal(malId: Long, episode: Int, source: com.nuvio.tv.data.mal.MalSourceMetadata): NativeAnimeEpisodeProof? {
            if (source.malId != malId || source.mediaType !in setOf("tv", "ona", "ova", "special") ||
                episode !in 1..source.episodeCount) return null
            val start = source.startDate ?: return null
            val year = try {
                when (start.length) {
                    4 -> java.time.Year.parse(start).value
                    7 -> java.time.YearMonth.parse(start).year
                    10 -> java.time.LocalDate.parse(start).year
                    else -> return null
                }
            } catch (_: java.time.DateTimeException) { return null }
            if (year !in 1900..2099) return null
            val title = NativeAnimeTitleIdentity.create(malId, source.title, listOf("${source.title} ($year)"),
                preserveEdition = true) ?: return null
            return NativeAnimeEpisodeProof(malId, episode, null, null, null, null, null, title,
                catalog = CatalogCoordinates("mal", null, null, editionQualified = false))
        }

        // The only constructor path performs the complete bounded public-response validation.
        internal fun decodeMapping(json: String, expectedMalId: Long): NativeAnimeMappingRecord? = try {
            Decoder.decode(json, expectedMalId)
        } catch (_: Exception) { null }

        internal fun withVerifiedParent(record: NativeAnimeMappingRecord, json: String): NativeAnimeMappingRecord =
            try { Decoder.withVerifiedParent(record, json) } catch (_: Exception) { record }

        internal fun kitsuLink(json: String, expectedMalId: Long): KitsuLink? =
            try { Decoder.kitsuLink(json, expectedMalId) } catch (_: Exception) { null }

        internal fun kitsuEpisodes(json: String, link: KitsuLink, expectedMalId: Long): KitsuAnimeEpisodes? =
            try { Decoder.kitsuEpisodes(json, link, expectedMalId) } catch (_: Exception) { null }

        internal fun kitsuLinks(json: String, expectedKitsuId: Long): LinkedKitsuEpisodes? =
            try { Decoder.kitsuLinks(json, expectedKitsuId) } catch (_: Exception) { null }

        internal fun corroborateKitsu(json: String, kitsuId: Long, links: LinkedKitsuEpisodes): Long? =
            try { Decoder.corroborateKitsu(json, kitsuId, links) } catch (_: Exception) { null }

        private val titleWords = Regex("[\\p{L}\\p{N}]+")
        private val titleApostrophes = Regex("['\u2019\u02bc]")
        private fun normalizedTitle(value: String): String = titleWords.findAll(titleApostrophes.replace(
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT), ""))
            .joinToString(" ") { it.value }
    }

    private data class CatalogCoordinates(val identity: String, val season: Int?, val relativeEpisode: Int?,
        val editionQualified: Boolean) {
        val cacheSuffix: String get() = "|$identity:$season:$relativeEpisode:$editionQualified"
    }

    private class VerifiedParentTitle(val imdbId: String, val title: String,
        private val identity: NativeAnimeTitleIdentity) {
        val fingerprint = "$imdbId:${identity.fingerprint}"

        /** Exact complete parent title before the episode, never a sequel-prefix or bracket claim. */
        fun matchRelease(value: String): NativeAnimeTitleIdentity.Match = identity.matchRelease(value, complete = true)
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
        private val genericEpisodeTitle =
            Regex("(?:episode|ep|e|chapter|part|special|ova)(?: ?[0-9]+)?|第 ?[0-9]+ ?[話回]|[0-9]+")
        private val mappedCoordinates = listOf("tvdbShowId", "tvdbId", "seasonNumber", "episodeNumber", "absoluteEpisodeNumber")
        private val positiveKey = Regex("[1-9][0-9]{0,3}")
        private val specialKey = Regex("[SCTPO][1-9][0-9]{0,3}")
        private val language = Regex("[A-Za-z0-9-]{1,32}")
        private val integer = Regex("0|[1-9][0-9]{0,9}")

        fun kitsuLink(json: String, expectedMalId: Long): KitsuLink? {
            if (json.length > MAX_BODY || json.toByteArray(Charsets.UTF_8).size > MAX_BODY) return null
            val mappings = strictTree(json).objectOrNull()?.get("mappings").objectOrNull() ?: return null
            if (mappings["mal_id"].whole(1, Int.MAX_VALUE.toLong()) != expectedMalId) return null
            val kitsuId = mappings["kitsu_id"].whole(1, Int.MAX_VALUE.toLong()) ?: return null
            val aniListId = mappings["anilist_id"].whole(1, Int.MAX_VALUE.toLong())
            return KitsuLink(kitsuId, aniListId)
        }

        fun kitsuEpisodes(json: String, link: KitsuLink, expectedMalId: Long): KitsuAnimeEpisodes? {
            if (json.length > MAX_BODY || json.toByteArray(Charsets.UTF_8).size > MAX_BODY) return null
            return strictTree(json).objectOrNull()?.let {
                decodeLinkedKitsuAnimeEpisodes(it, link.kitsuId, expectedMalId, link.aniListId)
            }
        }

        fun kitsuLinks(json: String, expectedKitsuId: Long): LinkedKitsuEpisodes? {
            if (json.length > MAX_BODY || json.toByteArray(Charsets.UTF_8).size > MAX_BODY) return null
            return strictTree(json).objectOrNull()?.let { decodeKitsuLinks(it, expectedKitsuId) }
        }

        fun corroborateKitsu(json: String, kitsuId: Long, links: LinkedKitsuEpisodes): Long? {
            if (json.length > MAX_BODY || json.toByteArray(Charsets.UTF_8).size > MAX_BODY) return null
            val ids = strictTree(json).objectOrNull()?.get("mappings").objectOrNull() ?: return null
            if (ids["kitsu_id"].whole(1, Int.MAX_VALUE.toLong()) != kitsuId) return null
            val malId = ids["mal_id"].whole(1, Int.MAX_VALUE.toLong()) ?: return null
            if (links.malId != null && links.malId != malId) return null
            if (links.aniListId != null && ids["anilist_id"].whole(1, Int.MAX_VALUE.toLong()) != links.aniListId) return null
            return malId
        }

        fun decode(json: String, expectedMalId: Long,
            kitsuEpisodes: Map<Int, KitsuAnimeEpisode>? = null): NativeAnimeMappingRecord? {
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
            val trustedTitles = root["titles"].objectOrNull()?.entrySet()
                ?.sortedBy { titleLanguagePriority(it.key) }?.mapNotNull { it.value.stringOrNull() }
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
                if ((!positiveKey.matches(key) && !special) ||
                    (if (special) key.substring(1) else key).toInt() > MAX_EPISODES) return null
                val row = value.objectOrNull() ?: return null
                if (!row.only(episodeFields) || row["episode"].stringOrNull() != key) return null
                if (episodeStrings.any { !row.optional(it) { text -> text.stringOrNull() != null } } ||
                    !row.optional("title", ::titles) || !row.optional("rating") { rating ->
                        (rating.stringOrNull()?.toDoubleOrNull() ?: rating.finiteNumber())?.let { it in 0.0..10.0 } == true
                    } || episodeNumbers.any { !row.optional(it) { number -> number.finiteNumber()?.let { it in 0.0..Int.MAX_VALUE.toDouble() } == true } }) return null
                if (special) continue // No season-zero/special/decimal proof is ever minted.
                // AniDB can list a new episode before TVDB maps it. That row carries no
                // episode proof and must not discard other fully verified episodes.
                if (mappedCoordinates.all { row[it] == null || row[it].isJsonNull }) continue
                val malEpisode = key.toInt()
                val rowShow = row["tvdbShowId"].whole(1, Int.MAX_VALUE.toLong()) ?: return null
                val episodeId = row["tvdbId"].whole(1, Int.MAX_VALUE.toLong()) ?: return null
                val season = row["seasonNumber"].whole(0, 1_000)?.toInt() ?: return null
                val episode = row["episodeNumber"].whole(1, MAX_EPISODES.toLong())?.toInt() ?: return null
                val absolute = row["absoluteEpisodeNumber"]?.takeUnless { it.isJsonNull }?.let {
                    it.whole(1, 100_000)?.toInt() ?: return null
                }
                if (rowShow != show || !coordinates.add(season to episode) || !episodeIds.add(episodeId)) return null
                if (season == 0) continue // Some numeric AniDB entries map to TVDB specials.
                if (absolute != null && !absolutes.add(absolute)) return null
                val names = row["title"].objectOrNull()?.entrySet()?.mapNotNull { it.value.stringOrNull() }.orEmpty()
                if (kitsuEpisodes != null && ((absolute != null && absolute != malEpisode) ||
                    !matchesKitsuEpisode(row, names, kitsuEpisodes[malEpisode]))) continue
                val episodeTitles = names.map(::normalizedTitle).filter(String::isNotBlank).toSet()
                result[malEpisode] = NativeAnimeEpisodeProof(expectedMalId, malEpisode, show, season, episode,
                    absolute, episodeId, titleIdentity, episodeTitles)
            }
            val parentImdb = mappings["imdb_id"].stringOrNull()?.takeIf { Regex("tt[0-9]{1,12}").matches(it) }
            return result.takeIf { it.isNotEmpty() }?.let { NativeAnimeMappingRecord(expectedMalId, it, parentImdb) }
        }

        private fun matchesKitsuEpisode(row: JsonObject, names: List<String>, episode: KitsuAnimeEpisode?): Boolean {
            val title = episode?.canonicalTitle?.let(::episodeLinkTitle) ?: return false
            val date = episode.airDate?.toString() ?: return false
            val dates = listOfNotNull(row["airDate"].stringOrNull(), row["airdate"].stringOrNull()).distinct()
            return dates.singleOrNull() == date && names.any { episodeLinkTitle(it) == title }
        }

        private fun episodeLinkTitle(value: String): String? {
            if (value.length !in 1..512 || value.any(Char::isISOControl)) return null
            return normalizedTitle(value.replace('×', 'x')).takeIf {
                it.any(Char::isLetter) && !genericEpisodeTitle.matches(it)
            }
        }

        fun withVerifiedParent(record: NativeAnimeMappingRecord, json: String): NativeAnimeMappingRecord {
            val imdb = record.mappedParentImdbId ?: return record
            if (json.length > MAX_BODY || json.toByteArray(Charsets.UTF_8).size > MAX_BODY) return record
            val meta = strictTree(json).objectOrNull()?.get("meta").objectOrNull() ?: return record
            if (meta["id"].stringOrNull() != imdb || meta["imdb_id"].stringOrNull() != imdb ||
                meta["type"].stringOrNull() != "series") return record
            val title = meta["name"].stringOrNull() ?: return record
            val identity = NativeAnimeTitleIdentity.create(record.malId, title, emptyList()) ?: return record
            val videos = meta["videos"]?.takeIf { it.isJsonArray && it.asJsonArray.size() <= MAX_EPISODES }
                ?.asJsonArray ?: return record
            val episodes = linkedMapOf<Pair<Int, Int>, String>()
            for (value in videos) {
                val row = value.objectOrNull() ?: return record
                val season = row["season"].whole(1, 1_000)?.toInt() ?: continue
                val episode = row["episode"].whole(1, MAX_EPISODES.toLong())?.toInt() ?: continue
                if (row["id"].stringOrNull() != "$imdb:$season:$episode") return record
                val name = row["name"].stringOrNull()?.let(::normalizedTitle) ?: continue
                if (episodes.put(season to episode, name) != null) return record
            }
            val parent = VerifiedParentTitle(imdb, title, identity)
            val proofs = record.episodes.mapValues { (_, proof) ->
                val season = proof.tvdbSeason ?: return@mapValues proof
                val episode = proof.tvdbEpisode ?: return@mapValues proof
                val name = episodes[season to episode]
                if (name == null || name !in proof.episodeTitles) proof else NativeAnimeEpisodeProof(
                    proof.malId, proof.malEpisode, proof.tvdbShowId, proof.tvdbSeason, proof.tvdbEpisode,
                    proof.absoluteEpisode, proof.tvdbEpisodeId, proof.titleIdentity, proof.episodeTitles, parent)
            }
            return NativeAnimeMappingRecord(record.malId, proofs, imdb)
        }

        private fun titles(value: JsonElement): Boolean =
            value.isJsonObject && value.asJsonObject.size() <= 128 && value.asJsonObject.entrySet().all { (key, text) ->
                language.matches(key) && (text.stringOrNull() != null || text.isJsonNull)
            }
        // Mapping object order is not language preference. Give the bounded query snapshot a
        // useful English/Romaji/Japanese order without dropping any trusted proof aliases.
        private fun titleLanguagePriority(language: String): Int = when (language.lowercase(Locale.ROOT)) {
            "en", "english" -> 0
            "x-jat", "ja-latn", "romaji" -> 1
            "ja", "jp", "japanese" -> 2
            else -> 3
        }
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

internal data class KitsuLink(val kitsuId: Long, val aniListId: Long?)

internal class NativeAnimeMappingRecord(val malId: Long, episodes: Map<Int, NativeAnimeEpisodeProof>,
    internal val mappedParentImdbId: String? = null) {
    val episodes: Map<Int, NativeAnimeEpisodeProof> = Collections.unmodifiableMap(LinkedHashMap(episodes))
    internal fun withVerifiedParentMetadata(json: String): NativeAnimeMappingRecord =
        NativeAnimeEpisodeProof.withVerifiedParent(this, json)
}

internal object NativeAnimeMappingCodec {
    fun parse(json: String, expectedMalId: Long): NativeAnimeMappingRecord? = NativeAnimeEpisodeProof.decodeMapping(json, expectedMalId)
}
