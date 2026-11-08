package com.nuvio.tv.data.mal

import com.nuvio.tv.core.tracking.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class MalPlaybackAuthScope(val auth: MalScope) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MalPlaybackAuthScope>
}

/** Each completion owns its originating grant and media; outgoing videos do not replace a global key. */
@Singleton
class MalPlaybackSync internal constructor(
    private val auth: MalAuthRepository,
    private val repository: MalRepository,
    private val worker: CoroutineScope
) {
    @Inject constructor(auth: MalAuthRepository, repository: MalRepository) : this(
        auth, repository, CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    fun preparePlaybackGrant(profileId: Int): TrackingPlaybackGrantCapture? {
        val account = auth.state.value
        val native = account.scope
        if (native.profileId != profileId || !account.connected) return null
        return TrackingPlaybackGrantCapture {
            val id = auth.store.capturePlaybackGrantId(profileId) ?: return@TrackingPlaybackGrantCapture null
            TrackingPlaybackGrant(TrackingProviderId.MAL, profileId, id, {
                auth.store.isCurrent(native) && auth.store.isPlaybackGrantCurrent(profileId, id)
            }, MalPlaybackAuthScope(native))
        }
    }

    suspend fun recoverPlaybackGrant(profileId: Int, grantId: String): TrackingPlaybackGrant? =
        recoverTrackingPlaybackGrant(profileId, grantId, { auth.state.value.scope.profileId }) { preparePlaybackGrant(profileId) }

    fun record(action: TrackingScrobbleAction, event: TrackingScrobbleEvent, origin: TrackingPlaybackOrigin? = null) {
        if (action != TrackingScrobbleAction.STOP || !event.progressPercent.isFinite() ||
            event.progressPercent < MalRules.COMPLETION_PERCENT) return
        val grant = origin?.grant(TrackingProviderId.MAL) ?: return
        val native = grant.requestContext[MalPlaybackAuthScope]?.auth ?: return
        if (!auth.state.value.automaticProgress) return
        val id = event.media.ids.mal ?: event.media.catalog?.contentId
            ?.takeIf { it.startsWith("mal:") }?.removePrefix("mal:")?.substringBefore(':')?.toLongOrNull()
        if (id == null || id <= 0) {
            if (event.media.kind == TrackingMediaKind.ANIME) repository.unmappedPlayback(native)
            return
        }
        worker.launch {
            try {
                grant.execute {
                    repository.syncCompletedEpisode(native, id, event.progressPercent,
                        event.media.episode?.season, event.media.episode?.number)
                }
            } catch (_: TrackingPlaybackAccountChangedException) {
                // A queued completion never binds to the replacement account.
            }
        }
    }
}
