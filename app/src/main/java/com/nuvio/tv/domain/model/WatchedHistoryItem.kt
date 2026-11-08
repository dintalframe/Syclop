package com.nuvio.tv.domain.model

/** One actual completed movie/episode observation; it never represents an unfinished resume position. */
data class WatchedHistoryItem(
    val contentId: String,
    val contentType: String,
    val title: String,
    val watchedAt: Long,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val genres: List<String> = emptyList(),
    val ageRating: String? = null,
    val country: String? = null,
    val languages: List<String> = emptyList(),
    val imdbId: String? = null,
    val releaseInfo: String? = null,
    val source: String = "trakt",
    val sourceAddonBaseUrl: String? = null
) {
    val certification: String? get() = ageRating
}
