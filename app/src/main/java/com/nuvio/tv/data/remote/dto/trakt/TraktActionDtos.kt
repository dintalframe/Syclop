package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktRatedMediaDto(val ids: TraktIdsDto, val rating: Int? = null)
@JsonClass(generateAdapter = true)
data class TraktRatingRequestDto(val movies: List<TraktRatedMediaDto>? = null,
    val shows: List<TraktRatedMediaDto>? = null, val episodes: List<TraktRatedMediaDto>? = null)
@JsonClass(generateAdapter = true)
data class TraktActionCountsDto(val movies: Int = 0, val shows: Int = 0, val seasons: Int = 0, val episodes: Int = 0)
@JsonClass(generateAdapter = true)
data class TraktRatingMutationDto(val added: TraktActionCountsDto? = null,
    val updated: TraktActionCountsDto? = null, val deleted: TraktActionCountsDto? = null,
    @Json(name = "not_found") val notFound: TraktRatingRequestDto? = null)
@JsonClass(generateAdapter = true)
data class TraktUserRatingDto(val rating: Int, val type: String,
    val movie: TraktMovieDto? = null, val show: TraktShowDto? = null, val episode: TraktEpisodeDto? = null)

/** Explicit false prevents inheriting account social-network sharing defaults. */
@JsonClass(generateAdapter = true)
data class TraktCheckinSharingDto(val twitter: Boolean = false, val mastodon: Boolean = false, val tumblr: Boolean = false)
@JsonClass(generateAdapter = true)
data class TraktCheckinMediaDto(val ids: TraktIdsDto)
@JsonClass(generateAdapter = true)
data class TraktCheckinRequestDto(val movie: TraktCheckinMediaDto? = null,
    val episode: TraktCheckinMediaDto? = null, val sharing: TraktCheckinSharingDto = TraktCheckinSharingDto())
@JsonClass(generateAdapter = true)
data class TraktCheckinResponseDto(val id: Long, val movie: TraktMovieDto? = null,
    val episode: TraktEpisodeDto? = null, val show: TraktShowDto? = null)
@JsonClass(generateAdapter = true)
data class TraktCheckinConflictDto(@Json(name = "expires_at") val expiresAt: String? = null)
@JsonClass(generateAdapter = true)
data class TraktWatchingDto(val action: String, val type: String,
    @Json(name = "expires_at") val expiresAt: String,
    val movie: TraktMovieDto? = null, val episode: TraktEpisodeDto? = null, val show: TraktShowDto? = null)
