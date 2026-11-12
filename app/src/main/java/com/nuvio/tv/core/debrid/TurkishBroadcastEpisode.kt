package com.nuvio.tv.core.debrid

/** A broadcast ordinal corroborated by catalog and original-language episode metadata. */
class TurkishBroadcastEpisode internal constructor(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val number: Int,
    val originalTitle: String,
    val premiereYear: Int? = null,
) {
    internal val cacheKey: String
        get() = "$videoId:$season:$episode:$number:${premiereYear ?: 0}:$originalTitle"

    internal fun matchesContext(videoId: String?, season: Int?, episode: Int?, mediaType: String?): Boolean =
        mediaType != "movie" && this.videoId == videoId && this.season == season && this.episode == episode
}
