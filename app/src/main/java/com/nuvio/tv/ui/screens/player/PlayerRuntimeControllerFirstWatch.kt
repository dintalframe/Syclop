package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.setup.FirstWatchWriteScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.setup.FirstWatchPlaybackObservation
import com.nuvio.tv.core.setup.FirstWatchPlaybackOutcome
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class FirstWatchPlaybackCapture(val scope: FirstWatchWriteScope, val binding: String,
    val url: String, val title: String, val videoId: String) {
    var recorded = false
    override fun toString() = "FirstWatchPlaybackCapture([redacted])"
}

internal fun PlayerRuntimeController.captureFirstWatchPlayback(url: String): FirstWatchPlaybackCapture? {
    val repository = firstWatchReadiness ?: return null
    val config = repository.configuration.value ?: return null
    val captured = repository.captureScope()
    if (isReleasingPlayer || config.profileId != profileId || captured.profileId != profileId ||
        !repository.isCurrent(captured) || !ConnectedServicePlaybackGrants.registry.permits(url)) return null
    val selectedVideo = currentVideoId ?: contentId ?: return null
    return FirstWatchPlaybackCapture(captured, config.binding, url, title, selectedVideo)
}

internal fun PlayerRuntimeController.recordFirstWatchStarted(captured: FirstWatchPlaybackCapture?, engine: String,
    renderedFrame: Boolean = false, requestedMpvProgress: Boolean = false) {
    if (captured == null || captured.recorded ||
        !(engine == "exo" && renderedFrame || engine == "mpv" && requestedMpvProgress)) return
    val repository = firstWatchReadiness ?: return
    fun current() = firstWatchCapture === captured && !isReleasingPlayer && !isInBackground &&
        currentStreamUrl == captured.url && (currentVideoId ?: contentId) == captured.videoId &&
        repository.isCurrent(captured.scope) && repository.configuration.value?.binding == captured.binding &&
        ConnectedServicePlaybackGrants.registry.permits(captured.url)
    if (!current()) return
    captured.recorded = true
    val observation = FirstWatchPlaybackObservation(captured.binding, System.currentTimeMillis(),
        captured.title, captured.videoId, engine, FirstWatchPlaybackOutcome.STARTED)
    scope.launch {
        if (!current()) return@launch
        try { repository.recordPlayback(captured.scope, observation, ::current) }
        catch (error: Exception) { if (error is CancellationException) throw error }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlayerRuntimeController.attachFirstWatchMedia3Listener(player: ExoPlayer, captured: FirstWatchPlaybackCapture?) {
    player.addListener(object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (_exoPlayer === player && !player.isReleased)
                recordFirstWatchStarted(captured, "exo", renderedFrame = true)
        }
    })
}
