package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.RealDebridDeviceCodeDto
import com.nuvio.tv.data.remote.dto.RealDebridDeviceCredentialsDto
import com.nuvio.tv.data.remote.dto.RealDebridOAuthTokenDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Official open-source device flow. No application-wide client secret is shipped. */
interface RealDebridOAuthApi {
    @GET("device/code")
    suspend fun deviceCode(@Query("client_id") clientId: String,
        @Query("new_credentials") newCredentials: String = "yes"): Response<RealDebridDeviceCodeDto>

    @GET("device/credentials")
    suspend fun deviceCredentials(@Query("client_id") clientId: String,
        @Query("code") deviceCode: String): Response<RealDebridDeviceCredentialsDto>

    @FormUrlEncoded @POST("token")
    suspend fun token(@Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String = "http://oauth.net/grant_type/device/1.0"): Response<RealDebridOAuthTokenDto>
}
