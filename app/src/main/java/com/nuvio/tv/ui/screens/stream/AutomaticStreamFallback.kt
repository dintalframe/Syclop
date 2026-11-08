package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.core.debrid.DirectDebridFailure
import com.nuvio.tv.core.debrid.DirectDebridFailureReason
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.core.debrid.canonicalAvailabilityHash
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

internal data class AutomaticStreamAttempt<T>(val playback: T? = null,
    val failure: DirectDebridResolveResult? = null)

internal const val AUTOMATIC_STREAM_BUDGET_MS = 30_000L

/** One bounded selection: each source hash is attempted once, including aliases from other addons. */
internal suspend fun <T> resolveAutomaticStreamFallback(
    initial: Stream,
    ranked: List<Stream>,
    mode: StreamAutoPlayMode,
    eligible: (Stream) -> Boolean,
    selectNext: (List<Stream>) -> Stream?,
    isCurrent: () -> Boolean,
    budgetMillis: Long = AUTOMATIC_STREAM_BUDGET_MS,
    resolve: suspend (Stream) -> AutomaticStreamAttempt<T>
): AutomaticStreamAttempt<T> = withTimeoutOrNull(budgetMillis.coerceAtLeast(1L)) {
    var remaining = ranked.filter(eligible)
    var candidate: Stream? = initial.takeIf(eligible)
    var last = AutomaticStreamAttempt<T>()
    val attempted = mutableSetOf<String>()
    repeat(if (mode == StreamAutoPlayMode.MANUAL) 1 else 3) {
        coroutineContext.ensureActive()
        if (!isCurrent()) return@withTimeoutOrNull AutomaticStreamAttempt<T>()
        val selected = candidate ?: return@withTimeoutOrNull last
        val identity = selected.automaticSourceIdentity()
        if (!attempted.add(identity)) return@withTimeoutOrNull last
        remaining = remaining.filterNot { it.automaticSourceIdentity() == identity }
        last = resolve(selected)
        coroutineContext.ensureActive()
        if (!isCurrent()) return@withTimeoutOrNull AutomaticStreamAttempt<T>()
        if (last.playback != null || !last.failure.canAdvanceAutomaticSource()) return@withTimeoutOrNull last
        candidate = selectNext(remaining)
    }
    last
} ?: AutomaticStreamAttempt(failure = DirectDebridResolveResult.Failure(
    DirectDebridFailure(DirectDebridFailureReason.TIMED_OUT)))

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
