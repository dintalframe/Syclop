package com.nuvio.tv.core.providers

import androidx.annotation.Keep
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Static errors only. Provider URLs and server text never enter status storage/UI. */
@Keep
enum class ProviderUpdateError { NETWORK_OR_INVALID, IDENTITY_CHANGED, CANCELLED }
@Keep
data class ProviderUpdateStatus(val lastAttemptMillis: Long = 0L, val lastSuccessMillis: Long = 0L,
    val error: ProviderUpdateError? = null, val running: Boolean = false)

object ProviderUpdatePolicy {
    const val INTERVAL_MS = 24L * 60L * 60L * 1000L
    // Android periodic flex must be at least 5% of the interval (72 minutes at 24 h).
    const val PERIODIC_FLEX_MS = 90L * 60L * 1000L
    const val FAILED_RETRY_MS = 60L * 60L * 1000L
    fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun due(state: ProviderUpdateStatus, now: Long, periodic: Boolean = false): Boolean {
        fun elapsed(time: Long, delay: Long) = time <= 0L || now < time || now - time >= delay
        // Only the periodic job may check within its explicit OS flex window.
        // App-start checks stay at 24 hours; failures never bypass the retry gap.
        val interval = if (periodic) INTERVAL_MS - PERIODIC_FLEX_MS else INTERVAL_MS
        return elapsed(state.lastSuccessMillis, interval) && elapsed(state.lastAttemptMillis,
            if (state.error != null) FAILED_RETRY_MS else interval)
    }
}

interface ProviderUpdatePersistence {
    fun read(): Map<String, ProviderUpdateStatus>
    fun write(states: Map<String, ProviderUpdateStatus>)
}

/** Refreshes data only. Never modifies installation/enabled/order preferences. */
class ProviderUpdateEngine(
    private val persistence: ProviderUpdatePersistence,
    private val clock: () -> Long,
    private val refresh: suspend (String) -> ProviderUpdateError?
) {
    private val mutex = Mutex()
    private val _states = MutableStateFlow(persistence.read().mapValues { it.value.copy(running = false) })
    val states: StateFlow<Map<String, ProviderUpdateStatus>> = _states.asStateFlow()
    private val slots = Semaphore(2)

    suspend fun update(urls: List<String>, force: Boolean = false, periodic: Boolean = false) = mutex.withLock {
        val unique = urls.distinct().take(500)
        val wanted = unique.filter { force || ProviderUpdatePolicy.due(_states.value[ProviderUpdatePolicy.key(it)] ?: ProviderUpdateStatus(), clock(), periodic) }
            .sortedBy { _states.value[ProviderUpdatePolicy.key(it)]?.lastAttemptMillis ?: 0L }
            .take(if (force) 500 else 30)
        coroutineScope {
            wanted.map { url -> async { slots.withPermit {
                val key = ProviderUpdatePolicy.key(url)
                val before = _states.value[key] ?: ProviderUpdateStatus()
                change(key, before.copy(lastAttemptMillis = clock().coerceAtLeast(1L), running = true))
                try {
                    val result = withTimeoutOrNull(30_000L) { Outcome(refresh(url)) }
                    val error = result?.error ?: if (result == null) ProviderUpdateError.NETWORK_OR_INVALID else null
                    val current = _states.value[key] ?: before
                    change(key, current.copy(lastSuccessMillis = if (error == null) clock().coerceAtLeast(1L) else before.lastSuccessMillis,
                        error = error, running = false))
                } catch (cancelled: CancellationException) {
                    change(key, (_states.value[key] ?: before).copy(error = ProviderUpdateError.CANCELLED, running = false))
                    throw cancelled
                } catch (_: Exception) {
                    change(key, (_states.value[key] ?: before).copy(error = ProviderUpdateError.NETWORK_OR_INVALID, running = false))
                }
            } } }.awaitAll()
        }
        // Large collections continue in another OS-managed batch; no timer/poll loop.
        unique.any { ProviderUpdatePolicy.due(_states.value[ProviderUpdatePolicy.key(it)] ?: ProviderUpdateStatus(), clock(), periodic) }
    }
    private data class Outcome(val error: ProviderUpdateError?)
    @Synchronized private fun change(key: String, state: ProviderUpdateStatus) {
        val next = (_states.value + (key to state)).entries.sortedByDescending { it.value.lastAttemptMillis }.take(500).associate { it.toPair() }
        // A process death during network work must restore an interrupted/retryable attempt.
        persistence.write(next.mapValues { (_, value) -> if (value.running) value.copy(error = ProviderUpdateError.CANCELLED, running = false) else value })
        _states.value = next
    }
}
