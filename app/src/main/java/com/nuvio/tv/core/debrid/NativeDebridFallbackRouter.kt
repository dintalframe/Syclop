package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only a service-neutral hash may use multiple services; provider contracts are never reassigned. */
internal object NativeDebridFallbackRouter {
    private val hash = Regex("[a-fA-F0-9]{40}|[a-zA-Z2-7]{32}")
    private val nextStart = AtomicInteger()
    private const val MAX_PARALLEL_SERVICES = 3
    private const val SERVICE_STAGGER_MILLIS = 250L

    fun neutralHashSource(stream: Stream): Boolean = stream.clientResolve == null &&
        stream.needsLocalDebridResolve() && stream.getEffectiveInfoHash()?.matches(hash) == true

    fun accounts(settings: DebridSettings, permitFallback: Boolean): List<DebridServiceCredential> {
        val preferred = settings.activeResolverCredential ?: return emptyList()
        if (!preferred.provider.supports(DebridProviderCapability.LocalTorrentResolve)) return emptyList()
        if (!permitFallback || !settings.connectedServiceFallbackEnabled) return listOf(preferred)
        val connected = settings.resolverServices.filter { it.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
        // Keep the full capable pool so a fourth service is not permanently excluded by the
        // concurrency bound. resolve rotates it and queues each service at most once.
        return (listOf(preferred) + connected.filter { it.provider.id == DebridProviders.REAL_DEBRID_ID } + connected)
            .distinctBy { it.provider.id }
    }

    suspend fun resolve(
        accounts: List<DebridServiceCredential>,
        isCurrent: () -> Boolean,
        onAttempt: (String) -> Unit,
        attempt: suspend (DebridServiceCredential) -> NativeDebridAttempt
    ): NativeDebridRoute = supervisorScope {
        fun checkCurrent() {
            if (!isCurrent()) throw CancellationException("The active viewer or connected service changed.")
        }
        currentCoroutineContext().ensureActive()
        checkCurrent()
        val pool = accounts.filter { it.apiKey.isNotBlank() && it.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
            .distinctBy { it.provider.id }
        val start = if (pool.size > 1) Math.floorMod(nextStart.getAndIncrement(), pool.size) else 0
        val selected = pool.drop(start) + pool.take(start)
        val permits = Semaphore(MAX_PARALLEL_SERVICES)
        val stopDispatch = AtomicBoolean(false)
        val jobs = selected.mapIndexed { index, account ->
            async {
                permits.withPermit {
                    if (stopDispatch.get()) return@withPermit NativeDebridRoute(account.provider.id, DirectDebridResolveResult.NotCached)
                    if (index in 1 until MAX_PARALLEL_SERVICES) delay(index * SERVICE_STAGGER_MILLIS)
                    if (stopDispatch.get()) return@withPermit NativeDebridRoute(account.provider.id, DirectDebridResolveResult.NotCached)
                    currentCoroutineContext().ensureActive()
                    checkCurrent()
                    onAttempt(account.provider.id)
                    val outcome = try {
                        attempt(account)
                    } catch (cancelled: CancellationException) {
                        // A queued service can acquire this permit before the parent awaits
                        // the cancellation. Stop dispatch before releasing it.
                        stopDispatch.set(true)
                        throw cancelled
                    } catch (error: Exception) {
                        NativeDebridAttempt(directDebridExceptionFailure(error, account.provider.id))
                    }
                    currentCoroutineContext().ensureActive()
                    checkCurrent()
                    val result = outcome.result.let { result ->
                        if (result is DirectDebridResolveResult.Success && result.url.toHttpUrlOrNull() == null)
                            DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.UNAVAILABLE, account.provider.id))
                        else result
                    }
                    if (result is DirectDebridResolveResult.Success || result == DirectDebridResolveResult.Stale ||
                        result == DirectDebridResolveResult.MissingApiKey ||
                        (result is DirectDebridResolveResult.Failure && result.detail.reason == DirectDebridFailureReason.WRONG_EPISODE)) {
                        stopDispatch.set(true)
                    }
                    NativeDebridRoute(account.provider.id, result)
                }
            }
        }
        val remaining = jobs.toMutableList()
        var last = NativeDebridRoute(null, DirectDebridResolveResult.MissingApiKey)
        var serviceFailed = false
        var classifiedFailure: NativeDebridRoute? = null
        try {
            while (remaining.isNotEmpty()) {
                val (completed, route) = select {
                    remaining.forEach { job -> job.onAwait { job to it } }
                }
                remaining.remove(completed)
                currentCoroutineContext().ensureActive()
                checkCurrent()
                last = route
                when (val result = route.result) {
                    is DirectDebridResolveResult.Success -> return@supervisorScope route
                    is DirectDebridResolveResult.Failure -> {
                        // Contradictory file/episode proof remains terminal, as does a stale
                        // captured scope below. Parallelism never relaxes file selection.
                        if (result.detail.reason == DirectDebridFailureReason.WRONG_EPISODE) return@supervisorScope route
                        val previous = (classifiedFailure?.result as? DirectDebridResolveResult.Failure)?.detail?.reason
                        if (previous == null || failurePriority(result.detail.reason) > failurePriority(previous)) classifiedFailure = route
                    }
                    DirectDebridResolveResult.NotCached -> Unit
                    DirectDebridResolveResult.Error -> serviceFailed = true
                    DirectDebridResolveResult.MissingApiKey, DirectDebridResolveResult.Stale -> return@supervisorScope route
                }
            }
            classifiedFailure ?: if (serviceFailed) last.copy(result = DirectDebridResolveResult.Error) else last
        } finally {
            // Shared resolver jobs track their own waiters. Cancel only this operation's
            // waits; do not delete a remote torrent or another caller's shared request.
            jobs.forEach { it.cancel() }
        }
    }

    private fun failurePriority(reason: DirectDebridFailureReason): Int = when (reason) {
        DirectDebridFailureReason.WRONG_EPISODE -> 7
        DirectDebridFailureReason.PROCESSING -> 6
        DirectDebridFailureReason.RATE_LIMITED -> 5
        DirectDebridFailureReason.AUTH_EXPIRED -> 4
        DirectDebridFailureReason.TIMED_OUT -> 3
        DirectDebridFailureReason.NETWORK_FAILURE -> 2
        DirectDebridFailureReason.SOURCE_REJECTED, DirectDebridFailureReason.UNAVAILABLE -> 1
    }
}

internal data class NativeDebridAttempt(val result: DirectDebridResolveResult)
internal data class NativeDebridRoute(val providerId: String?, val result: DirectDebridResolveResult)
