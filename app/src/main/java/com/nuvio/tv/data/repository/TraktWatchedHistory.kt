package com.nuvio.tv.data.repository

import com.nuvio.tv.core.trakt.traktBestBackdropUrl
import com.nuvio.tv.core.trakt.traktBestLogoUrl
import com.nuvio.tv.core.trakt.traktBestPosterUrl
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.domain.model.WatchedHistoryItem
import java.time.Instant

internal const val RECENT_WATCHED_HISTORY_LIMIT = 200

/** Keep the newest real completion per movie or exact episode, with no synthetic timestamps/IDs. */
internal fun recentTraktWatchedHistory(
    observations: List<TraktHistoryItemDto>,
    limit: Int = RECENT_WATCHED_HISTORY_LIMIT
): List<WatchedHistoryItem> = observations.mapNotNull { row ->
    val watchedAt = row.watchedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?.takeIf { it > 0L } ?: return@mapNotNull null
    fun TraktIdsDto?.canonicalId(): String? = this?.imdb?.takeIf { it.matches(Regex("tt[0-9]+")) }
        ?: this?.tmdb?.takeIf { it > 0 }?.let { "tmdb:$it" }
    val movie = row.movie
    if (movie != null) {
        val id = movie.ids.canonicalId() ?: return@mapNotNull null
        val title = movie.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        WatchedHistoryItem(id, "movie", title, watchedAt, id,
            poster = movie.images.traktBestPosterUrl(), backdrop = movie.images.traktBestBackdropUrl(),
            logo = movie.images.traktBestLogoUrl(), genres = (movie.genres.orEmpty() + movie.subgenres.orEmpty()).distinct(), ageRating = movie.certification,
            country = movie.country, languages = movie.languages.orEmpty(), imdbId = movie.ids?.imdb,
            releaseInfo = movie.year?.toString())
    } else {
        val show = row.show ?: return@mapNotNull null
        val episode = row.episode ?: return@mapNotNull null
        val id = show.ids.canonicalId() ?: return@mapNotNull null
        val season = episode.season?.takeIf { it >= 0 } ?: return@mapNotNull null
        val number = episode.number?.takeIf { it > 0 } ?: return@mapNotNull null
        val title = show.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        WatchedHistoryItem(id, "series", title, watchedAt, "$id:$season:$number",
            season = season, episode = number, episodeTitle = episode.title,
            poster = show.images.traktBestPosterUrl(), backdrop = show.images.traktBestBackdropUrl(),
            logo = show.images.traktBestLogoUrl(), genres = (show.genres.orEmpty() + show.subgenres.orEmpty()).distinct(), ageRating = show.certification,
            country = show.country, languages = show.languages.orEmpty(), imdbId = show.ids?.imdb,
            releaseInfo = show.year?.toString())
    }
}.sortedByDescending { it.watchedAt }
    .distinctBy { Triple(it.contentId, it.season, it.episode) }
    .take(limit.coerceIn(0, RECENT_WATCHED_HISTORY_LIMIT))
