package com.nuvio.tv.core.initialization

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class InitializationProvider(val label: String) { TRAKT("Trakt"), SIMKL("Simkl"), MAL("MyAnimeList") }
enum class InitializationFailure { NETWORK, TIMEOUT, ACCOUNT_CHANGED, UNAVAILABLE, INCOMPLETE }

class InitializationReadException(val reason: InitializationFailure) : Exception(reason.name)

/** Ephemeral ownership only. Never persisted, logged, or constructed from a provider URL. */
class InitializationReadScope(
    val provider: InitializationProvider,
    internal val operationKey: String,
    private val current: () -> Boolean
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InitializationReadScope>
    fun isCurrent(): Boolean = current()
    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (!current()) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
    }
    override fun toString() = "InitializationReadScope(provider=$provider)"
}

data class InitializationReadResult(val count: Int? = null, val truncated: Boolean = false, val complete: Boolean = true)

suspend fun checkpointInitializationRead() {
    currentCoroutineContext()[InitializationReadScope]?.checkpoint()
}

/** Concurrent callers share the same actual read, including a failed outcome. A later retry may run. */
class InitializationReadGate {
    private val mutex = Mutex()
    @Volatile private var sequence = 0L
    private var completedKey: String? = null
    private var completed: Result<InitializationReadResult>? = null
    private var completedAtNanos = 0L

    /** Reuse a full import that just finished in a background startup collector. Never reuse no-op/failure. */
    suspend fun recentComplete(scope: InitializationReadScope): InitializationReadResult? {
        val observed = sequence
        return mutex.withLock {
            scope.checkpoint()
            // A caller already waiting on this read receives its failure once; explicit later Retry may run.
            if (observed != sequence && completedKey == scope.operationKey && completed?.isFailure == true)
                requireNotNull(completed).getOrThrow()
            val elapsed = System.nanoTime() - completedAtNanos
            completed?.getOrNull()?.takeIf { completedKey == scope.operationKey && it.complete &&
                elapsed >= 0L && elapsed <= 30_000_000_000L }
        }
    }

    suspend fun run(scope: InitializationReadScope,
        block: suspend () -> InitializationReadResult): InitializationReadResult {
        val observed = sequence
        return mutex.withLock {
            scope.checkpoint()
            if (observed != sequence && completedKey == scope.operationKey) {
                return@withLock requireNotNull(completed).getOrThrow()
            }
            try {
                val value = withContext(scope) { scope.checkpoint(); block().also { scope.checkpoint() } }
                completed = Result.success(value)
                completedAtNanos = System.nanoTime()
                completedKey = scope.operationKey
                sequence++
                value
            } catch (cancelled: CancellationException) {
                // Cancellation is not a reusable completion and must never write an import marker.
                throw cancelled
            } catch (failure: Exception) {
                completed = Result.failure(failure)
                completedKey = scope.operationKey
                sequence++
                throw failure
            }
        }
    }
}
