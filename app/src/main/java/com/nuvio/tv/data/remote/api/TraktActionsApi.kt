package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.trakt.*
import retrofit2.Response
import retrofit2.http.*

/** Isolated transport: no request replay, redirects, cache, or credential logging. */
interface TraktActionsApi {
    @GET("users/me/ratings/{type}")
    suspend fun ratings(@Header("Authorization") auth: String, @Header("trakt-api-key") clientId: String,
        @Path("type") type: String, @Query("page") page: Int, @Query("limit") limit: Int): Response<List<TraktUserRatingDto>>
    @POST("sync/ratings")
    suspend fun rate(@Header("Authorization") auth: String, @Header("trakt-api-key") clientId: String,
        @Body body: TraktRatingRequestDto): Response<TraktRatingMutationDto>
    @POST("sync/ratings/remove")
    suspend fun removeRating(@Header("Authorization") auth: String, @Header("trakt-api-key") clientId: String,
        @Body body: TraktRatingRequestDto): Response<TraktRatingMutationDto>
    @GET("users/me/watching")
    suspend fun watching(@Header("Authorization") auth: String, @Header("trakt-api-key") clientId: String): Response<TraktWatchingDto>
    @POST("checkin")
    suspend fun checkin(@Header("Authorization") auth: String, @Header("trakt-api-key") clientId: String,
        @Body body: TraktCheckinRequestDto): Response<TraktCheckinResponseDto>
    @DELETE("checkin")
    suspend fun cancelCheckin(@Header("Authorization") auth: String, @Header("trakt-api-key") clientId: String): Response<Unit>
    @POST("https://auth.trakt.tv/oauth/token")
    suspend fun refreshToken(@Body body: TraktRefreshTokenRequestDto): Response<TraktTokenResponseDto>
}
