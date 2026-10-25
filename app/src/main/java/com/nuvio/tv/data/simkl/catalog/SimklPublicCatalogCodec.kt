package com.nuvio.tv.data.simkl.catalog

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaLink
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

internal enum class SimklFeedType(val path: String, val contentType: ContentType) {
    MOVIES("movies", ContentType.MOVIE), TV("tv", ContentType.SERIES), ANIME("anime", ContentType.SERIES)
}

internal data class SimklGenre(val name: String, val slug: String)

/** Public discovery metadata only. No provider URLs, credentials or inferred episode numbering. */
internal data class SimklPublicItem(
    val preview: MetaPreview,
    val simklId: Long,
    val pageUrl: String
)

internal object SimklPublicCatalogCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val digits = Regex("[1-9][0-9]{0,11}")
    private val imdb = Regex("tt[0-9]{5,12}")
    private val slug = Regex("[a-z0-9][a-z0-9-]*")
    private val imagePath = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+){0,3}")
    private val adult = setOf("hentai", "erotica", "pornography", "adult")

    fun genres(body: String): List<SimklGenre> {
        val rows = json.parseToJsonElement(body) as? JsonArray ?: error("Invalid Simkl genre response.")
        require(rows.size <= 128) { "Simkl genre response is too large." }
        return rows.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val name = item.text("name", 80) ?: return@mapNotNull null
            val path = item.text("slug", 80)?.takeIf { slug.matches(it) && it !in adult } ?: return@mapNotNull null
            if (name.lowercase() in adult) null else SimklGenre(name, path)
        }.distinctBy { it.slug }
    }

    fun items(body: String, type: SimklFeedType): List<SimklPublicItem> {
        val rows = json.parseToJsonElement(body) as? JsonArray ?: error("Invalid Simkl catalog response.")
        require(rows.size <= 500) { "Simkl catalog response is too large." }
        return rows.mapNotNull { raw -> (raw as? JsonObject)?.let { item(it, type) } }
            .distinctBy { it.preview.id }
    }

    private fun item(item: JsonObject, type: SimklFeedType): SimklPublicItem? {
        val ids = item["ids"] as? JsonObject ?: return null
        val firstId = ids.positiveId("simkl_id")
        val secondId = ids.positiveId("simkl")
        if (firstId != null && secondId != null && firstId != secondId) return null
        val simklId = firstId ?: secondId ?: return null
        val title = item.text("title", 300) ?: return null
        val path = ids.text("slug", 240)?.takeIf { slug.matches(it) } ?: return null
        val genres = (item["genres"] as? JsonArray)?.takeIf { it.size <= 40 }?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.safeText(80)
        }.orEmpty().distinct()
        if (genres.any { it.lowercase() in adult || it.equals("Video Game Play", true) }) return null
        val id = if (type == SimklFeedType.ANIME) {
            "mal:${ids.positiveId("mal") ?: return null}"
        } else ids.text("imdb", 20)?.takeIf { imdb.matches(it) } ?: return null
        val animeType = item.text("anime_type", 40)
        if (type == SimklFeedType.ANIME && animeType.equals("music video", true)) return null
        val contentType = if (type == SimklFeedType.ANIME && animeType.equals("movie", true)) ContentType.MOVIE else type.contentType
        val pageUrl = "https://simkl.com/${type.path}/$simklId/$path"
        val poster = image(item.text("poster", 160), "posters", "_m")
        val background = image(item.text("fanart", 160), "fanart", "_mobile")
        val ratings = item["ratings"] as? JsonObject
        val imdbRating = ((ratings?.get("imdb") as? JsonObject)?.get("rating") as? JsonPrimitive)
            ?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..10.0 }?.toFloat()
        val released = item.text("release_date", 20)?.let { date ->
            runCatching { LocalDate.parse(date, DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT)).toString() }.getOrNull()
        }
        val preview = MetaPreview(id = id, type = contentType, rawType = contentType.toApiString(),
            name = title, poster = poster, posterShape = PosterShape.POSTER, background = background,
            logo = null, description = item.description(),
            releaseInfo = released?.take(4), imdbRating = imdbRating,
            genres = if (type == SimklFeedType.ANIME) (listOf("Anime") + genres).distinct() else genres,
            runtime = item.text("runtime", 80), status = item.text("status", 40),
            // The rich CDN contract does not supply a documented age certification. Never infer one.
            ageRating = null, language = item.text("original_language", 12), released = released,
            country = item.text("country", 12), imdbId = ids.text("imdb", 20)?.takeIf { imdb.matches(it) },
            slug = path, landscapePoster = image(item.text("poster", 160), "posters", "_w"),
            links = listOf(MetaLink("Simkl", "metadata", pageUrl)),
            sourceAddonBaseUrl = SimklPublicCatalog.BASE_URL)
        return SimklPublicItem(preview, simklId, pageUrl)
    }

    internal fun image(path: String?, kind: String, size: String): String? {
        if (path == null || !imagePath.matches(path) || kind !in setOf("posters", "fanart") ||
            size !in setOf("_m", "_w", "_mobile")) return null
        // The documented public image proxy receives only a fixed Simkl image host and validated path.
        return "https://wsrv.nl/".toHttpUrl().newBuilder()
            .addQueryParameter("url", "https://simkl.in/$kind/$path$size.webp")
            .addQueryParameter("q", "90").build().toString()
    }

    private fun JsonObject.description(): String? {
        val overview = text("overview", 8_000, allowNewlines = true) ?: return null
        // Parse an inert fragment only: text and entities are retained; markup never reaches UI.
        val document = Jsoup.parseBodyFragment(overview)
        document.select("script, style, template").remove()
        return document.text().safeText(8_000, allowNewlines = true)
    }

    private fun JsonObject.positiveId(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        val raw = value.contentOrNull ?: return null
        return raw.takeIf { digits.matches(it) }?.toLongOrNull()
    }

    private fun JsonObject.text(key: String, limit: Int, allowNewlines: Boolean = false): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.safeText(limit, allowNewlines)

    private fun String.safeText(limit: Int, allowNewlines: Boolean = false): String? = trim()
        .takeIf { it.isNotBlank() && it.length <= limit && it.none { c -> c.isISOControl() && !(allowNewlines && c in "\n\r\t") } }
}
