package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.core.debrid.DirectDebridFailure
import com.nuvio.tv.core.debrid.DirectDebridFailureReason
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.core.debrid.canonicalAvailabilityHash
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

internal data class AutomaticStreamAttempt<T>(val playback: T? = null,
    val failure: DirectDebridResolveResult? = null)

internal const val SOURCE_RESOLUTION_TIMEOUT_MS = 30_000L

/** One bounded selection: each source hash is attempted once, including aliases from other addons. */
internal suspend fun <T> resolveAutomaticStreamFallback(
    initial: Stream,
    ranked: List<Stream>,
    eligible: (Stream) -> Boolean,
    selectNext: (List<Stream>) -> Stream?,
    isCurrent: () -> Boolean,
    budgetMillis: Long = SOURCE_RESOLUTION_TIMEOUT_MS,
    onAttempt: (Int, Int) -> Unit = { _, _ -> },
    resolve: suspend (Stream) -> AutomaticStreamAttempt<T>
): AutomaticStreamAttempt<T> {
    var remaining = ranked.filter(eligible)
    var candidate: Stream? = initial.takeIf(eligible)
    val candidates = mutableListOf<Stream>()
    while (candidate != null) {
        yield()
        if (!isCurrent()) return AutomaticStreamAttempt()
        val selected = candidate
        candidates += selected
        val identity = selected.automaticSourceIdentity()
        remaining = remaining.filterNot { it.automaticSourceIdentity() == identity }
        candidate = selectNext(remaining)?.takeIf { it in remaining }
    }
    var last = AutomaticStreamAttempt<T>()
    for ((index, selected) in candidates.withIndex()) {
        coroutineContext.ensureActive()
        if (!isCurrent()) return AutomaticStreamAttempt()
        onAttempt(index + 1, candidates.size)
        last = withTimeoutOrNull(budgetMillis.coerceAtLeast(1L)) { resolve(selected) }
            ?: AutomaticStreamAttempt(failure = DirectDebridResolveResult.Failure(
                DirectDebridFailure(DirectDebridFailureReason.TIMED_OUT)))
        coroutineContext.ensureActive()
        if (!isCurrent()) return AutomaticStreamAttempt()
        if (last.playback != null || !last.failure.canAdvanceAutomaticSource()) return last
    }
    return last
}

private fun Stream.automaticSourceIdentity(): String =
    canonicalAvailabilityHash(getEffectiveInfoHash())?.let { "hash:$it" }
        ?: getStreamUrl()?.let { "url:$it" }
        ?: stableKey(0)

private fun DirectDebridResolveResult?.canAdvanceAutomaticSource(): Boolean =
    this == DirectDebridResolveResult.NotCached || this is DirectDebridResolveResult.Failure && detail.reason in setOf(
        DirectDebridFailureReason.PROCESSING,
        DirectDebridFailureReason.UNAVAILABLE,
        DirectDebridFailureReason.SOURCE_REJECTED,
        DirectDebridFailureReason.WRONG_EPISODE
    )
