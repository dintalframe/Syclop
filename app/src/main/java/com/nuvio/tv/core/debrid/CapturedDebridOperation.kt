package com.nuvio.tv.core.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A resolver operation owns the initiating credential; it never borrows a later viewer's key. */
class CapturedDebridOperation internal constructor(
    private val providerId: String,
    private val apiKey: String,
    private val isCurrent: () -> Boolean
) {
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
