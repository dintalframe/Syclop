package com.nuvio.tv.ui.screens.player

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true
    scope.launch {
        com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants.registry.invalidations.collect {
            if (currentStreamUrl.isNotBlank() &&
                !com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants.registry.permits(currentStreamUrl)) {
                releasePlayer()
                _uiState.update { it.copy(error = com.nuvio.tv.core.streams.SyclopPlaybackPolicy.BLOCKED_MESSAGE,
                    showLoadingOverlay = false, isBuffering = false) }
            }
        }
    }

    val bg = navigationArgs.bingeGroup
    val cid = contentId
    if (cid != null && bg != null) {
        scope.launch(kotlinx.coroutines.NonCancellable) {
            val existingFilename = bingeGroupCacheDataStore.getFilename(cid)
            val existingInfoHash = bingeGroupCacheDataStore.getInfoHash(cid)
            bingeGroupCacheDataStore.replace(cid, bg, existingFilename, existingInfoHash)
        }
    }

    val infoHash = navigationArgs.infoHash
    val clickElapsedMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    queuePlaybackRawEventLine(
        "PLAYER_START_REQUEST: clickElapsedMs=$clickElapsedMs host=${initialStreamUrl.safeStartupHost()} " +
            "contentId=${contentId ?: "n/a"} videoId=${currentVideoId ?: "n/a"} " +
            "S${currentSeason ?: "-"}E${currentEpisode ?: "-"} infoHash=${infoHash != null} " +
            "startFromBeginning=${navigationArgs.startFromBeginning} streamName=${streamName ?: "n/a"}"
    )
    Log.d(
        "PlayerStartup",
        "startInitialPlayback: infoHash=$infoHash host=${currentStreamUrl.safeStartupHost()} " +
            "urlHash=${currentStreamUrl.hashCode().toUInt().toString(16)}"
    )
    if (infoHash != null && !com.nuvio.tv.core.streams.SyclopPlaybackPolicy.isHttps(initialStreamUrl)) {
        _uiState.update { it.copy(error = com.nuvio.tv.core.streams.SyclopPlaybackPolicy.BLOCKED_MESSAGE,
            showLoadingOverlay = false, isBuffering = false) }
        return
    }
    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = !navigationArgs.startFromBeginning
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}

private fun String.safeStartupHost(): String {
    return runCatching {
        android.net.Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}
