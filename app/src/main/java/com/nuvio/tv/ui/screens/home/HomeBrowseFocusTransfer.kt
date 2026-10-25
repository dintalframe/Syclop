package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.withTimeoutOrNull

/** A request succeeding is not sufficient: confirm that the intended lazy child gained focus. */
internal object HomeBrowseFocusTransfer {
    enum class Result { FOCUSED, STALE, UNAVAILABLE }

    fun cardIndex(remembered: Int?, itemCount: Int): Int =
        (remembered ?: 0).coerceIn(0, (itemCount - 1).coerceAtLeast(0))

    suspend fun transfer(
        isCurrent: () -> Boolean,
        prepare: suspend () -> Unit,
        awaitFrame: suspend () -> Unit,
        ready: () -> Boolean,
        request: () -> Boolean,
        hasFocus: () -> Boolean
    ): Result = withTimeoutOrNull(1_000L) {
        if (!isCurrent()) return@withTimeoutOrNull Result.STALE
        prepare()
        repeat(12) {
            awaitFrame()
            if (!isCurrent()) return@withTimeoutOrNull Result.STALE
            if (ready()) {
                // Lazy layout can still detach between ready() and the request in this frame.
                val accepted = try { request() } catch (_: IllegalStateException) { false }
                if (accepted) {
                    awaitFrame()
                    if (!isCurrent()) return@withTimeoutOrNull Result.STALE
                    if (hasFocus()) return@withTimeoutOrNull Result.FOCUSED
                }
            }
        }
        Result.UNAVAILABLE
    } ?: Result.UNAVAILABLE
}
