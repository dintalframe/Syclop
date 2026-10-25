package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AllDebridEnvelope<T>(
    val status: String? = null,
    val data: T? = null,
    val error: AllDebridErrorDto? = null
) {
    val successful: Boolean get() = status == "success" && data != null
}

@JsonClass(generateAdapter = true)
data class AllDebridErrorDto(val code: String? = null)

@JsonClass(generateAdapter = true)
data class AllDebridUserDataDto(val user: AllDebridUserDto? = null)

@JsonClass(generateAdapter = true)
data class AllDebridUserDto(
    val username: String? = null,
    val isPremium: Boolean = false,
    val isTrial: Boolean = false
)

data class AllDebridMagnetsDataDto(val magnets: List<AllDebridMagnetDto> = emptyList())

@JsonClass(generateAdapter = true)
data class AllDebridMagnetDto(
    val id: Long? = null,
    val filename: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val hash: String? = null,
    val ready: Boolean? = null,
    val status: String? = null,
    val statusCode: Int? = null,
    val downloaded: Long? = null,
    val files: List<AllDebridFileNodeDto> = emptyList(),
    val error: AllDebridErrorDto? = null
)

/** v4/magnet/files is a recursive tree, not the deprecated v4 status.links list. */
@JsonClass(generateAdapter = true)
data class AllDebridFileNodeDto(
    @Json(name = "n") val name: String = "",
    @Json(name = "s") val size: Long? = null,
    @Json(name = "l") val link: String? = null,
    @Json(name = "e") val children: List<AllDebridFileNodeDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AllDebridLinkDto(
    val link: String? = null,
    val filename: String? = null,
    val filesize: Long? = null,
    val id: String? = null,
    val delayed: Long? = null,
    val status: Int? = null,
    val streams: List<AllDebridStreamDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AllDebridStreamDto(val id: String? = null, val quality: String? = null)
