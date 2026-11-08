package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Singleton
class MdbListTrackingProvider @Inject constructor(
    auth: MdbListAuthStore,
    profiles: ProfileManager,
    override val scrobbler: MdbListTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.MDBLIST,
        "MDBList",
        setOf(TrackingCapability.AUTHENTICATION, TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE, TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE, TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE, TrackingCapability.SCROBBLE)
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}

internal class MdbListPlaybackAuthScope(val auth: MdbListAuthScope) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<MdbListPlaybackAuthScope>
}

@Singleton
class MdbListTrackingScrobbler @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val service: MdbListScrobbleService,
    private val auth: MdbListAuthStore? = null
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.MDBLIST

    override fun preparePlaybackGrant(profileId: Int): TrackingPlaybackGrantCapture? {
        val store = auth ?: return null
        val native = store.scope()
        if (native.profileId != profileId || !store.state.value.isAuthenticated) return null
        return TrackingPlaybackGrantCapture {
            val id = store.capturePlaybackGrantId(profileId) ?: return@TrackingPlaybackGrantCapture null
            TrackingPlaybackGrant(providerId, profileId, id, {
                store.isCurrent(native) && store.isPlaybackGrantCurrent(profileId, id)
            }, MdbListPlaybackAuthScope(native))
        }
    }

    override suspend fun recoverPlaybackGrant(profileId: Int, grantId: String): TrackingPlaybackGrant? =
        recoverTrackingPlaybackGrant(profileId, grantId, { auth?.scope()?.profileId ?: -1 }) { preparePlaybackGrant(profileId) }

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        checkpointTrackingPlaybackOrigin(providerId)
        val scope = kotlinx.coroutines.currentCoroutineContext()[MdbListPlaybackAuthScope]?.auth ?: sync.currentScope()
        service.scrobble(scope, action, event)
    }
}

@Singleton
class MdbListTrackingHistoryWriter @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val service: MdbListHistoryService
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.MDBLIST

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        // MDBList answers a show-level mark by marking every episode watched, same as Simkl.
        val pushableItems = items.filterNot(TrackingHistoryItem::isWholeSeriesMark)
        if (pushableItems.isEmpty()) return TrackingMutationResult(0)
        return service.add(scope, pushableItems)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        return service.remove(scope, items)
    }
}
