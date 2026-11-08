package com.nuvio.tv.core.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** App-owned, bounded capture: final player snapshots can finish after their ViewModel clears. */
internal class TrackingPlaybackOriginResolver(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val timeoutMs: Long = 5_000L
) {
    fun capture(
        profileId: Int,
        prepared: TrackingPlaybackOriginCapture,
        localScope: suspend () -> String?
    ): Deferred<TrackingPlaybackOrigin> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        var capturedLocalScope: String? = null
        fun unavailable() = TrackingPlaybackOrigin(profileId, emptyMap(), capturedLocalScope, { false })
        try {
            withTimeoutOrNull(timeoutMs) {
                capturedLocalScope = localScope()
                prepared.resolve(capturedLocalScope)
            } ?: unavailable()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            unavailable()
        } catch (_: Exception) {
            unavailable()
        }
    }
}
