package com.nuvio.tv.core.player

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opaque nonces only: no account credentials, media URLs or playback metadata. */
internal interface PlaybackProgressLeaseCheckpoint {
    suspend fun read(ownerKey: String): String?
    suspend fun write(ownerKey: String, nonce: String)
}

internal class MemoryPlaybackProgressLeaseCheckpoint : PlaybackProgressLeaseCheckpoint {
    private val values = linkedMapOf<String, String>()
    override suspend fun read(ownerKey: String): String? = synchronized(values) { values[ownerKey] }
    override suspend fun write(ownerKey: String, nonce: String) {
        synchronized(values) {
            values.remove(ownerKey)
            values[ownerKey] = nonce
            while (values.size > MAX_PROGRESS_LEASE_CHECKPOINTS) values.remove(values.keys.first())
        }
    }
}

internal class AndroidPlaybackProgressLeaseCheckpoint(context: Context) : PlaybackProgressLeaseCheckpoint {
    private val context = context.applicationContext
    private val mutex = Mutex()

    override suspend fun read(ownerKey: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(ownerKey, null)?.substringAfter(':')
        }
    }

    override suspend fun write(ownerKey: String, nonce: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(ownerKey, "${System.currentTimeMillis()}:$nonce")
            val others = prefs.all.entries.filter { it.key != ownerKey }
                .sortedBy { (it.value as? String)?.substringBefore(':')?.toLongOrNull() ?: 0L }
            others.take((others.size + 1 - MAX_PROGRESS_LEASE_CHECKPOINTS).coerceAtLeast(0))
                .forEach { editor.remove(it.key) }
            if (!editor.commit()) throw IOException("Playback progress lease checkpoint could not be saved")
        }
    }

    private companion object { const val PREFS_NAME = "playback_progress_leases" }
}

internal const val MAX_PROGRESS_LEASE_CHECKPOINTS = 512
