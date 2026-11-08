package com.nuvio.tv.ui.screens.player.autosync

import kotlinx.coroutines.delay

/** A shared finite idle budget for addon results that arrive after current candidates fail. */
internal class AutoSyncLateCandidateWait(
    private var remainingMs: Long = 10_000L,
    private val pollMs: Long = 250L
) {
    init { require(remainingMs >= 0L && pollMs > 0L) }

    suspend fun awaitNext(available: () -> Boolean): Boolean {
        if (available()) return true
        while (remainingMs > 0L) {
            val slice = minOf(pollMs, remainingMs)
            delay(slice)
            remainingMs -= slice
            if (available()) return true
        }
        return false
    }
}
