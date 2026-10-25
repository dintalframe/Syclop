package com.nuvio.tv.data.catalog

import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaLink
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only explicit certification and genre/subgenre facts can admit or group a Kids record. */
internal object RatedKidsCatalogCodec {
    private val imdb = Regex("tt[0-9]{5,12}")
    private val televisionRatings = setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G")
    private val mature = setOf("adult", "mature", "hentai", "ecchi", "erotica", "pornography", "pornographic")
    private val education = setOf("education", "educational", "learning")
    private val science = setOf("science", "engineering", "technology", "science-and-technology")
    private val nature = setOf("nature", "wildlife", "natural-history", "nature-documentary", "animal-documentary")

    fun movie(value: TraktMovieDto, descriptor: RatedKidsDescriptor): MetaPreview? = preview(
        descriptor, value.ids?.imdb, value.ids?.trakt, value.title, value.certification,
        value.genres, value.subgenres, value.images, value.overview, value.year, value.released,
        value.runtime, value.status, value.languages?.firstOrNull(), value.country)

    fun show(value: TraktShowDto, descriptor: RatedKidsDescriptor): MetaPreview? = preview(
        descriptor, value.ids?.imdb, value.ids?.trakt, value.title, value.certification,
        value.genres, value.subgenres, value.images, value.overview, value.year, value.firstAired,
        value.runtime, value.status, value.languages?.firstOrNull(), value.country)

    private fun preview(descriptor: RatedKidsDescriptor, id: String?, traktId: Int?, title: String?, certificate: String?,
        genres: List<String>?, subgenres: List<String>?, images: TraktImagesDto?, overview: String?, year: Int?,
        released: String?, runtime: Int?, status: String?, language: String?, country: String?): MetaPreview? {
        if (id == null || !imdb.matches(id)) return null
        val name = title.safeText(300) ?: return null
        val rating = certificate?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (descriptor.type == ContentType.MOVIE && rating != "G" ||
            descriptor.type != ContentType.MOVIE && rating !in televisionRatings) return null
        if (genres.orEmpty().size > 40 || subgenres.orEmpty().size > 80) return null
        val rawGenres = genres.orEmpty().mapNotNull { it.safeText(80) }
        val rawSubgenres = subgenres.orEmpty().mapNotNull { it.safeText(80) }
        val tags = (rawGenres + rawSubgenres).mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        if (tags.any { it in mature }) return null
        val documentary = "documentary" in tags || "documentaries" in tags || "docuseries" in tags
        val educational = tags.any { it in education }
        val scientific = (documentary || educational) && tags.any { it in science }
        val natural = tags.any { it in nature } || documentary && "animals" in tags
        when (descriptor.topic) {
            RatedKidsTopic.FAMILY -> {
                if (tags.none { it == "family" || it == "children" }) return null
            }
            RatedKidsTopic.SCIENCE -> if (!scientific) return null
            RatedKidsTopic.NATURE -> if (!natural) return null
            RatedKidsTopic.EDUCATION -> if (!educational) return null
            RatedKidsTopic.DOCUMENTARY -> if (!documentary) return null
        }
        val markers = buildList {
            if (scientific) add("Science")
            if (natural) add("Nature")
            if (educational) add("Educational")
            if (documentary) add("Documentary")
        }
        val poster = image(images?.poster)
        val background = image(images?.fanart) ?: image(images?.thumb) ?: image(images?.banner) ?: poster
        val links = traktId?.takeIf { it > 0 }?.let {
            listOf(MetaLink("Trakt", "metadata", "https://trakt.tv/${if (descriptor.type == ContentType.MOVIE) "movies" else "shows"}/$it"))
        }.orEmpty()
        return MetaPreview(id = id, type = descriptor.type, name = name, poster = poster,
            posterShape = PosterShape.POSTER, background = background, logo = image(images?.logo),
            description = overview.safeText(8_000, allowNewlines = true),
            releaseInfo = year?.takeIf { it in 1000..9999 }?.toString(), imdbRating = null,
            genres = (markers + rawGenres + rawSubgenres).distinct(),
            runtime = runtime?.takeIf { it in 1..1_440 }?.let { "$it min" }, status = status.safeText(80),
            ageRating = certificate, language = language.safeText(16), released = released.safeText(80),
            country = country.safeText(16), imdbId = id, landscapePoster = background,
            rawPosterUrl = poster, links = links, sourceAddonBaseUrl = RatedKidsCatalog.BASE_URL)
    }

    /** Official public Trakt artwork only; no account URL, arbitrary host or token-bearing query. */
    private fun image(values: List<String>?): String? = values.orEmpty().take(8).firstNotNullOfOrNull { value ->
        val raw = value.safeText(2_048) ?: return@firstNotNullOfOrNull null
        val url = (if (raw.startsWith("media.trakt.tv/")) "https://$raw" else raw).toHttpUrlOrNull()
            ?: return@firstNotNullOfOrNull null
        if (!url.isHttps || url.host != "media.trakt.tv" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) null else url.toString()
    }

    private fun String?.safeText(limit: Int, allowNewlines: Boolean = false): String? = this?.trim()?.takeIf {
        it.isNotBlank() && it.length <= limit && it.none { char -> char.isISOControl() && !(allowNewlines && char in "\n\r\t") }
    }
}
