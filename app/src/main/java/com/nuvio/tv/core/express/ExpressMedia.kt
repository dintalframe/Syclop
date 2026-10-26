package com.nuvio.tv.core.express

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.plugin.absoluteAnimeEpisodeNumber
import com.nuvio.tv.data.mal.MalMetadataService
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
    val nativeAnimeEpisode: com.nuvio.tv.core.anime.NativeAnimeEpisodeProof? = null
) {
    val hasMappedEpisodeCoordinates: Boolean get() = nativeAnimeEpisode != null
    val catalogSeason: Int? get() = if (nativeAnimeEpisode != null) 1 else season
    val catalogEpisode: Int? get() = nativeAnimeEpisode?.malEpisode ?: episode
    val tvdbSeason: Int? get() = nativeAnimeEpisode?.tvdbSeason
    val tvdbEpisode: Int? get() = nativeAnimeEpisode?.tvdbEpisode

    fun queryTitles(): List<String> {
        if (!anime) return listOf(title)
        // The proof-bound mapping snapshot must also recognize every searched alias. Reading
        // a separate mutable metadata alias list here can find a file the resolver must reject.
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

/** Metadata uses catalog IDs, never title-search guesses or season-to-absolute arithmetic. */
@Singleton
class ExpressMetadataResolver @Inject constructor(
    private val http: ExpressHttpClient,
    private val tmdb: TmdbService,
    private val mal: MalMetadataService,
    private val animeMappings: com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings? = null
) : ExpressMetadataSource {
    private val cache = ConcurrentHashMap<String, JsonObject>()

    override suspend fun resolve(request: ExpressMediaRequest): ExpressMedia {
        if (request.videoId.startsWith("mal:")) {
            val parts = request.videoId.split(':')
            val id = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ExpressException("The MyAnimeList catalog ID is invalid.")
            val source = mal.sourceMetadata(id)
            if (source.malId != id) throw ExpressException("MyAnimeList metadata did not match the requested record.")
            val isMovie = source.mediaType == "movie"
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
                ?: if (isMovie) null else throw ExpressException("A confirmed anime episode mapping is unavailable.")
            // A valid S/E mapping remains useful without an absolute episode. Templates that
            // require the absent coordinate are skipped; catalog-relative numbers never fill it.
            val absolute = proof?.absoluteEpisode
            val identity = if (isMovie) com.nuvio.tv.core.anime.NativeAnimeTitleIdentity.create(
                source.malId, source.title, source.alternativeTitles) else proof?.titleIdentity
            if (identity == null) throw ExpressException("A confirmed anime title identity is unavailable.")
            return ExpressMedia(if (isMovie) "movie" else "series", source.title,
                source.imdbId, source.year, proof?.tvdbSeason, proof?.tvdbEpisode, absoluteNumber = absolute, anime = true,
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
        val meta = cache[key] ?: run {
            val document = http.get("https://v3-cinemeta.strem.io/meta/$type/$imdbId.json", 2 * 1024 * 1024)
            val root = parseExpressObject(document.text)
            val value = root.getAsJsonObject("meta") ?: throw ExpressException("Catalog metadata is unavailable.")
            val returnedId = value.string("imdb_id") ?: value.string("id")
            if (returnedId != imdbId) throw ExpressException("Catalog metadata did not match the requested IMDb ID.")
            if (cache.size >= 100) cache.clear()
            cache[key] = value
            value
        }
        val title = meta.string("name")?.takeIf { it.isNotBlank() }
            ?: throw ExpressException("Catalog did not provide a title.")
        val year = listOfNotNull(meta.string("year"), meta.string("releaseInfo"))
            .firstNotNullOfOrNull { Regex("^(\\d{4})(?:\\D|$)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return ExpressMedia(type, title, imdbId, year, season, episode,
            absoluteNumber = null, anime = request.type == "anime")
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

/** Native MAL rows must carry the trusted title in every supplied release-identity field. */
internal fun ExpressMedia.acceptsNativeAnimeRow(row: ExpressRow): Boolean {
    val identity = nativeAnimeIdentity ?: return true // Non-MAL metadata paths retain prior behavior.
    val names = listOfNotNull(row.title, row.filename).filter { it.isNotBlank() }
    return names.isNotEmpty() && names.all(identity::matchesRelease)
}
