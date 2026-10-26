package com.nuvio.tv.core.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A resolver operation owns the initiating credential; it never borrows a later viewer's key. */
class CapturedDebridOperation private constructor(
    private val providerId: String,
    private val apiKey: String,
    private val isCurrent: () -> Boolean,
    internal val animeProof: com.nuvio.tv.core.anime.NativeAnimeEpisodeProof?
) {
    internal constructor(providerId: String, apiKey: String, isCurrent: () -> Boolean) : this(providerId, apiKey, isCurrent, null)

    internal fun withAnimeProof(proof: com.nuvio.tv.core.anime.NativeAnimeEpisodeProof?): CapturedDebridOperation =
        if (proof == null) this else CapturedDebridOperation(providerId, apiKey, isCurrent, proof)

    fun credential(expectedProviderId: String): String {
        checkCurrent()
        check(expectedProviderId == providerId) { "The streaming service changed." }
        return apiKey
    }

    private fun checkCurrent() {
        if (!isCurrent()) throw CancellationException("The active viewer or connected service changed.")
    }

    suspend fun <T> request(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkCurrent()
        val result = block()
        try {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            // A final service-local rejection is not a stale source/file selection. Let the
            // provider return its existing safe Error so another captured service can be tried.
            (result as? retrofit2.Response<*>)?.let { response ->
                if (response.code() in setOf(401, 403, 429)) {
                    runCatching { response.errorBody()?.close() }
                    runCatching { (response.body() as? java.io.Closeable)?.close() }
                    throw DirectDebridHttpException(response.code(), parseDebridRetryAfter(response.headers()["Retry-After"]))
                }
            }
            return result
        } catch (cancelled: CancellationException) {
            (result as? retrofit2.Response<*>)?.let { response ->
                runCatching { response.errorBody()?.close() }
                runCatching { (response.body() as? java.io.Closeable)?.close() }
            }
            throw cancelled
        }
    }

    override fun toString() = "CapturedDebridOperation(credentials=REDACTED)"
}

internal suspend fun <T> scopedDebridRequest(operation: CapturedDebridOperation?, block: suspend () -> T): T =
    if (operation == null) block() else operation.request(block)
