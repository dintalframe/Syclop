package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.AllDebridEnvelope
import com.nuvio.tv.data.remote.dto.AllDebridPinDto
import com.nuvio.tv.data.remote.dto.AllDebridPinCheckDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

interface AllDebridAuthApi {
    @GET("v4.1/pin/get")
    suspend fun getPin(): Response<AllDebridEnvelope<AllDebridPinDto>>

    @FormUrlEncoded @POST("v4/pin/check")
    suspend fun checkPin(@Field("pin") pin: String,
        @Field("check") check: String): Response<AllDebridEnvelope<AllDebridPinCheckDto>>
}
