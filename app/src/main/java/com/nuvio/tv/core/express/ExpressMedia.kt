package com.nuvio.tv.core.express

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.core.anime.NativeAnimeEpisodeProof
import com.nuvio.tv.core.debrid.TurkishBroadcastEpisode
import com.nuvio.tv.core.debrid.hasDebridVideoExtension
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.plugin.absoluteAnimeEpisodeNumber
import com.nuvio.tv.data.mal.MalMetadataService
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ExpressMediaRequest(val type: String, val videoId: String, val season: Int? = null, val episode: Int? = null)
data class ExpressMedia(
    val type: String,
    val title: String,
    val imdbId: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val absoluteNumber: Int? = null,
    val anime: Boolean = false,
    val titleAliases: List<String> = emptyList(),
    /** Minted only from trusted native MAL metadata/mapping, never from an addon row. */
    val nativeAnimeIdentity: com.nuvio.tv.core.anime.NativeAnimeTitleIdentity? = null,
    /** Keeps catalog-relative, mapped TVDB and optional release-absolute coordinates distinct. */
    val nativeAnimeEpisode: com.nuvio.tv.core.anime.NativeAnimeEpisodeProof? = null,
    /** Optional original spelling resolved against this record's IMDb identity. */
    val originalTitle: String? = null,
    internal val requiresStandaloneKitsuLabel: Boolean = false,
    internal val preferOriginalTitle: Boolean = false,
    internal val turkishBroadcastEpisode: TurkishBroadcastEpisode? = null
) {
    val hasMappedEpisodeCoordinates: Boolean get() = nativeAnimeEpisode?.catalogOnly == false
    val catalogSeason: Int? get() = nativeAnimeEpisode?.catalogSeason ?: season
    val catalogEpisode: Int? get() = nativeAnimeEpisode?.malEpisode ?: episode
    val tvdbSeason: Int? get() = nativeAnimeEpisode?.tvdbSeason
    val tvdbEpisode: Int? get() = nativeAnimeEpisode?.tvdbEpisode

    fun queryTitles(): List<String> {
        if (!anime) {
            val canonical = expressQueryTitle(title)
            val original = originalTitle?.let(::expressQueryTitle)
            val folded = (original ?: canonical)?.let(::foldExpressTitle)
            return (if (preferOriginalTitle) listOfNotNull(original, folded, canonical)
                else listOfNotNull(canonical, original, folded)).distinct().take(3)
        }
        // The proof-bound mapping snapshot must also recognize every searched alias. Reading
        // a separate mutable metadata alias list here can find a file the resolver must reject.
        nativeAnimeEpisode?.let { return it.queryTitles() }
        nativeAnimeIdentity?.let { return it.queryTitles() }
        val canonical = title.trim()
        return (listOf(canonical) + titleAliases).map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) }.distinct().take(3)
    }

    fun variables(queryTitle: String = title): Map<String, String> = buildMap {
        put("title", queryTitle)
        imdbId?.let { put("imdbId", it); put("showImdbId", it) }
        year?.let { put("year", it.toString()) }
        season?.let { put("season", it.toString()); put("seasonCode", "s${it.toString().padStart(2, '0')}") }
        episode?.let { put("episode", it.toString()) }
        if (season != null && episode != null) put("episodeCode", "s${season.toString().padStart(2, '0')}e${episode.toString().padStart(2, '0')}")
        absoluteNumber?.let { put("absoluteNumber", it.toString()) }
        catalogSeason?.let { put("catalogSeason", it.toString()) }
        catalogEpisode?.let { put("catalogEpisode", it.toString()) }
        tvdbSeason?.let { put("tvdbSeason", it.toString()) }
        tvdbEpisode?.let { put("tvdbEpisode", it.toString()) }
    }
}

fun interface ExpressMetadataSource { suspend fun resolve(request: ExpressMediaRequest): ExpressMedia }

internal class ExpressAnimeMappingUnavailable : ExpressException("A confirmed anime episode mapping is unavailable.")

/** Metadata uses catalog IDs, never title-search guesses or season-to-absolute arithmetic. */
@Singleton
class ExpressMetadataResolver @Inject constructor(
    private val http: ExpressHttpClient,
    private val tmdb: TmdbService,
    private val mal: MalMetadataService,
    private val animeMappings: NativeAnimeEpisodeMappings? = null
) : ExpressMetadataSource {
    private data class TvmazeShow(val id: Int, val title: String, val turkish: Boolean)
    private data class Metadata(val title: String, val year: Int?, val originalTitle: String?,
        val turkish: Boolean, val refreshAfterMillis: Long, val tvmaze: TvmazeShow?,
        val catalogEpisodeNumbers: Map<String, Int>)
    private val cache = ConcurrentHashMap<String, Metadata>()
    private val broadcastProofs = ConcurrentHashMap<String, TurkishBroadcastEpisode>()

    override suspend fun resolve(request: ExpressMediaRequest): ExpressMedia {
        if (request.videoId.startsWith("kitsu:")) {
            val (id, ordinal) = NativeAnimeEpisodeMappings.kitsuIdentity(request.videoId)
                ?: throw ExpressAnimeMappingUnavailable()
            if (request.type == "movie") throw ExpressAnimeMappingUnavailable()
            val proof = animeMappings?.proof(request.videoId)?.takeIf { it.isKitsuEpisode(id, ordinal) }
                ?: throw ExpressAnimeMappingUnavailable()
            val identity = proof.titleIdentity ?: throw ExpressAnimeMappingUnavailable()
            val year = identity.verifiedReleaseYear ?: throw ExpressAnimeMappingUnavailable()
            val title = proof.queryTitles().firstOrNull() ?: throw ExpressAnimeMappingUnavailable()
            return ExpressMedia("series", title, null, year, proof.tvdbSeason, proof.tvdbEpisode,
                absoluteNumber = proof.absoluteEpisode, anime = true, nativeAnimeIdentity = identity,
                nativeAnimeEpisode = proof, requiresStandaloneKitsuLabel = proof.catalogOnly)
        }
        if (request.videoId.startsWith("mal:")) {
            val parts = request.videoId.split(':')
            val id = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ExpressException("The MyAnimeList catalog ID is invalid.")
            val source = mal.sourceMetadata(id)
            if (source.malId != id) throw ExpressException("MyAnimeList metadata did not match the requested record.")
            val isMovie = source.mediaType == "movie"
            if (!isMovie && parts.size == 2) throw ExpressAnimeMappingUnavailable()
            val episode = if (isMovie) null else absoluteAnimeEpisodeNumber(request.videoId)
                ?.takeIf { it > 0 && (source.episodeCount <= 0 || it <= source.episodeCount) }
                ?: if (isMovie) null else throw ExpressException("A valid MyAnimeList episode ID is required.")
            if (!isMovie && ((request.season != null && request.season != 1) ||
                (request.episode != null && request.episode != episode))) {
                throw ExpressException("The MyAnimeList episode ID conflicts with the requested episode.")
            }
            // Keep MAL identity/relative episode untouched. Only a validated public episode record
            // can supply the separate release-absolute coordinate used by declarative anime searches.
            val proof = if (isMovie) null else animeMappings?.proof(request.videoId)
                ?.takeIf { it.hasTrustedTitleIdentity && it.malId == id && it.malEpisode == episode }
                ?: if (isMovie) null else throw ExpressAnimeMappingUnavailable()
            // A valid S/E mapping remains useful without an absolute episode. Templates that
            // require the absent coordinate are skipped; catalog-relative numbers never fill it.
            val absolute = proof?.absoluteEpisode
            val identity = if (isMovie) com.nuvio.tv.core.anime.NativeAnimeTitleIdentity.create(
                source.malId, source.title, source.alternativeTitles) else proof?.titleIdentity
            if (identity == null) throw ExpressException("A confirmed anime title identity is unavailable.")
            return ExpressMedia(if (isMovie) "movie" else "series", source.title,
                proof?.verifiedParentImdbId, source.year, proof?.tvdbSeason, proof?.tvdbEpisode, absoluteNumber = absolute, anime = true,
                titleAliases = source.alternativeTitles, nativeAnimeIdentity = identity,
                nativeAnimeEpisode = proof)
        }
        val type = if (request.type == "movie") "movie" else "series"
        val parts = request.videoId.trim().split(':')
        var offset = 1
        val imdbId = when {
            parts.firstOrNull()?.matches(Regex("tt[0-9]+")) == true -> parts[0]
            parts.firstOrNull() == "tmdb" && parts.getOrNull(1)?.toIntOrNull() != null -> {
                offset = 2
                tmdb.tmdbToImdb(parts[1].toInt(), type)
            }
            else -> null
        }?.takeIf { it.matches(Regex("tt[0-9]+")) }
            ?: throw ExpressException("This catalog has no verified IMDb metadata for Express source searches.")
        val idSeason = parts.getOrNull(offset)?.toIntOrNull()
        val idEpisode = parts.getOrNull(offset + 1)?.toIntOrNull()
        if (request.season != null && idSeason != null && request.season != idSeason ||
            request.episode != null && idEpisode != null && request.episode != idEpisode) {
            throw ExpressException("The catalog episode ID conflicts with the requested season or episode.")
        }
        val season = request.season ?: idSeason
        val episode = request.episode ?: idEpisode
        if (type != "movie" && (season == null || episode == null)) throw ExpressException("Verified season and episode metadata is required.")
        val key = "$type:$imdbId"
        val meta = cache[key]?.takeIf { it.refreshAfterMillis > System.currentTimeMillis() } ?: run {
            val document = http.get("https://v3-cinemeta.strem.io/meta/$type/$imdbId.json", 2 * 1024 * 1024)
            val root = parseExpressObject(document.text)
            val value = root.getAsJsonObject("meta") ?: throw ExpressException("Catalog metadata is unavailable.")
            val returnedId = value.string("imdb_id") ?: value.string("id")
            if (returnedId != imdbId) throw ExpressException("Catalog metadata did not match the requested IMDb ID.")
            val title = value.string("name")?.takeIf { it.isNotBlank() }
                ?: throw ExpressException("Catalog did not provide a title.")
            val year = listOfNotNull(value.string("year"), value.string("releaseInfo"))
                .firstNotNullOfOrNull { Regex("^(\\d{4})(?:\\D|$)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val turkish = value.isTurkishProduction()
            val (original, tvmaze) = withContext(Dispatchers.IO) {
                var tmdbTitle: String? = null
                val fallback = withTimeoutOrNull(2_500L) {
                    tmdbTitle = tmdb.findOriginalTitle(imdbId, type, year)
                    val tvmaze = if (type == "series" && year != null && (tmdbTitle == null || turkish))
                        tvmazeShow(imdbId, year) else null
                    val wikidata = if (tmdbTitle == null && type == "movie" && turkish && year != null)
                        http.wikidataTurkishMovieTitle(imdbId, year) else null
                    (tvmaze?.title ?: wikidata) to tvmaze
                }
                (tmdbTitle ?: fallback?.first) to fallback?.second
            }
            val refreshAfter = when {
                turkish && (original == null || type == "series" && tvmaze == null) -> System.currentTimeMillis() + 300_000L
                tvmaze?.turkish == true -> System.currentTimeMillis() + 1_800_000L
                else -> Long.MAX_VALUE
            }
            val catalogEpisodeNumbers = if (tvmaze?.turkish == true) value.catalogTurkishEpisodeNumbers(imdbId) else emptyMap()
            val metadata = Metadata(title, year, original, turkish || tvmaze?.turkish == true, refreshAfter,
                tvmaze, catalogEpisodeNumbers)
            if (cache.size >= 100) cache.clear()
            cache[key] = metadata
            metadata
        }
        val broadcast = if (request.type != "anime" && type == "series" && season != null && episode != null)
            confirmedBroadcast(meta, imdbId, request.videoId, season, episode) else null
        return ExpressMedia(type, meta.title, imdbId, meta.year, season, episode,
            absoluteNumber = null, anime = request.type == "anime", originalTitle = meta.originalTitle,
            preferOriginalTitle = meta.turkish && meta.originalTitle != null, turkishBroadcastEpisode = broadcast)
    }

    private suspend fun confirmedBroadcast(meta: Metadata, imdbId: String, videoId: String, season: Int, episode: Int):
        TurkishBroadcastEpisode? {
        val show = meta.tvmaze?.takeIf { it.turkish } ?: return null
        val number = meta.catalogEpisodeNumbers["$imdbId:$season:$episode"] ?: return null
        val proof = TurkishBroadcastEpisode(videoId.trim(), season, episode, number, show.title, meta.year)
        val key = "${show.id}:${meta.refreshAfterMillis}:${proof.cacheKey}"
        broadcastProofs[key]?.let { return it }
        return withTimeoutOrNull(1_500L) { http.confirmTurkishBroadcastEpisode(show.id, proof) }?.also {
            if (broadcastProofs.size >= 128) broadcastProofs.clear()
            broadcastProofs[key] = it
        }
    }

    private suspend fun tvmazeShow(imdbId: String, year: Int): TvmazeShow? = try {
        var showId: Int? = null
        fun validate(url: HttpUrl) {
            if (url.scheme != "https" || url.host != "api.tvmaze.com" || url.port != 443)
                throw ExpressException("TVmaze metadata changed origin.")
            val id = Regex("^/shows/([1-9][0-9]*)$").matchEntire(url.encodedPath)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (id != null && url.query == null && (showId == null || showId == id)) showId = id
            else if (showId != null || url.encodedPath != "/lookup/shows" || url.query != "imdb=$imdbId")
                throw ExpressException("TVmaze metadata changed identity.")
        }
        val document = http.get("https://api.tvmaze.com/lookup/shows?imdb=$imdbId", 512 * 1024,
            beforeRedirect = ::validate)
        validate(document.url.toHttpUrl())
        val value = parseExpressObject(document.text)
        val id = value.string("id")?.toIntOrNull()?.takeIf { it > 0 }
        val external = value.get("externals")?.takeIf { it.isJsonObject }?.asJsonObject?.string("imdb")
        if (id == null || (showId != null && showId != id) || external != imdbId ||
            value.string("premiered")?.take(4)?.toIntOrNull() != year) null
        else value.string("name")?.let(::expressQueryTitle)?.let {
            TvmazeShow(id, it, value.string("language").equals("Turkish", ignoreCase = true))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

internal fun expressQueryTitle(value: String): String? = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim().takeIf { it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) }

private fun foldExpressTitle(value: String): String = Normalizer.normalize(
    Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("(?<=\\p{IsLatin})\\p{M}+"), "")
        .replace('ı', 'i'), Normalizer.Form.NFC)

private val releaseGroupPrefix = Regex("^(?:\\s*\\[[^\\[\\]]+])+")
private val movieReleaseMarker = Regex(
    "(?:^| )(?:[0-9]{3,4}p|4k|uhd|web ?(?:dl|rip)|blu ?ray|[bh]d ?rip|[hx] ?26[45]|hevc|av1)(?= |$)"
)
private val movieReleaseYear = Regex(" (?:18|19|20|21)\\d{2}(?= |$)")
private val movieEditionSuffix = Regex(" (?:extended(?: cut)?|directors cut|theatrical cut|remastered|unrated)$")
private val genericMovieLabel = Regex("(?:^| )(?:movie|film|video|release|file|download|hd|hdr|sdr|dolby vision|dual audio|multi|dubbed|english|turkish|turkce)(?= |$)")
private val releaseWords = Regex("[^\\p{L}\\p{N}]+")
private val releaseApostrophes = Regex("['’ʼ]")

/** Compare explicit movie release titles while retaining rows with sparse or display-only metadata. */
internal fun ExpressMedia.acceptsMovieReleaseTitles(row: ExpressRow): Boolean {
    if (type != "movie" || anime) return true
    fun normalized(value: String) = foldExpressTitle(Normalizer.normalize(value, Normalizer.Form.NFKC))
        .lowercase(Locale.ROOT).replace(releaseApostrophes, "").replace("&", " and ")
        .replace(releaseWords, " ").trim()
    val known = (listOfNotNull(title, originalTitle) + titleAliases).mapNotNull(::expressQueryTitle)
        .map(::normalized).filter(String::isNotEmpty).toSet()
    if (known.isEmpty()) return true
    fun releaseTitle(value: String): String? {
        val label = value.trim().replace(releaseGroupPrefix, "")
        val videoFile = label.hasDebridVideoExtension()
        val release = normalized(if (videoFile) label.substringBeforeLast('.') else label)
        val marker = movieReleaseMarker.find(release)
        val prefix = release.substring(0, marker?.range?.first ?: release.length)
        if (prefix in known) return prefix
        val year = movieReleaseYear.findAll(prefix).lastOrNull()
        if (marker == null && !(videoFile && year?.range?.last == prefix.lastIndex)) return null
        if (!videoFile && year == null) return null
        val title = prefix.substring(0, year?.range?.first ?: prefix.length).replace(movieEditionSuffix, "")
        return title.takeIf { it in known || genericMovieLabel.replace(it, "").any(Char::isLetter) }
    }
    fun compatible(field: String, allowTitlePunctuation: Boolean): Boolean {
        val name = field.lineSequence().firstOrNull { it.isNotBlank() } ?: return true
        if (name.length > 4_096) return true
        val basename = name.replace('\\', '/').substringAfterLast('/')
        val candidate = releaseTitle(basename) ?: return true
        return candidate in known || (allowTitlePunctuation && name != basename &&
            releaseTitle(name)?.let(known::contains) == true)
    }
    return row.title?.let { compatible(it, allowTitlePunctuation = true) } != false &&
        row.filename?.let { compatible(it, allowTitlePunctuation = false) } != false
}

/** Only an explicit year immediately after a known title is evidence of another edition. */
internal fun ExpressMedia.acceptsInternationalYearHints(row: ExpressRow): Boolean {
    val premiere = year ?: return true
    if (anime || queryTitles().none { it != expressQueryTitle(title) }) return true
    fun normalized(value: String) = foldExpressTitle(value).lowercase(Locale.ROOT)
        .replace(Regex("[\\s._:–—-]+"), " ").trim()
    val titles = queryTitles().map(::normalized).distinct()
    return listOfNotNull(row.title, row.filename).all { name ->
        val text = normalized(name.replace('\\', '/').substringAfterLast('/'))
        titles.all { known ->
            if (!text.startsWith("$known ") && !text.startsWith("$known(") && !text.startsWith("$known[")) true
            else {
                val hint = Regex("^(?:\\(|\\[)?((?:18|19|20|21)\\d{2})(?:\\)|\\])?(?= |$)")
                    .find(text.removePrefix(known).trimStart())?.groupValues?.get(1)?.toIntOrNull()
                hint == null || if (type == "movie") kotlin.math.abs(hint - premiere) <= 1 else hint >= premiere
            }
        }
    }
}

internal fun parseExpressObject(text: String): JsonObject = try {
    JsonParser.parseString(text).asJsonObject
} catch (_: Exception) { throw ExpressException("Provider returned invalid JSON.") }

internal fun JsonObject.string(key: String): String? = get(key)?.scalarString()
internal fun JsonElement.scalarString(): String? = takeIf { isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

internal fun expressTemplate(template: String, variables: Map<String, String>): String? {
    var missing = false
    val result = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}").replace(template) { match ->
        variables[match.groupValues[1]] ?: run { missing = true; "" }
    }
    return result.takeUnless { missing || it.contains('{') || it.contains('}') }
}

internal fun expressEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** Native anime rows must carry the trusted title in every supplied release-identity field. */
internal fun ExpressMedia.acceptsNativeAnimeRow(row: ExpressRow): Boolean {
    val identity = nativeAnimeIdentity ?: return true
    if (requiresStandaloneKitsuLabel) {
        val file = row.filename?.takeIf(String::isNotBlank) ?: row.title ?: return false
        if (!NativeAnimeEpisodeProof.hasStandaloneEpisodeHint(file)) return false
    }
    val names = listOfNotNull(row.title, row.filename).filter { it.isNotBlank() }
    return names.isNotEmpty() && names.all { nativeAnimeEpisode?.acceptsReleaseTitle(it) ?: identity.matchesRelease(it) }
}
