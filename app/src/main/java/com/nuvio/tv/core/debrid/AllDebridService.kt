package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridEnvelope
import com.nuvio.tv.data.remote.dto.AllDebridFileNodeDto
import com.nuvio.tv.data.remote.dto.AllDebridLinkDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto
import com.nuvio.tv.data.remote.dto.AllDebridUserDto
import kotlinx.coroutines.delay
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AllDebridFile(val path: String, val size: Long?, val link: String) {
    override fun toString(): String = "AllDebridFile(path=$path, link=[redacted])"
}

/** API error text is deliberately not surfaced: servers can echo input links and credentials. */
class AllDebridApiException(message: String, val httpStatus: Int? = null,
    val reason: DirectDebridFailureReason? = null, val retryAfterSeconds: Long? = null) : IOException(message)

@Singleton
class AllDebridService @Inject constructor(private val api: AllDebridApi) {
    suspend fun user(apiKey: String): AllDebridUserDto =
        api.getUser(bearer(apiKey)).dataOrThrow().user
            ?.takeIf { !it.username.isNullOrBlank() }
            ?: throw AllDebridApiException("AllDebrid returned an invalid account response.")

    suspend fun upload(apiKey: String, magnet: String): AllDebridMagnetDto {
        val item = api.uploadMagnet(bearer(apiKey), listOf(magnet)).dataOrThrow().magnets.singleOrNull()
            ?: throw AllDebridApiException("AllDebrid could not add this magnet.")
        if (item.error != null || item.id == null) throw AllDebridApiException("AllDebrid could not add this magnet.")
        return item
    }

    suspend fun status(apiKey: String, id: Long? = null): List<AllDebridMagnetDto> =
        api.magnetStatus(bearer(apiKey), id).dataOrThrow().magnets

    suspend fun files(apiKey: String, ids: List<Long>): Map<Long, List<AllDebridFile>> {
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(100).flatMap { batch ->
            api.magnetFiles(bearer(apiKey), batch).dataOrThrow().magnets
        }.mapNotNull { item -> item.id?.let { it to flattenAllDebridFiles(item.files) } }.toMap()
    }

    suspend fun unlock(apiKey: String, source: String): AllDebridLinkDto {
        val authorization = bearer(apiKey)
        var result = api.unlockLink(authorization, source).dataOrThrow()
        if (result.link.isNullOrBlank() && result.streams.isNotEmpty()) {
            // Prefer the highest numeric video quality; avoid choosing an audio-only entry.
            val stream = result.streams.filter { it.id != null && it.quality?.toIntOrNull() != null }
                .maxByOrNull { it.quality?.toIntOrNull() ?: 0 }
                ?: throw AllDebridApiException("Choose a supported video source.")
            val id = result.id ?: throw AllDebridApiException("AllDebrid returned an incomplete video response.")
            result = api.streamingLink(authorization, id, stream.id!!).dataOrThrow()
        }
        val delayedId = result.delayed
        if (result.link.isNullOrBlank() && delayedId != null) {
            // Official minimum polling interval is five seconds. Bounded and cancellable.
            repeat(6) {
                delay(5_000)
                val delayed = api.delayedLink(authorization, delayedId).dataOrThrow()
                if (delayed.link.isHttpUrl()) return result.copy(link = delayed.link)
                if (delayed.status == 3) throw AllDebridApiException("AllDebrid could not prepare this link.")
            }
            throw AllDebridApiException("This link is still being prepared. Try again shortly.", reason = DirectDebridFailureReason.PROCESSING)
        }
        if (!result.link.isHttpUrl()) throw AllDebridApiException("AllDebrid did not return a playable link.")
        return result
    }

    private fun bearer(apiKey: String) = "Bearer ${apiKey.trim()}"
}

internal fun flattenAllDebridFiles(nodes: List<AllDebridFileNodeDto>, parent: String = ""): List<AllDebridFile> =
    nodes.flatMap { node ->
        val path = listOf(parent, node.name).filter { it.isNotBlank() }.joinToString("/")
        if (node.children.isNotEmpty()) flattenAllDebridFiles(node.children, path)
        else node.link?.takeIf { it.isHttpUrl() }?.let { listOf(AllDebridFile(path, node.size, it)) }.orEmpty()
    }

internal fun <T> Response<AllDebridEnvelope<T>>.dataOrThrow(): T {
    val envelope = body()
    if (isSuccessful && envelope?.successful == true) return envelope.data!!
    errorBody()?.close()
    val message = when {
        code() == 429 -> "AllDebrid is rate limiting requests. Try again shortly."
        envelope?.error?.code in setOf("AUTH_BAD_APIKEY", "AUTH_MISSING_APIKEY") -> "AllDebrid API key is invalid."
        envelope?.error?.code == "AUTH_BLOCKED" -> "AllDebrid blocked this connection. Check the account security settings."
        envelope?.error?.code in setOf("MUST_BE_PREMIUM", "MAGNET_MUST_BE_PREMIUM") -> "An active AllDebrid subscription is required."
        else -> "AllDebrid request failed. Check the service and network connection."
    }
    val reason = when {
        code() == 429 -> DirectDebridFailureReason.RATE_LIMITED
        code() in setOf(401, 403) || envelope?.error?.code in setOf("AUTH_BAD_APIKEY", "AUTH_MISSING_APIKEY",
            "AUTH_BLOCKED", "MUST_BE_PREMIUM", "MAGNET_MUST_BE_PREMIUM") -> DirectDebridFailureReason.AUTH_EXPIRED
        else -> DirectDebridFailureReason.UNAVAILABLE
    }
    throw AllDebridApiException(message, code().takeIf { it in 100..599 }, reason,
        if (reason == DirectDebridFailureReason.RATE_LIMITED) parseDebridRetryAfter(headers()["Retry-After"]) ?: 60L else null)
}

internal fun String?.isHttpUrl(): Boolean =
    this?.startsWith("https://", ignoreCase = true) == true || this?.startsWith("http://", ignoreCase = true) == true
