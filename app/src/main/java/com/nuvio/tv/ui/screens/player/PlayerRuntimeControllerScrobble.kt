package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.tv.data.local.toTrackPreference
import com.nuvio.tv.core.streams.ServicePlaybackOrigin

internal fun PlayerRuntimeController.preparePlaybackBeforeStart(
    url: String,
    headers: Map<String, String>,
    loadSavedProgress: Boolean
) {
    // Source selection commits the episode identity before preparation. Record that visit even
    // when authorization fails or playback ends before the first frame/progress save.
    preparePlaybackOriginForCurrentOwner()
    currentProgressWriteOwner()?.let(progressWriteCoordinator::onPlaybackOwnerChanged)
    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
    val playbackUrl = playbackRequest.url
    val playbackHeaders = playbackRequest.headers
    if (playbackUrl != currentStreamUrl || playbackHeaders != currentHeaders) {
        currentStreamUrl = playbackUrl
        currentHeaders = playbackHeaders
        _uiState.update { it.copy(currentStreamUrl = playbackUrl) }
    }

    logSwitchTrace(
        stage = "prepare-playback-before-start",
        message = "urlHash=${playbackUrl.hashCode().toUInt().toString(16)} loadSavedProgress=$loadSavedProgress " +
            "clearPendingSwitchPref=true"
    )
    val clickElapsedMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    queuePlaybackRawEventLine(
        "PREPARE_PLAYBACK: clickElapsedMs=$clickElapsedMs host=${playbackUrl.safeScrobbleHost()} " +
            "loadSavedProgress=$loadSavedProgress currentSeason=${currentSeason ?: -1} " +
            "currentEpisode=${currentEpisode ?: -1} streamName=${_uiState.value.currentStreamName ?: "n/a"}"
    )
    clearPendingEngineSwitchTrackPreference()
    playbackPreparationJob?.cancel()
    // Capture before authorization/preferences suspend. Starting from the beginning while these
    // reads are pending invalidates this exact preparation's right to restore saved progress.
    val resumeRequest = resumeRequestGate.beginRequest()
    if (!loadSavedProgress) clearPendingInitialResumePosition()
    val preflightToken = afrPreflightGate.token()
    val owner = currentProgressWriteOwner()
    val progressLease = owner?.let { progressWriteCoordinator.capture(it, completedAtCapture = false) }
    val origin = playbackOrigin
    val selectedVideoId = currentVideoId
    val selectedSeason = currentSeason
    val selectedEpisode = currentEpisode
    val selectedMediaType = contentType
    val infoHash = currentInfoHash
    val fileIndex = currentFileIdx
    fun accountOrigin() = debridSettingsDataStore?.let { settings ->
        ServicePlaybackOrigin(settings.activeProfileId.value, settings.activeProfileSelectionRevision,
            settings.activeCredentialRevision)
    }
    val account = accountOrigin()
    fun sessionIsCurrent() = resumeRequestGate.isCurrent(resumeRequest) &&
        currentProgressWriteOwner() == owner && playbackOrigin === origin &&
        currentVideoId == selectedVideoId && currentSeason == selectedSeason &&
        currentEpisode == selectedEpisode && contentType == selectedMediaType &&
        currentStreamUrl == playbackUrl && currentHeaders == playbackHeaders &&
        currentInfoHash == infoHash && currentFileIdx == fileIndex && accountOrigin() == account &&
        (progressLease == null || progressWriteCoordinator.mayPublish(progressLease))
    suspend fun preparationIsCurrent() = currentCoroutineContext().isActive &&
        afrPreflightGate.isCurrent(preflightToken) && !isInBackground && sessionIsCurrent()
    val resumePreparation: () -> Unit = {
        if (sessionIsCurrent()) preparePlaybackBeforeStart(playbackUrl, playbackHeaders, loadSavedProgress)
    }
    pendingInitializationResume = resumePreparation

    // Fire-and-forget: warm the Trakt episode mapping in the background.
    traktMappingJob?.cancel()
    traktMappingJob = scope.launch {
        warmTraktEpisodeMappingForCurrentPlayback()
    }

    playbackPreparationJob = scope.launch {
        try {
            if (!preparationIsCurrent()) return@launch
            val authorized = directDebridResolver.isPlaybackAuthorized(playbackUrl, profileId,
                selectedVideoId, selectedSeason, selectedEpisode, selectedMediaType)
            if (!preparationIsCurrent()) return@launch
            if (!authorized) {
                _uiState.update { it.copy(error = com.nuvio.tv.core.streams.SyclopPlaybackPolicy.BLOCKED_MESSAGE,
                    showLoadingOverlay = false, isBuffering = false) }
                return@launch
            }
            setLoadingStatus(
                phase = "preparing_metadata",
                message = context.getString(com.nuvio.tv.R.string.player_loading_preparing)
            )
            refreshScrobbleItem()
            if (persistedTrackPreference == null) {
                contentId?.let { id ->
                    val loaded = trackPreferenceDataStore.load(id)?.toTrackPreference()
                    if (!preparationIsCurrent()) return@launch
                    logSwitchTrace(
                        stage = "track-pref-load",
                        message = "contentId=$id loadedAudio=${loaded?.audio?.language}/${loaded?.audio?.name} " +
                            "loadedSubtitle=${loaded?.subtitle?.javaClass?.simpleName ?: "none"}"
                    )
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "TRACK_PREF load: contentId=$id S${currentSeason}E${currentEpisode} " +
                            "result=${if (loaded == null) "null (no saved preference)" else "audio=${loaded.audio?.language}/${loaded.audio?.name} subtitle=${loaded.subtitle?.javaClass?.simpleName}"}"
                    )
                    persistedTrackPreference = loaded
                } ?: Log.d(PlayerRuntimeController.TAG, "TRACK_PREF load: skipped (contentId is null)")
            } else {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "TRACK_PREF load: skipped (persistedTrackPreference already set: " +
                        "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                        "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName})"
                )
                logSwitchTrace(
                    stage = "track-pref-load",
                    message = "skipped=true reason=persisted-already-set " +
                        "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                        "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName ?: "none"}"
                )
            }
            // Per-video delay must still load when a restarted preparation already cached tracks.
            // The renderers factory snapshots this value when initializePlayer builds it.
            selectedVideoId?.takeIf { it.isNotBlank() }?.let { vid ->
                val savedDelayMs = trackPreferenceDataStore.loadSubtitleDelayMs(vid)
                if (!preparationIsCurrent()) return@launch
                if (savedDelayMs != null && savedDelayMs != 0) {
                    subtitleDelayUs.set(savedDelayMs.toLong() * 1000L)
                    _uiState.update { it.copy(subtitleDelayMs = savedDelayMs) }
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "TRACK_PREF load: restored subtitleDelayMs=$savedDelayMs for videoId=$vid"
                    )
                }
            }
            contentId?.takeIf { it.isNotBlank() }?.let { id ->
                val speed = trackPreferenceDataStore.loadPlaybackSpeed(id)
                if (!preparationIsCurrent()) return@launch
                if (speed != null) _uiState.update { it.copy(playbackSpeed = speed) }
            }
            // Load saved watch progress BEFORE player init.
            // This eliminates the race condition where ExoPlayer's STATE_READY
            // callback fired before the DB read completed, causing the resume
            // seek to be silently skipped — the player would start from 0:00
            // or hang in buffering after a late seek.
            val partyPositionMs = partyResumePositionMs()
            if (partyPositionMs != null && resumeRequestGate.isCurrent(resumeRequest)) {
                pendingResumeProgress = null
                _uiState.update { it.copy(pendingSeekPosition = partyPositionMs) }
            } else if (loadSavedProgress) {
                recordLoadingDiagnosticEvent(
                    phase = "loading_saved_progress",
                    message = context.getString(com.nuvio.tv.R.string.player_loading_preparing)
                )
                loadSavedProgressSuspend(selectedSeason, selectedEpisode, resumeRequest)
            }
            if (!preparationIsCurrent()) return@launch
            recordLoadingDiagnosticEvent(
                phase = "initializing_player",
                message = context.getString(com.nuvio.tv.R.string.player_loading_building)
            )
            initializePlayer(playbackUrl, playbackHeaders, startPaused = userPausedManually)
        } finally {
            if (!isInBackground && pendingInitializationResume === resumePreparation) {
                pendingInitializationResume = null
            }
        }
    }
}

private fun String.safeScrobbleHost(): String {
    return runCatching {
        android.net.Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    if (!traktEpisodeMappingService.isTraktAuthenticated()) {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }

    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }

    val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }
    val season = currentSeason ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }
    val episode = currentEpisode ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }

    currentTraktEpisodeMapping = withTimeoutOrNull(12_000L) {
        traktEpisodeMappingService.prefetchEpisodeMapping(
            contentId = resolvedContentId,
            contentType = contentType,
            videoId = currentVideoId,
            season = season,
            episode = episode
        )
    }
    currentTraktEpisodeMappingKey = currentEpisodeMappingCacheKey()
}

internal fun PlayerRuntimeController.currentEpisodeMappingCacheKey(): String? {
    val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val season = currentSeason ?: return null
    val episode = currentEpisode ?: return null
    val videoId = currentVideoId?.trim().orEmpty()
    return "$resolvedType|$resolvedContentId|$videoId|$season|$episode"
}
