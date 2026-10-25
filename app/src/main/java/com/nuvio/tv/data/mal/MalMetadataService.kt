package com.nuvio.tv.data.mal

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaBehaviorHints
import com.nuvio.tv.domain.model.MetaLink
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import javax.inject.Inject
import javax.inject.Singleton

/** Verified MAL record identity. MAL v2 supplies no IMDb mapping, so imdbId is intentionally null. */
data class MalSourceMetadata(
    val malId: Long,
    val title: String,
    val year: Int?,
    val mediaType: String?,
    val episodeCount: Int,
    val imdbId: String? = null,
    val alternativeTitles: List<String> = emptyList()
)

object MalIdentity {
    private val pattern = Regex("^mal:([1-9][0-9]*)(?::([1-9][0-9]*))?$")
    fun id(value: String): Long? = pattern.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
    fun episode(value: String): Int? = pattern.matchEntire(value)?.groupValues?.get(2)?.toIntOrNull()
    fun year(date: String?): Int? = date?.takeIf { it.matches(Regex("[0-9]{4}(-[0-9]{2})?(-[0-9]{2})?")) }
        ?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2200 }
}

@Singleton
class MalMetadataService @Inject constructor(
    private val auth: MalAuthRepository,
    private val api: MalApiClient
) {
    private data class Cached(val anime: MalAnime, val expires: Long)
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 64
    }

    suspend fun sourceMetadata(malId: Long): MalSourceMetadata = detail(malId).let {
        MalSourceMetadata(it.id, it.title, MalIdentity.year(it.startDate), it.mediaType, it.totalEpisodes,
            alternativeTitles = it.alternativeTitles)
    }

    suspend fun meta(id: String): Meta = toMeta(detail(requireNotNull(MalIdentity.id(id))))

    fun cachedMeta(id: String): Meta? {
        val malId = MalIdentity.id(id) ?: return null
        val key = "${auth.state.value.configuration.clientId}|$malId"
        return synchronized(cache) { cache[key]?.takeIf { it.expires > System.currentTimeMillis() }?.anime }
            ?.let(::toMeta)
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private suspend fun detail(id: Long): MalAnime {
        require(id > 0)
        val clientId = auth.state.value.configuration.clientId
        val key = "$clientId|$id"
        synchronized(cache) { cache[key]?.takeIf { it.expires > System.currentTimeMillis() }?.anime }?.let { return it }
        val result = api.publicDetail(id, clientId)
        require(result.id == id && result.title.isNotBlank()) { "MyAnimeList returned a different title identity." }
        require(auth.state.value.configuration.clientId == clientId) { "MyAnimeList application configuration changed." }
        synchronized(cache) { cache[key] = Cached(result, System.currentTimeMillis() + 10 * 60 * 1000L) }
        return result
    }

    companion object {
        private fun type(anime: MalAnime) = if (anime.mediaType == "movie") ContentType.MOVIE else ContentType.SERIES

        fun preview(anime: MalAnime): MetaPreview = MetaPreview(
            id = "mal:${anime.id}", type = type(anime), name = anime.title,
            poster = anime.pictureUrl, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = anime.synopsis,
            releaseInfo = MalIdentity.year(anime.startDate)?.toString(), imdbRating = null,
            genres = anime.genres, released = anime.startDate,
            sourceAddonBaseUrl = MalCatalog.BASE_URL,
            links = listOf(MetaLink("MyAnimeList", "metadata", "https://myanimelist.net/anime/${anime.id}"))
        )

        fun toMeta(anime: MalAnime): Meta {
            val id = "mal:${anime.id}"
            val movie = anime.mediaType == "movie"
            // Each MAL entry is its own season. No franchise-wide season arithmetic or invented episode dates.
            val videos = if (movie || anime.totalEpisodes <= 0) emptyList() else {
                (1..anime.totalEpisodes.coerceAtMost(3000)).map { episode -> Video(
                    id = "$id:$episode", title = "Episode $episode", released = null,
                    thumbnail = anime.pictureUrl, season = 1, episode = episode,
                    overview = null, runtime = anime.averageEpisodeSeconds?.takeIf { it > 0 }?.div(60)
                ) }
            }
            val note = when {
                !movie && anime.totalEpisodes <= 0 -> "Episode count is not yet available from MyAnimeList."
                !movie && anime.totalEpisodes > 3000 -> "Showing the first 3,000 episodes of this MyAnimeList entry."
                else -> null
            }
            return Meta(
                id = id, type = type(anime), name = anime.title, poster = anime.pictureUrl,
                posterShape = PosterShape.POSTER, background = null, logo = null,
                description = listOfNotNull(anime.synopsis, note).joinToString("\n\n").ifBlank { null },
                releaseInfo = MalIdentity.year(anime.startDate)?.toString(), imdbRating = null,
                genres = anime.genres, runtime = anime.averageEpisodeSeconds?.takeIf { it >= 60 }?.div(60)?.toString(),
                director = emptyList(), cast = emptyList(), videos = videos,
                country = null, awards = null, language = null,
                links = listOf(MetaLink("MyAnimeList", "metadata", "https://myanimelist.net/anime/${anime.id}")),
                released = anime.startDate,
                behaviorHints = MetaBehaviorHints(defaultVideoId = if (movie) id else videos.firstOrNull()?.id)
            )
        }
    }
}
