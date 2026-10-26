package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only a service-neutral hash may be retried; a provider URL/contract is never reassigned. */
internal object NativeDebridFallbackRouter {
    private val hash = Regex("[a-fA-F0-9]{40}|[a-zA-Z2-7]{32}")

    fun neutralHashSource(stream: Stream): Boolean = stream.clientResolve == null &&
        stream.needsLocalDebridResolve() && stream.getEffectiveInfoHash()?.matches(hash) == true

    fun accounts(settings: DebridSettings, permitFallback: Boolean): List<DebridServiceCredential> {
        val preferred = settings.activeResolverCredential ?: return emptyList()
        if (!preferred.provider.supports(DebridProviderCapability.LocalTorrentResolve)) return emptyList()
        if (!permitFallback || !settings.connectedServiceFallbackEnabled) return listOf(preferred)
        val connected = settings.resolverServices.filter { it.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
        return (listOf(preferred) + connected.filter { it.provider.id == DebridProviders.REAL_DEBRID_ID } + connected)
            .distinctBy { it.provider.id }.take(4)
    }

    suspend fun resolve(
        accounts: List<DebridServiceCredential>,
        isCurrent: () -> Boolean,
        onAttempt: (String) -> Unit,
        attempt: suspend (DebridServiceCredential) -> NativeDebridAttempt
    ): NativeDebridRoute {
        var last = NativeDebridRoute(null, DirectDebridResolveResult.MissingApiKey)
        var serviceFailed = false
        var classifiedFailure: NativeDebridRoute? = null
        for (account in accounts.distinctBy { it.provider.id }.take(4)) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("The active viewer or connected service changed.")
            onAttempt(account.provider.id)
            val outcome = attempt(account)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("The active viewer or connected service changed.")
            last = NativeDebridRoute(account.provider.id, outcome.result)
            when (outcome.result) {
                is DirectDebridResolveResult.Success -> return last
                is DirectDebridResolveResult.Failure -> {
                    if (outcome.result.detail.reason == DirectDebridFailureReason.WRONG_EPISODE) return last
                    val previous = (classifiedFailure?.result as? DirectDebridResolveResult.Failure)?.detail?.reason
                    if (previous == null || failurePriority(outcome.result.detail.reason) > failurePriority(previous)) classifiedFailure = last
                }
                DirectDebridResolveResult.NotCached -> Unit
                DirectDebridResolveResult.Error -> serviceFailed = true
                DirectDebridResolveResult.MissingApiKey, DirectDebridResolveResult.Stale -> return last
            }
        }
        return classifiedFailure ?: if (serviceFailed) last.copy(result = DirectDebridResolveResult.Error) else last
    }

    private fun failurePriority(reason: DirectDebridFailureReason): Int = when (reason) {
        DirectDebridFailureReason.WRONG_EPISODE -> 7
        DirectDebridFailureReason.PROCESSING -> 6
        DirectDebridFailureReason.RATE_LIMITED -> 5
        DirectDebridFailureReason.AUTH_EXPIRED -> 4
        DirectDebridFailureReason.TIMED_OUT -> 3
        DirectDebridFailureReason.NETWORK_FAILURE -> 2
        DirectDebridFailureReason.UNAVAILABLE -> 1
    }
}

internal data class NativeDebridAttempt(val result: DirectDebridResolveResult)
internal data class NativeDebridRoute(val providerId: String?, val result: DirectDebridResolveResult)
