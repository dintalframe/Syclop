package com.nuvio.tv.data.mal

import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingMediaKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives the real internal/external player completion path, without changing other trackers. */
@Singleton
class MalPlaybackSync @Inject constructor(
    private val auth: MalAuthRepository,
    private val repository: MalRepository
) {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackScope: MalScope? = null
    private var playbackKey: String? = null

    @Synchronized
    fun record(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val account = auth.state.value
        if (action == TrackingScrobbleAction.START) {
            playbackScope = account.scope.takeIf { account.connected }
            playbackKey = event.media.stableKey
            return
        }
        if (!account.connected || !account.automaticProgress || action != TrackingScrobbleAction.STOP ||
            !event.progressPercent.isFinite() || event.progressPercent < MalRules.COMPLETION_PERCENT) return
        // A delayed stop from another profile must never write to the account now on screen.
        if (playbackScope != account.scope || playbackKey != event.media.stableKey) return
        val id = event.media.ids.mal ?: event.media.catalog?.contentId
            ?.takeIf { it.startsWith("mal:") }?.removePrefix("mal:")?.substringBefore(':')?.toLongOrNull()
        if (id == null || id <= 0) {
            if (event.media.kind == TrackingMediaKind.ANIME) repository.unmappedPlayback(account.scope)
            return
        }
        worker.launch {
            repository.syncCompletedEpisode(account.scope, id, event.progressPercent,
                event.media.episode?.season, event.media.episode?.number)
        }
    }
}
