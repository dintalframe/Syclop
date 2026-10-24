package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.AllDebridEnvelope
import com.nuvio.tv.data.remote.dto.AllDebridLinkDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetsDataDto
import com.nuvio.tv.data.remote.dto.AllDebridUserDataDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** Official v4 API. Authentication is always a header, never part of a URL. */
interface AllDebridApi {
    @GET("v4/user")
    suspend fun getUser(@Header("Authorization") authorization: String): Response<AllDebridEnvelope<AllDebridUserDataDto>>

    @FormUrlEncoded
    @POST("v4/link/unlock")
    suspend fun unlockLink(@Header("Authorization") authorization: String, @Field("link") link: String): Response<AllDebridEnvelope<AllDebridLinkDto>>

    @FormUrlEncoded
    @POST("v4/link/streaming")
    suspend fun streamingLink(@Header("Authorization") authorization: String, @Field("id") id: String, @Field("stream") stream: String): Response<AllDebridEnvelope<AllDebridLinkDto>>

    @FormUrlEncoded
    @POST("v4/link/delayed")
    suspend fun delayedLink(@Header("Authorization") authorization: String, @Field("id") id: Long): Response<AllDebridEnvelope<AllDebridLinkDto>>

    @FormUrlEncoded
    @POST("v4/magnet/upload")
    suspend fun uploadMagnet(@Header("Authorization") authorization: String, @Field("magnets[]") magnets: List<String>): Response<AllDebridEnvelope<AllDebridMagnetsDataDto>>

    @FormUrlEncoded
    @POST("v4.1/magnet/status")
    suspend fun magnetStatus(@Header("Authorization") authorization: String, @Field("id") id: Long? = null): Response<AllDebridEnvelope<AllDebridMagnetsDataDto>>

    @FormUrlEncoded
    @POST("v4/magnet/files")
    suspend fun magnetFiles(@Header("Authorization") authorization: String, @Field("id[]") ids: List<Long>): Response<AllDebridEnvelope<AllDebridMagnetsDataDto>>
}
