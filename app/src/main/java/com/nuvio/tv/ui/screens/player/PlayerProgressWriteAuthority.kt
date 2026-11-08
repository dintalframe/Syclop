package com.nuvio.tv.ui.screens.player

import android.content.Context
import com.nuvio.tv.core.player.AndroidPlaybackProgressLeaseCheckpoint
import com.nuvio.tv.core.player.MemoryPlaybackProgressLeaseCheckpoint
import com.nuvio.tv.core.player.PlaybackProgressLeaseCheckpoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-lifetime ownership and persistence ordering for progress of one captured video/profile. */
@Singleton
class PlayerProgressWriteAuthority internal constructor(private val checkpoint: PlaybackProgressLeaseCheckpoint) {
    @Inject constructor(@ApplicationContext context: Context) : this(AndroidPlaybackProgressLeaseCheckpoint(context))
    constructor() : this(MemoryPlaybackProgressLeaseCheckpoint())

    internal data class Foreground(val controllerId: Long, val activation: Long)
    internal data class Request(
        val owner: PlayerProgressWriteOwner,
        val controllerId: Long,
        val generation: Long,
        val nonce: String
    )
    internal data class ExternalReservation(val owner: PlayerProgressWriteOwner, val nonce: String, val baseline: String?)

    private class Entry(
        var controllerId: Long = 0L,
        var generation: Long = 0L,
        var nonce: String = "",
        var completed: Boolean = false,
        var writes: Int = 0,
        var touched: Long = 0L,
        val mutex: Mutex = Mutex()
    )

    // Entries contain media identity and primitive lease state, never a controller/account/URL.
    private val entries = mutableMapOf<PlayerProgressWriteOwner, Entry>()
    private var nextControllerId = 0L
    private var nextActivation = 0L
    private var nextTouch = 0L
    private var foreground: Foreground? = null
    private var foregroundOwner: PlayerProgressWriteOwner? = null

    @Synchronized internal fun newControllerId(): Long = ++nextControllerId

    /** Called only by the actual PlayerScreen foreground lifecycle signal. */
    @Synchronized internal fun activateForeground(controllerId: Long): Foreground {
        foreground?.takeIf { it.controllerId == controllerId }?.let { return it }
        foregroundOwner = null
        return Foreground(controllerId, ++nextActivation).also { foreground = it }
    }

    @Synchronized internal fun deactivateForeground(activation: Foreground) {
        if (foreground == activation) { foreground = null; foregroundOwner = null }
    }

    /** Preparation/restart may claim only while its screen owns the foreground activation. */
    @Synchronized internal fun claim(activation: Foreground, owner: PlayerProgressWriteOwner, newVisit: Boolean): Request? {
        if (foreground != activation) return null
        val entry = entries.getOrPut(owner) { Entry() }
        if (activation.controllerId != entry.controllerId || newVisit) {
            entry.controllerId = activation.controllerId
            entry.generation++
            entry.nonce = UUID.randomUUID().toString()
            entry.completed = false
        }
        foregroundOwner = owner
        touch(entry)
        prune()
        return request(owner, entry)
    }

    /** Prepared before Android launch; it does not retire any existing internal playback. */
    internal suspend fun prepareExternal(owner: PlayerProgressWriteOwner): ExternalReservation {
        val entry = synchronized(this) { entries.getOrPut(owner) { Entry() }.also { it.writes++ } }
        try {
            return entry.mutex.withLock {
                val key = storageKey(owner)
                // A real baseline nonce distinguishes a never-written owner from an evicted checkpoint.
                val baseline = checkpoint.read(key) ?: UUID.randomUUID().toString().also { checkpoint.write(key, it) }
                ExternalReservation(owner, UUID.randomUUID().toString(), baseline)
            }
        } finally { synchronized(this) { entry.writes--; touch(entry); prune() } }
    }

    /** Only call after Android accepted the external intent (or for an already accepted handoff). */
    @Synchronized internal fun acceptExternal(reservation: ExternalReservation): Request {
        val entry = entries.getOrPut(reservation.owner) { Entry() }
        entry.controllerId = ++nextControllerId
        entry.generation++
        entry.nonce = reservation.nonce
        entry.completed = false
        touch(entry)
        prune()
        return request(reservation.owner, entry)
    }

    /** Recovery never invents a new owner over a newer in-process or persisted playback. */
    internal suspend fun recoverExternal(reservation: ExternalReservation): Request? {
        synchronized(this) {
            entries[reservation.owner]?.let { entry ->
                return if (entry.nonce == reservation.nonce) request(reservation.owner, entry) else null
            }
        }
        val saved = checkpoint.read(storageKey(reservation.owner))
        if (saved == null || (saved != reservation.nonce && saved != reservation.baseline)) return null
        return synchronized(this) {
            val current = entries[reservation.owner]
            when {
                current != null -> if (current.nonce == reservation.nonce) request(reservation.owner, current) else null
                else -> acceptExternal(reservation)
            }
        }
    }

    /** Record accepted ownership asynchronously, serialized with all progress commits for this owner. */
    internal suspend fun checkpointIfCurrent(request: Request) = writeIfCurrent(request, false) {}

    /** A final flush may use an existing lease after pause; it can never claim a new one. */
    @Synchronized internal fun capture(lease: Request, completedAtCapture: Boolean): Request? {
        if (!isCurrent(lease)) return null
        val entry = entries.getValue(lease.owner)
        if (completedAtCapture) entry.completed = true
        touch(entry)
        return lease
    }

    @Synchronized internal fun isCurrent(request: Request): Boolean = entries[request.owner]?.let {
        it.controllerId == request.controllerId && it.generation == request.generation && it.nonce == request.nonce
    } == true

    @Synchronized internal fun mayPublish(activation: Foreground?, request: Request): Boolean =
        activation != null && foreground == activation && activation.controllerId == request.controllerId && isCurrent(request)

    internal suspend fun writeIfCurrent(request: Request, completesVideo: Boolean, write: suspend () -> Unit) {
        val entry = synchronized(this) { entries[request.owner]?.also { it.writes++ } } ?: return
        try {
            entry.mutex.withLock {
                val mayWrite = synchronized(this) { isCurrent(request) && !entry.completed }
                if (!mayWrite) return@withLock
                // This IO checkpoint always precedes a repository mutation, including a rewatch.
                checkpoint.write(storageKey(request.owner), request.nonce)
                if (!synchronized(this) { isCurrent(request) && !entry.completed }) return@withLock
                write()
                if (completesVideo) synchronized(this) {
                    if (isCurrent(request)) entry.completed = true
                }
            }
        } finally { synchronized(this) { entry.writes--; touch(entry); prune() } }
    }

    private fun request(owner: PlayerProgressWriteOwner, entry: Entry) = Request(owner, entry.controllerId, entry.generation, entry.nonce)
    private fun touch(entry: Entry) { entry.touched = ++nextTouch }
    private fun prune() {
        while (entries.size > 512) {
            val oldest = entries.entries.filter { it.value.writes == 0 && it.key != foregroundOwner }
                .minByOrNull { it.value.touched } ?: return
            entries.remove(oldest.key)
        }
    }
    private fun storageKey(owner: PlayerProgressWriteOwner): String {
        val identity = listOf(owner.profileId.toString(), owner.contentId, owner.contentType,
            owner.videoId, owner.season?.toString().orEmpty(), owner.episode?.toString().orEmpty())
            .joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
