package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal fun mergeProgressProjectionWithRetainedLocal(
    providerEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    retainsLocalProgress: (String) -> Boolean,
    localPlaybackScope: String? = null
): List<WatchProgress> {
    val merged = providerEntries.associateByTo(linkedMapOf(), WatchProgress::projectionKey)
    localEntries.forEach { progress ->
        val key = progress.projectionKey()
        val existing = merged[key]
        val authoredForAccount = localPlaybackScope != null &&
            progress.localPlaybackScope == localPlaybackScope && progress.source == WatchProgress.SOURCE_LOCAL
        if (authoredForAccount && (existing == null || progress.lastWatched > existing.lastWatched) ||
            existing == null && retainsLocalProgress(progress.contentId)) {
            merged[key] = progress
        }
    }
    return merged.values.sortedByDescending(WatchProgress::lastWatched)
}

private fun WatchProgress.projectionKey() = Triple(contentId, season, episode)

internal fun mergeWatchedEpisodeProjection(
    providerEpisodes: Map<String, Set<Pair<Int, Int>>>,
    localItems: List<WatchedItem>,
    retainsLocalWatchedEpisode: (WatchedItem) -> Boolean
): Map<String, Set<Pair<Int, Int>>> {
    val merged = providerEpisodes.mapValuesTo(linkedMapOf()) { (_, episodes) -> episodes.toMutableSet() }
    localItems.forEach { item ->
        val season = item.season
        val episode = item.episode
        if (season != null && episode != null && retainsLocalWatchedEpisode(item)) {
            merged.getOrPut(item.contentId, ::linkedSetOf).add(season to episode)
        }
    }
    return merged
}

/** Exact positive remote evidence, with a newer event allowed to supersede an earlier local state. */
internal fun isConfirmedPlaybackAcknowledgement(local: WatchProgress, remote: WatchProgress): Boolean {
    if (local.localPlaybackScope == null || remote.localPlaybackScope != null ||
        remote.source == WatchProgress.SOURCE_LOCAL ||
        local.contentId != remote.contentId || local.videoId != remote.videoId ||
        !local.contentType.equals(remote.contentType, ignoreCase = true) ||
        local.season != remote.season || local.episode != remote.episode ||
        remote.lastWatched < local.lastWatched) return false
    if (remote.lastWatched > local.lastWatched) return true
    return local.isCompleted() == remote.isCompleted() &&
        kotlin.math.abs(local.progressPercentage - remote.progressPercentage) <= 0.03f
}
