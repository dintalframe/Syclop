package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class TraktTrackingProgressProvider @Inject constructor(
    private val service: TraktProgressService,
    private val layoutPreferences: LayoutPreferenceDataStore,
    private val authDataStore: TraktAuthDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.TRAKT
    override val isAuthenticated = authDataStore.isEffectivelyAuthenticated
    override val ownsCompletedHistoryProjection = true
    override val clearsLocalProgressOnSelection = true
    override val allProgress = service.observeAllProgress()
    override val remoteProgressLoaded = service.observeRemoteProgressLoaded()
    override val confirmedProgress = service.observeConfirmedProgress()
    override val localPlaybackScope = authDataStore.state.map(::traktLocalPlaybackScope)

    override suspend fun captureLocalPlaybackScope(profileId: Int): String? {
        val revision = authDataStore.grantRevision(profileId)
        val selection = authDataStore.activeProfileSelectionRevision
        if (revision < 0L || authDataStore.activeProfileId != profileId) return null
        val state = authDataStore.getCurrentState(profileId)
        if (authDataStore.activeProfileId != profileId ||
            selection != authDataStore.activeProfileSelectionRevision ||
            revision != authDataStore.grantRevision(profileId)) return null
        return traktLocalPlaybackScope(state)
    }
    override val watchedMovieIds = service.observeAllWatchedMovieIds()
    override val nextUpSeeds = combine(
        service.observeWatchedShowSeeds(),
        service.observeAllProgress().map { items ->
            val now = System.currentTimeMillis()
            items.filter { progress ->
                progress.source == WatchProgress.SOURCE_TRAKT_HISTORY ||
                    progress.isRecentCompletedPlaybackSeed(now)
            }
        },
        layoutPreferences.nextUpFromFurthestEpisode
    ) { canonical, optimistic, useFurthest ->
        mergeTrackingNextUpSeeds(canonical, optimistic, useFurthest)
    }

    override fun episodeProgress(contentId: String) = service.observeEpisodeProgress(contentId)

    override fun airedEpisodeOrder(contentId: String) = service.observeAiredEpisodes(contentId)

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = if (season != null && episode != null) {
        service.observeEpisodeProgress(contentId).map { progress ->
            progress[season to episode]?.isCompleted() == true
        }
    } else {
        service.observeMovieWatched(contentId, videoId)
    }

    override suspend fun watchedShowEpisodes() = service.getWatchedShowEpisodes()

    override suspend fun showIdSiblings() = service.getShowIdSiblings()

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        if (intent == TrackingRefreshIntent.AUTOMATIC) service.requestAutomaticRefresh()
        else service.refreshNow()
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) =
        service.removeProgress(contentId, season, episode)

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) {
        if (quiet) service.updateOptimisticProgressQuietly(progress) else service.applyOptimisticProgress(progress)
    }

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) =
        service.applyOptimisticRemoval(contentId, season, episode)

    override fun clearOptimistic() = service.clearOptimistic()

    override fun retainsLocalProgress(contentId: String): Boolean =
        shouldRetainTraktLocalProgress(contentId)

    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean =
        shouldRetainTraktLocalWatchedEpisode(item)

    override fun isHiddenFromProgress(contentId: String): Boolean =
        service.isShowHiddenFromProgress(contentId)

    override fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? =
        traktContinueWatchingCutoffEpochMs(daysCap, nowEpochMs)

    override fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean =
        shouldUseTraktNextUpSeed(progress, nowEpochMs)

    override fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
        resolveEffectiveContentId(parentContentId, videoId)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress {
        val season = progress.season ?: return progress
        val episode = progress.episode ?: return progress
        return service.remapEpisodeSeedToAddon(
            contentId = progress.contentId,
            contentType = progress.contentType,
            season = season,
            episode = episode,
            episodeTitle = progress.episodeTitle
        )?.let { remapped ->
            progress.copy(
                season = remapped.season,
                episode = remapped.episode,
                videoId = remapped.videoId ?: progress.videoId
            )
        } ?: progress
    }
}

internal fun shouldRetainTraktLocalProgress(contentId: String): Boolean =
    !isTraktCompatibleId(contentId)

internal fun shouldRetainTraktLocalWatchedEpisode(item: WatchedItem): Boolean =
    item.season != null &&
        item.episode != null &&
        (item.contentType.equals("series", ignoreCase = true) ||
            item.contentType.equals("tv", ignoreCase = true))

internal fun traktContinueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? {
    if (daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) return null
    return nowEpochMs - daysCap.toLong() * 24L * 60L * 60L * 1_000L
}

internal fun shouldUseTraktNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean {
    if (!progress.isCompleted()) return false
    if (progress.source != WatchProgress.SOURCE_TRAKT_PLAYBACK) return true
    val explicitPercent = progress.progressPercent ?: return false
    if (explicitPercent < 95f) return false
    return nowEpochMs - progress.lastWatched in 0..3 * 60_000L
}

private fun WatchProgress.isRecentCompletedPlaybackSeed(nowEpochMs: Long): Boolean {
    if (!contentType.equals("series", ignoreCase = true)) return false
    if (!isCompleted() || source != WatchProgress.SOURCE_TRAKT_PLAYBACK) return false
    if (season == null || episode == null || season == 0) return false
    return nowEpochMs - lastWatched in 0..3 * 60_000L
}

private fun mergeTrackingNextUpSeeds(
    canonical: List<WatchProgress>,
    optimistic: List<WatchProgress>,
    useFurthest: Boolean
): List<WatchProgress> {
    val merged = linkedMapOf<String, WatchProgress>()
    canonical.forEach { seed -> merged[trackingNextUpKey(seed)] = seed }
    optimistic.forEach { seed ->
        val key = trackingNextUpKey(seed)
        val existing = merged[key]
        if (existing == null || shouldReplaceTrackingSeed(existing, seed, useFurthest)) {
            merged[key] = seed
        }
    }
    return merged.values.sortedByDescending(WatchProgress::lastWatched)
}

private fun trackingNextUpKey(progress: WatchProgress): String =
    progress.traktShowId?.let { "trakt_show:$it" } ?: progress.contentId.trim()

private fun shouldReplaceTrackingSeed(
    existing: WatchProgress,
    candidate: WatchProgress,
    useFurthest: Boolean
): Boolean {
    if (!useFurthest) return candidate.lastWatched >= existing.lastWatched
    val candidatePosition = (candidate.season ?: -1) to (candidate.episode ?: -1)
    val existingPosition = (existing.season ?: -1) to (existing.episode ?: -1)
    return candidatePosition.first > existingPosition.first ||
        candidatePosition.first == existingPosition.first &&
        (candidatePosition.second > existingPosition.second ||
            candidatePosition.second == existingPosition.second &&
            candidate.lastWatched >= existing.lastWatched)
}

/** Public identity only; tokens and mutable access-token lifetime never enter the persisted key. */
internal fun traktLocalPlaybackScope(state: com.nuvio.tv.data.local.TraktAuthState): String? {
    if (!state.isAuthenticated) return null
    val slug = state.userSlug?.trim()?.lowercase(java.util.Locale.ROOT)
        ?.takeIf { it.matches(Regex("[a-z0-9_-]{1,100}")) } ?: return null
    val app = state.registrationClientId?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,256}")) } ?: return null
    val identity = "${state.profileId}\n$app\n$slug"
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return "trakt-local-v1:$digest"
}
