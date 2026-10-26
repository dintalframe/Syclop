package com.nuvio.tv.ui.screens.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-lifetime ownership and persistence ordering for progress of one captured video/profile. */
@Singleton
class PlayerProgressWriteAuthority @Inject constructor() {
    internal data class Foreground(val controllerId: Long, val activation: Long)
    internal data class Request(
        val owner: PlayerProgressWriteOwner,
        val controllerId: Long,
        val generation: Long
    )

    private class Entry(
        var controllerId: Long = 0L,
        var generation: Long = 0L,
        var completed: Boolean = false,
        val mutex: Mutex = Mutex()
    )

    // Entries contain media identity and primitive lease state, never a controller/account/URL.
    private val entries = mutableMapOf<PlayerProgressWriteOwner, Entry>()
    private var nextControllerId = 0L
    private var nextActivation = 0L
    private var foreground: Foreground? = null

    @Synchronized
    internal fun newControllerId(): Long = ++nextControllerId

    /** Called only by the actual PlayerScreen foreground lifecycle signal. */
    @Synchronized
    internal fun activateForeground(controllerId: Long): Foreground {
        foreground?.takeIf { it.controllerId == controllerId }?.let { return it }
        return Foreground(controllerId, ++nextActivation).also { foreground = it }
    }

    @Synchronized
    internal fun deactivateForeground(activation: Foreground) {
        if (foreground == activation) foreground = null
    }

    /** Preparation/restart may claim only while its screen owns the foreground activation. */
    @Synchronized
    internal fun claim(activation: Foreground, owner: PlayerProgressWriteOwner, newVisit: Boolean): Request? {
        if (foreground != activation) return null
        val entry = entries.getOrPut(owner) { Entry() }
        if (activation.controllerId != entry.controllerId || newVisit) {
            entry.controllerId = activation.controllerId
            entry.generation++
            entry.completed = false
        }
        return Request(owner, activation.controllerId, entry.generation)
    }

    /** A final flush may use an existing lease after pause; it can never claim a new one. */
    @Synchronized
    internal fun capture(lease: Request, completedAtCapture: Boolean): Request? {
        if (!isCurrent(lease)) return null
        if (completedAtCapture) entries.getValue(lease.owner).completed = true
        return lease
    }

    @Synchronized
    internal fun isCurrent(request: Request): Boolean = entries[request.owner]?.let {
        it.controllerId == request.controllerId && it.generation == request.generation
    } == true

    @Synchronized
    internal fun mayPublish(activation: Foreground?, request: Request): Boolean =
        activation != null && foreground == activation && activation.controllerId == request.controllerId && isCurrent(request)

    internal suspend fun writeIfCurrent(request: Request, completesVideo: Boolean, write: suspend () -> Unit) {
        val entry = synchronized(this) { entries[request.owner] } ?: return
        entry.mutex.withLock {
            val mayWrite = synchronized(this) { isCurrent(request) && !entry.completed }
            if (!mayWrite) return@withLock
            write()
            if (completesVideo) synchronized(this) {
                // A new screen/restart may claim while already-entered persistence awaits.
                if (isCurrent(request)) entry.completed = true
            }
        }
    }
}
