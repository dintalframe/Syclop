package com.nuvio.tv.core.tracking

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Credential-free identity of the accounts that owned a playback visit. */
@Serializable
data class TrackingPlaybackOriginRecord(
    val profileId: Int,
    val grants: Map<String, String>,
    val localPlaybackScope: String? = null
)

class TrackingPlaybackAccountChangedException : Exception("Playback account changed")

/** A runtime grant also pins the profile selection; token refresh does not replace it. */
class TrackingPlaybackGrant(
    val providerId: TrackingProviderId,
    val profileId: Int,
    val grantId: String,
    private val current: () -> Boolean,
    internal val requestContext: CoroutineContext = EmptyCoroutineContext
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TrackingPlaybackGrant>

    fun isCurrent(): Boolean = current()
    fun checkpoint() {
        if (!isCurrent()) throw TrackingPlaybackAccountChangedException()
    }

    internal suspend fun <T> execute(block: suspend () -> T): T = withContext(requestContext + this) {
        checkpoint()
        block().also { checkpoint() }
    }
}

/** Constructed synchronously, before resolving an existing account's durable UUID can suspend. */
class TrackingPlaybackGrantCapture(private val resolve: suspend () -> TrackingPlaybackGrant?) {
    suspend fun resolve(): TrackingPlaybackGrant? = try {
        resolve.invoke()?.takeIf { it.isCurrent() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

class TrackingPlaybackOrigin internal constructor(
    val profileId: Int,
    internal val grants: Map<TrackingProviderId, TrackingPlaybackGrant>,
    val localPlaybackScope: String?,
    private val selectionCurrent: () -> Boolean = { true }
) {
    fun isProfileCurrent(): Boolean = selectionCurrent()

    fun record(): TrackingPlaybackOriginRecord = TrackingPlaybackOriginRecord(
        profileId, grants.filterValues { selectionCurrent() && it.isCurrent() }
            .mapKeys { it.key.storageId }.mapValues { it.value.grantId }, localPlaybackScope
    )

    internal fun grant(provider: TrackingProviderId): TrackingPlaybackGrant? = grants[provider]?.takeIf {
        selectionCurrent() && it.profileId == profileId && it.isCurrent()
    }
}

class TrackingPlaybackOriginCapture internal constructor(
    private val profileId: Int,
    private val captures: List<TrackingPlaybackGrantCapture>,
    private val selectionCurrent: () -> Boolean = { true }
) {
    suspend fun resolve(localPlaybackScope: String? = null): TrackingPlaybackOrigin = TrackingPlaybackOrigin(
        profileId, captures.mapNotNull { it.resolve() }.filter { it.profileId == profileId }.map { captured ->
            TrackingPlaybackGrant(captured.providerId, profileId, captured.grantId,
                { selectionCurrent() && captured.isCurrent() }, captured.requestContext)
        }.associateBy { it.providerId }, localPlaybackScope, selectionCurrent
    )
}

/** Called at request and receipt boundaries, including after pacing/refresh/retry suspensions. */
suspend fun checkpointTrackingPlaybackOrigin(provider: TrackingProviderId) {
    currentCoroutineContext()[TrackingPlaybackGrant]?.takeIf { it.providerId == provider }?.checkpoint()
}

/** Only recovery may wait for startup selection loading; a live entry never retargets its grant. */
internal suspend fun recoverTrackingPlaybackGrant(
    profileId: Int,
    grantId: String,
    currentProfileId: () -> Int,
    prepare: () -> TrackingPlaybackGrantCapture?
): TrackingPlaybackGrant? = withTimeoutOrNull(5_000L) {
    // Some credential stores publish no distinct StateFlow value when two disconnected profiles
    // have equal state. Poll their synchronized scope, rather than wait forever for an emission.
    while (currentProfileId() != profileId) delay(20L)
    prepare()?.resolve()?.takeIf { it.grantId == grantId }
}
