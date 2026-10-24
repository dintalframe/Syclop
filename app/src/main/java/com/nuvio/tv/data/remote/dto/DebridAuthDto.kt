package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RealDebridDeviceCodeDto(
    @Json(name="device_code") val deviceCode: String? = null,
    @Json(name="user_code") val userCode: String? = null,
    val interval: Int? = null,
    @Json(name="expires_in") val expiresIn: Int? = null,
    @Json(name="verification_url") val verificationUrl: String? = null
) { override fun toString() = "RealDebridDeviceCodeDto([redacted])" }

@JsonClass(generateAdapter = true)
data class RealDebridDeviceCredentialsDto(
    @Json(name="client_id") val clientId: String? = null,
    @Json(name="client_secret") val clientSecret: String? = null
) { override fun toString() = "RealDebridDeviceCredentialsDto([redacted])" }

@JsonClass(generateAdapter = true)
data class RealDebridOAuthTokenDto(
    @Json(name="access_token") val accessToken: String? = null,
    @Json(name="refresh_token") val refreshToken: String? = null,
    @Json(name="expires_in") val expiresIn: Int? = null,
    @Json(name="token_type") val tokenType: String? = null
) { override fun toString() = "RealDebridOAuthTokenDto([redacted])" }

@JsonClass(generateAdapter = true)
data class AllDebridPinDto(val pin: String? = null, val check: String? = null,
    @Json(name="expires_in") val expiresIn: Int? = null,
    @Json(name="user_url") val userUrl: String? = null,
    @Json(name="base_url") val baseUrl: String? = null
) { override fun toString() = "AllDebridPinDto([redacted])" }

@JsonClass(generateAdapter = true)
data class AllDebridPinCheckDto(val activated: Boolean? = null, val apikey: String? = null,
    @Json(name="expires_in") val expiresIn: Int? = null
) { override fun toString() = "AllDebridPinCheckDto([redacted])" }
