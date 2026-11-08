package com.nuvio.tv.core.tracking

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingScrobbleCoordinator @Inject constructor(
    private val providerRegistry: TrackingProviderRegistry,
    private val malPlaybackSync: com.nuvio.tv.data.mal.MalPlaybackSync? = null,
    private val profileManager: ProfileManager? = null
) {
    private val playbackOriginResolver = TrackingPlaybackOriginResolver()

    fun capturePlaybackOrigin(profileId: Int, localScope: suspend () -> String?) =
        playbackOriginResolver.capture(profileId, preparePlaybackOrigin(profileId), localScope)

    fun preparePlaybackOrigin(profileId: Int): TrackingPlaybackOriginCapture {
        val selection = profileManager?.activeProfileSelectionRevision
        fun current() = profileManager == null || (profileManager.activeProfileReady.value &&
            profileManager.activeProfileId.value == profileId && profileManager.activeProfileSelectionRevision == selection)
        return TrackingPlaybackOriginCapture(profileId,
            if (!current()) emptyList() else providerRegistry.providers().mapNotNull {
                it.scrobbler?.preparePlaybackGrant(profileId)
            } + listOfNotNull(malPlaybackSync?.preparePlaybackGrant(profileId)), ::current)
    }

    suspend fun recoverPlaybackOrigin(record: TrackingPlaybackOriginRecord): TrackingPlaybackOrigin {
        val ready = withTimeoutOrNull(5_000L) { profileManager?.activeProfileReady?.first { it }; true } == true
        val selection = profileManager?.activeProfileSelectionRevision
        fun current() = ready && (profileManager == null || (profileManager.activeProfileId.value == record.profileId &&
            profileManager.activeProfileSelectionRevision == selection))
        if (!current()) return TrackingPlaybackOrigin(record.profileId, emptyMap(), record.localPlaybackScope, { false })
        val grants = supervisorScope {
            record.grants.mapNotNull { (id, grantId) ->
                val provider = TrackingProviderId.fromStorage(id) ?: return@mapNotNull null
                async {
                    val captured = if (provider == TrackingProviderId.MAL)
                        malPlaybackSync?.recoverPlaybackGrant(record.profileId, grantId)
                    else providerRegistry.provider(provider)?.scrobbler?.recoverPlaybackGrant(record.profileId, grantId)
                    captured?.let { TrackingPlaybackGrant(it.providerId, it.profileId, it.grantId,
                        { current() && it.isCurrent() }, it.requestContext) }
                }
            }.awaitAll().filterNotNull().associateBy { it.providerId }
        }
        return TrackingPlaybackOrigin(record.profileId, grants, record.localPlaybackScope, ::current)
    }

    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
        origin: TrackingPlaybackOrigin? = null
    ): List<TrackingScrobbleFailure> {
        if (origin == null) return emptyList()
        malPlaybackSync?.record(action, event, origin)
        val scrobblers = boundScrobblers(origin)
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "coordinator dispatch action=${action.wireValue} providers=" +
                scrobblers.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                " ${event.scrobbleDiagnosticSummary()}"
        )
        return dispatch(
            scrobblers = scrobblers,
            action = action,
            event = event
        ).also { failures ->
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "coordinator complete action=${action.wireValue} failures=" +
                    failures.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                    " ${event.scrobbleDiagnosticSummary()}"
            )
        }
    }

    suspend fun scrobbleSeek(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
        origin: TrackingPlaybackOrigin? = null
    ): List<TrackingScrobbleFailure> = dispatchSeek(
        scrobblers = origin?.let(::boundScrobblers).orEmpty(),
        action = action,
        event = event
    )

    private fun boundScrobblers(origin: TrackingPlaybackOrigin): List<TrackingScrobbler> =
        origin.grants.keys.mapNotNull { providerId ->
            val grant = origin.grant(providerId) ?: return@mapNotNull null
            val target = providerRegistry.provider(providerId)?.scrobbler ?: return@mapNotNull null
            object : TrackingScrobbler {
                override val providerId = target.providerId
                override val seekScrobblePolicy = target.seekScrobblePolicy
                override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
                    try {
                        grant.execute { target.scrobble(action, event) }
                    } catch (cancelled: CancellationException) {
                        // Existing provider scope guards use CancellationException for stale
                        // accounts. Preserve real task cancellation, isolate a replaced provider.
                        currentCoroutineContext().ensureActive()
                        if (!grant.isCurrent()) throw TrackingPlaybackAccountChangedException()
                        throw cancelled
                    }
                }
            }
        }

    private suspend fun dispatch(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(scrobblers, action, event)
        .also { failures -> failures.log(action, false) }

    private suspend fun dispatchSeek(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingSeekScrobble(scrobblers, action, event)
        .also { failures -> failures.log(action, true) }

    private fun List<TrackingScrobbleFailure>.log(
        action: TrackingScrobbleAction,
        seek: Boolean
    ) {
        forEach { failure ->
            Log.w(
                "TrackingScrobble",
                "${failure.providerId.storageId} ${if (seek) "seek " else ""}scrobble ${action.wireValue} failed",
                failure.cause
            )
        }
    }
}

suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers.map { scrobbler ->
        async {
            try {
                scrobbler.scrobble(action, event)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TrackingScrobbleFailure(scrobbler.providerId, error)
            }
        }
    }.awaitAll().filterNotNull()
}

suspend fun dispatchTrackingSeekScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(
    scrobblers = scrobblers.filter { it.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART },
    action = action,
    event = event
)
