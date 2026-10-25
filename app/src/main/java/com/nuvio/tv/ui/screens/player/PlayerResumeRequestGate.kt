package com.nuvio.tv.ui.screens.player

/** Invalidates delayed resume reads after an explicit restart or a new playback request. */
internal class PlayerResumeRequestGate {
    private var generation = 0L

    fun beginRequest(): Long = ++generation

    fun invalidate() {
        generation++
    }

    fun isCurrent(request: Long): Boolean = request == generation

    /** Guard both a delayed read's start and its completion with the preparation's original token. */
    suspend fun <T> readIfCurrent(request: Long, read: suspend () -> T): T? {
        if (!isCurrent(request)) return null
        val result = read()
        return result.takeIf { isCurrent(request) }
    }
}
