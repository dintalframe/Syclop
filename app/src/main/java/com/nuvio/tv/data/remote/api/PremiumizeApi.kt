package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.PremiumizeAccountInfoDto
import com.nuvio.tv.data.remote.dto.PremiumizeCacheCheckDto
import com.nuvio.tv.data.remote.dto.PremiumizeDeviceAuthorizationDto
import com.nuvio.tv.data.remote.dto.PremiumizeDeviceTokenDto
import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadDto
import com.nuvio.tv.data.remote.dto.PremiumizeItemDetailsDto
import com.nuvio.tv.data.remote.dto.PremiumizeItemListAllDto
import com.nuvio.tv.data.remote.dto.PremiumizeRootFolderDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface PremiumizeApi {
    @FormUrlEncoded
    @POST("token")
    suspend fun startDeviceAuthorization(
        @Field("response_type") responseType: String = "device_code",
        @Field("client_id") clientId: String
    ): Response<PremiumizeDeviceAuthorizationDto>

    @FormUrlEncoded
    @POST("token")
    suspend fun redeemDeviceAuthorization(
        @Field("grant_type") grantType: String = "device_code",
        @Field("code") deviceCode: String,
        @Field("client_id") clientId: String
    ): Response<PremiumizeDeviceTokenDto>

    @GET("api/account/info")
    suspend fun accountInfo(
        @Header("Authorization") authorization: String
    ): Response<PremiumizeAccountInfoDto>

    @GET("api/item/listall")
    suspend fun listAllItems(
        @Header("Authorization") authorization: String
    ): Response<PremiumizeItemListAllDto>

    @GET("api/item/details")
    suspend fun itemDetails(
        @Header("Authorization") authorization: String,
        @Query("id") itemId: String
    ): Response<PremiumizeItemDetailsDto>

    @GET("api/folder/list")
    suspend fun listRootFolder(
        @Header("Authorization") authorization: String
    ): Response<PremiumizeRootFolderDto>

    @FormUrlEncoded
    @POST("api/item/delete")
    suspend fun deleteItem(
        @Header("Authorization") authorization: String,
        @Field("id") itemId: String
    ): Response<Map<String, String>>

    @FormUrlEncoded
    @POST("api/cache/check")
    suspend fun checkCache(
        @Header("Authorization") authorization: String,
        @Field("items[]") items: List<String>
    ): Response<PremiumizeCacheCheckDto>

    @FormUrlEncoded
    @POST("api/transfer/directdl")
    suspend fun directDownload(
        @Header("Authorization") authorization: String,
        @Field("src") source: String
    ): Response<PremiumizeDirectDownloadDto>
}
