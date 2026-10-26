package com.nuvio.tv.core.debrid

import com.nuvio.tv.R
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class DirectDebridFailureReason {
    PROCESSING, AUTH_EXPIRED, RATE_LIMITED, TIMED_OUT, WRONG_EPISODE, NETWORK_FAILURE, UNAVAILABLE
}

/** Fixed classifications only: no remote message, URL, filename or credential is retained. */
data class DirectDebridFailure(
    val reason: DirectDebridFailureReason,
    val providerId: String? = null,
    val retryAfterSeconds: Long? = null
) {
    val messageRes: Int get() = when (reason) {
        DirectDebridFailureReason.PROCESSING -> R.string.syclop_resolver_processing
        DirectDebridFailureReason.AUTH_EXPIRED -> R.string.syclop_resolver_auth_expired
        DirectDebridFailureReason.RATE_LIMITED -> R.string.syclop_resolver_rate_limited
        DirectDebridFailureReason.TIMED_OUT -> R.string.syclop_resolver_timed_out
        DirectDebridFailureReason.WRONG_EPISODE -> R.string.syclop_resolver_wrong_episode
        DirectDebridFailureReason.NETWORK_FAILURE -> R.string.syclop_resolver_network_failure
        DirectDebridFailureReason.UNAVAILABLE -> R.string.syclop_resolver_unavailable
    }
}

internal class DirectDebridHttpException(val status: Int, val retryAfterSeconds: Long?) :
    IOException("The connected service rejected this request.")

internal fun directDebridHttpFailure(status: Int, providerId: String?, retryAfter: String? = null,
    retryAfterSeconds: Long? = null): DirectDebridResolveResult.Failure = DirectDebridResolveResult.Failure(
    DirectDebridFailure(when (status) {
        401, 403 -> DirectDebridFailureReason.AUTH_EXPIRED
        429 -> DirectDebridFailureReason.RATE_LIMITED
        in 500..599 -> DirectDebridFailureReason.NETWORK_FAILURE
        else -> DirectDebridFailureReason.UNAVAILABLE
    }, providerId, if (status == 429) retryAfterSeconds ?: parseDebridRetryAfter(retryAfter) ?: 60L else null))

internal fun directDebridExceptionFailure(error: Exception, providerId: String?): DirectDebridResolveResult.Failure = when (error) {
    is DirectDebridHttpException -> directDebridHttpFailure(error.status, providerId, retryAfterSeconds = error.retryAfterSeconds)
    is AllDebridApiException -> DirectDebridResolveResult.Failure(DirectDebridFailure(
        error.reason ?: when (error.httpStatus) {
            401, 403 -> DirectDebridFailureReason.AUTH_EXPIRED
            429 -> DirectDebridFailureReason.RATE_LIMITED
            else -> DirectDebridFailureReason.NETWORK_FAILURE
        }, providerId, error.retryAfterSeconds ?: if (error.httpStatus == 429) 60L else null))
    is retrofit2.HttpException -> directDebridHttpFailure(error.code(), providerId, error.response()?.headers()?.get("Retry-After"))
    is java.io.InterruptedIOException -> DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.TIMED_OUT, providerId))
    else -> DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.NETWORK_FAILURE, providerId))
}

internal fun wrongDebridFile(providerId: String?): DirectDebridResolveResult.Failure =
    DirectDebridResolveResult.Failure(DirectDebridFailure(
        DirectDebridFailureReason.WRONG_EPISODE, providerId))

/** Supports both delta-seconds and the HTTP-date form without retaining the header. */
internal fun parseDebridRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim()?.takeIf { it.length in 1..128 } ?: return null
    if (text.all(Char::isDigit)) return text.toLongOrNull()?.coerceAtLeast(1L)
    val position = ParsePosition(0)
    val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        isLenient = false; timeZone = TimeZone.getTimeZone("GMT")
    }.parse(text, position) ?: return null
    if (position.index != text.length) return null
    val remaining = (date.time - nowMillis).coerceAtLeast(1L)
    return (remaining - 1L) / 1_000L + 1L
}
