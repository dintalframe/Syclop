package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.ActivePlayerFileDuration
import com.nuvio.tv.core.debrid.DirectDebridStreamPreparer
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.data.local.AudioDelayRouteDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val trackingScrobbleCoordinator: TrackingScrobbleCoordinator,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: com.nuvio.tv.data.local.BingeGroupCacheDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val episodeShufflePlayback: com.nuvio.tv.core.player.EpisodeShufflePlayback,
    private val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val trackPreferenceDataStore: com.nuvio.tv.data.local.TrackPreferenceDataStore,
    private val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    private val torrentService: TorrentService,
    private val torrentSettings: TorrentSettings,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val simklRelatedService: com.nuvio.tv.data.simkl.SimklRelatedService,
    private val simklAuthRepository: com.nuvio.tv.data.simkl.SimklAuthRepository,
    private val directDebridResolver: DirectDebridResolver,
    private val youTubeStreamResolver: com.nuvio.tv.core.streams.YouTubeStreamResolver,
    private val directDebridStreamPreparer: DirectDebridStreamPreparer,
    private val cloudLibraryRepository: CloudLibraryRepository,
    private val cloudPlaybackProgressStore: CloudLibraryPlaybackProgressStore,
    private val cloudPlaybackSessionStore: CloudLibraryPlaybackSessionStore,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    private val debridStreamPresentation: com.nuvio.tv.core.debrid.DebridStreamPresentation,
    private val sourcePanelDebridSettings: com.nuvio.tv.data.local.DebridSettingsDataStore,
    private val sourcePanelBitrateMetadata: com.nuvio.tv.core.debrid.DebridBitrateMetadataService,
    private val playbackIssueReportRepository: com.nuvio.tv.data.repository.PlaybackIssueReportRepository,
    private val externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    private val subtitleFileCache: com.nuvio.tv.core.player.SubtitleFileCache,
    private val tvRecommendationManager: com.nuvio.tv.core.recommendations.TvRecommendationManager,
    private val serverPlayback: com.nuvio.tv.data.mediaserver.ServerPlayback,
    private val serverStreams: com.nuvio.tv.data.mediaserver.ServerStreams,
    partyRepository: com.nuvio.tv.core.party.PartyRepository,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    init {
        // Release trailer player codec resources so the full-screen player can
        // claim hardware decoders without contention (prevents black screen).
        trailerPlayerPool.yield()
    }

    internal val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        pluginManager = pluginManager,
        subtitleRepository = subtitleRepository,
        parentalGuideRepository = parentalGuideRepository,
        trackingScrobbleCoordinator = trackingScrobbleCoordinator,
        traktEpisodeMappingService = traktEpisodeMappingService,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        deviceLocalPlayerPreferences = deviceLocalPlayerPreferences,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
        bingeGroupCacheDataStore = bingeGroupCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        episodeShufflePlayback = episodeShufflePlayback,
        trackPreferenceDataStore = trackPreferenceDataStore,
        audioDelayRouteDataStore = audioDelayRouteDataStore,
        torrentService = torrentService,
        torrentSettings = torrentSettings,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        directDebridResolver = directDebridResolver,
        youTubeStreamResolver = youTubeStreamResolver,
        directDebridStreamPreparer = directDebridStreamPreparer,
        cloudLibraryRepository = cloudLibraryRepository,
        cloudPlaybackProgressStore = cloudPlaybackProgressStore,
        cloudPlaybackSessionStore = cloudPlaybackSessionStore,
        streamBadgePresentation = streamBadgePresentation,
        playbackIssueReportRepository = playbackIssueReportRepository,
        tvRecommendationManager = tvRecommendationManager,
        serverPlayback = serverPlayback,
        serverStreams = serverStreams,
        party = partyRepository,
        profileId = savedStateHandle.get<String>("profileId")?.toIntOrNull()
            ?: profileManager.activeProfileId.value,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    init {
        controller.bindParty()
    }

    val party: com.nuvio.tv.core.party.PartyRepository
        get() = controller.party

    val canUseParty: Boolean
        get() = controller.canUseParty

    val partyKey: String
        get() = controller.partyKey

    fun startParty() = controller.startParty()

    fun catchUpParty() = controller.catchUpParty()

    val partyMedia: PartyMedia
        get() = controller.currentPartyMedia()

    fun switchParty() = controller.party.publishMedia(controller.currentPartyMedia(), controller.currentPlaybackPositionMs() ?: 0L)

    fun currentPartyStream() = controller.currentPartyStream()

    private val postPlayRecommendationController = PostPlayRecommendationController(
        playbackController = controller,
        playerSettingsDataStore = playerSettingsDataStore,
        metaRepository = metaRepository,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        mdbListRepository = mdbListRepository,
        mdbListSettingsDataStore = mdbListSettingsDataStore,
        traktRelatedService = traktRelatedService,
        traktAuthDataStore = traktAuthDataStore,
        traktSettingsDataStore = traktSettingsDataStore,
        simklRelatedService = simklRelatedService,
        simklAuthRepository = simklAuthRepository,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchProgressRepository = watchProgressRepository,
        watchedSeriesStateHolder = watchedSeriesStateHolder,
        trailerService = trailerService,
        trailerSettingsDataStore = trailerSettingsDataStore,
        trailerPlayerPool = trailerPlayerPool,
        scope = viewModelScope
    )

    private val sourcePanelProfileId = controller.profileId
    private val sourcePanelSelectionRevision = profileManager.activeProfileSelectionRevision
    private val sourcePanelPresentation = PlayerSourcePanelPresentation { streams, settings ->
        debridStreamPresentation.rankAll(streams, settings)
    }

    private val sourcePanelMetadata = PlayerSourcePanelMetadata { streams, source, profile ->
        sourcePanelBitrateMetadata.enrichKnownFiles(streams, source.durationMillis, source.durationOrigin,
            source.season, source.episode, profile, source.nativeVideoId, source.mediaType, source.activeFileDuration)
    }
    private val sourcePanelActiveFileDuration = MutableStateFlow<ActivePlayerFileDuration?>(null)

    private data class SourcePanelMetadataWork(val request: PlayerSourceMetadataRequest?,
        val settings: com.nuvio.tv.domain.model.DebridSettings) {
        override fun toString() = "SourcePanelMetadataWork([redacted])"
    }

    private fun sourcePanelScopeCurrent() = profileManager.activeProfileId.value == sourcePanelProfileId &&
        profileManager.activeProfileSelectionRevision == sourcePanelSelectionRevision &&
        sourcePanelDebridSettings.activeProfileId.value == sourcePanelProfileId

    private fun sourceMetadataRequest(state: PlayerUiState): PlayerSourceMetadataRequest? {
        if (!state.showSourcesPanel || state.sourceAllStreams.isEmpty() || !sourcePanelScopeCurrent() ||
            controller.uiState.value !== state) return null
        val selected = controller.currentSourceStreamsRequest() ?: return null
        val sourceKey = controller.buildSourceRequestKey(selected.type, selected.videoId, selected.season, selected.episode)
        if (controller.sourceStreamsCacheRequestKey != sourceKey) return null
        val credentialRevision = sourcePanelDebridSettings.activeCredentialRevision
        if (credentialRevision < 0L) return null
        val cached = controller.contentId?.let { metaRepository.getCachedMeta(selected.type, it) }
        val active = sourcePanelActiveFileDuration.value?.takeIf {
            it.matches(sourcePanelProfileId, sourcePanelDebridSettings.activeProfileSelectionRevision, credentialRevision,
                selected.videoId, selected.type, selected.season, selected.episode, state.currentStreamUrl)
        }?.copy(sourceKey = PlayerSourcePanelMetadata.currentSourceKey(state))
        val source = PlayerSourcePanelMetadata.context(selected.type, selected.videoId, selected.season,
            selected.episode, controller.contentId, cached).copy(activeFileDuration = active)
        return PlayerSourceMetadataRequest(source, state.sourceAllStreams, sourcePanelProfileId,
            sourcePanelDebridSettings.activeProfileSelectionRevision, credentialRevision, sourceKey,
            streamInfoMediaOriginKey(state.currentStreamUrl))
    }

    private fun sourceMetadataCurrent(request: PlayerSourceMetadataRequest): Boolean {
        if (!sourcePanelScopeCurrent() || sourcePanelDebridSettings.activeProfileSelectionRevision != request.selectionRevision ||
            sourcePanelDebridSettings.activeCredentialRevision != request.credentialRevision || request.credentialRevision < 0L) return false
        return sourceMetadataRequest(controller.uiState.value) == request
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sourceMetadataFacts: StateFlow<PlayerSourceMetadataSnapshot> = combine(controller.uiState,
        sourcePanelDebridSettings.settings, profileManager.activeProfileId, sourcePanelActiveFileDuration) { state, settings, _, _ ->
        SourcePanelMetadataWork(sourceMetadataRequest(state), settings)
    }.distinctUntilChanged().transformLatest { work ->
        emit(PlayerSourceMetadataSnapshot())
        val request = work.request ?: return@transformLatest
        emit(sourcePanelMetadata.enrich(request, { streams ->
            debridStreamPresentation.rankAll(streams, work.settings, enforceLimits = false)
        }) { sourceMetadataCurrent(request) })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, PlayerSourceMetadataSnapshot())

    // Project only source-list presentation: controller/resolver inputs and native episode IDs
    // remain unchanged. Apply the shared global ranking before addon filtering and pagination,
    // including cached, prepared and incrementally badged results.
    val uiState: StateFlow<PlayerUiState> = combine(
        controller.uiState,
        sourcePanelDebridSettings.settings,
        sourceMetadataFacts
    ) { state, settings, facts ->
        val request = sourceMetadataRequest(state)
        val shown = if (request != null && sourceMetadataCurrent(request)) state.copy(sourceAllStreams =
            sourcePanelMetadata.displayStreams(request, facts.takeIf { it.request == request } ?: PlayerSourceMetadataSnapshot())) else state
        sourcePanelPresentation.apply(shown, settings) {
            profileManager.activeProfileId.value == sourcePanelProfileId &&
                profileManager.activeProfileSelectionRevision == sourcePanelSelectionRevision
        }
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PlayerSourcePanelPresentation.withoutSources(controller.uiState.value)
    )

    val playbackTimeline: StateFlow<PlaybackTimelineState>
        get() = controller.playbackTimeline

    val postPlayRecommendationUiState: StateFlow<PostPlayRecommendationUiState>
        get() = postPlayRecommendationController.uiState

    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun getCurrentFileSizeBytes(): Long? = controller.currentVideoSize

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getPlayerNativeMemoryBytes(): Long? {
        val allocator = controller._loadControl?.allocator as? androidx.media3.exoplayer.upstream.DefaultAllocator ?: return null
        return allocator.totalBytesAllocated.toLong().coerceAtLeast(0L)
    }

    fun stopAndRelease() {
        postPlayRecommendationController.stop()
        controller.stopAndRelease()
    }

    fun playPostPlayTrailer() {
        postPlayRecommendationController.playTrailer()
    }

    fun onPostPlayTrailerEnded() {
        postPlayRecommendationController.onTrailerEnded()
    }

    fun showPreviousPostPlayRecommendation() {
        postPlayRecommendationController.showPreviousRecommendation()
    }

    fun showNextPostPlayRecommendation() {
        postPlayRecommendationController.showNextRecommendation()
    }

    fun returnToPlayerFromPostPlay() {
        postPlayRecommendationController.returnToPlayer()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun attachMpvView(view: NuvioMpvSurfaceView?) {
        controller.attachMpvView(view)
    }

    fun pauseForLifecycle() {
        controller.pauseForLifecycle()
    }

    fun resumeForLifecycle() {
        controller.resumeForLifecycle()
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        if (event == PlayerEvent.OnShowSourcesPanel) {
            // Native player getters require the UI thread. One capture, never a timer/probe.
            viewModelScope.launch(Dispatchers.Main.immediate) {
                sourcePanelActiveFileDuration.value = captureSourcePanelDuration()
                controller.onEvent(event)
            }
            return
        }
        if (event == PlayerEvent.OnDismissSourcesPanel || event is PlayerEvent.OnSourceStreamSelected ||
            event is PlayerEvent.OnEpisodeStreamSelected) sourcePanelActiveFileDuration.value = null
        controller.onEvent(event)
    }

    private fun captureSourcePanelDuration(): ActivePlayerFileDuration? {
        if (!sourcePanelScopeCurrent()) return null
        val state = controller.uiState.value
        val source = controller.currentSourceStreamsRequest() ?: return null
        val url = state.currentStreamUrl?.takeIf { it == controller.currentStreamUrl } ?: return null
        val digest = streamInfoMediaOriginKey(url) ?: return null
        val selection = sourcePanelDebridSettings.activeProfileSelectionRevision
        val credentials = sourcePanelDebridSettings.activeCredentialRevision
        if (credentials < 0L || state.isLive) return null
        val engine = controller.currentInternalPlayerEngine
        val duration = runCatching {
            when (engine) {
                com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER -> {
                    val player = controller._exoPlayer ?: return null
                    if (player.currentMediaItem?.localConfiguration?.uri?.toString() != url || player.isCurrentMediaItemLive) return null
                    player.duration.takeIf { controller._exoPlayer === player &&
                        player.currentMediaItem?.localConfiguration?.uri?.toString() == url }
                }
                com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER -> {
                    val view = controller.mpvView ?: return null
                    if (!view.isRequestedMediaActive(url) || view.isLiveStreamNow()) return null
                    view.durationMs().takeIf { controller.mpvView === view && view.isRequestedMediaActive(url) }
                }
                else -> null
            }
        }.getOrNull()?.takeIf { it > 0L } ?: return null
        if (controller.currentInternalPlayerEngine != engine || controller.currentStreamUrl != url ||
            controller.uiState.value !== state || controller.currentSourceStreamsRequest() != source ||
            !sourcePanelScopeCurrent() || sourcePanelDebridSettings.activeProfileSelectionRevision != selection ||
            sourcePanelDebridSettings.activeCredentialRevision != credentials) return null
        return ActivePlayerFileDuration(sourcePanelProfileId, selection, credentials, source.videoId, source.type,
            source.season, source.episode, digest, duration)
    }

    fun playerMediaInfo(): PlayerMediaInfo = controller.playerMediaInfo()

    fun bindExoSubtitleView(subtitleView: androidx.media3.ui.SubtitleView?) {
        controller.bindExoSubtitleView(subtitleView)
    }

    fun consumePendingExitReason() {
        controller.consumePendingExitReason()
    }

    override fun onCleared() {
        postPlayRecommendationController.stop()
        controller.onCleared()
        // Allow the trailer player to be re-created when returning to home screen.
        trailerPlayerPool.reclaim()
        super.onCleared()
    }

    /**
     * Save watch progress returned by an external player after "Open in External Player".
     * Uses the controller's current content metadata (contentId, season, episode, etc.)
     * which are still available since the controller hasn't been cleared yet.
     */
    fun saveExternalPlayerProgress(positionMs: Long, durationMs: Long?) {
        val effectiveDuration = durationMs ?: controller.playbackTimeline.value.duration
        controller.saveWatchProgressInternal(
            position = positionMs,
            duration = effectiveDuration
        )
    }

    /**
     * Launch the current stream in an external player via the centralized tracker.
     *
     * Keep the ViewModel alive until the external intent has been handed to the launcher.
     * This lets the caller navigate away only after a successful handoff, while failures
     * remain visible on the current player screen (#2560).
     */
    fun launchInExternalPlayer(
        activityContext: Context,
        resumePositionMs: Long,
        onResult: (Boolean) -> Unit
    ) {
        val url = controller.getCurrentStreamUrl()
        if (url.isBlank()) {
            onResult(false)
            return
        }
        val contentId = controller.contentId
            ?: controller.cloudPlaybackContext?.item?.stableKey
            ?: run {
            onResult(false)
            return
        }
        val videoId = controller.currentVideoId ?: contentId
        val metadata = com.nuvio.tv.core.player.ExternalPlaybackMetadata(
            contentId = contentId,
            contentType = controller.contentType ?: "movie",
            contentName = controller.contentName ?: controller.title,
            poster = controller.poster,
            backdrop = controller.backdrop,
            logo = controller.logo,
            videoId = videoId,
            season = controller.currentSeason,
            episode = controller.currentEpisode,
            episodeTitle = controller.currentEpisodeTitle,
            year = controller.year,
            profileId = controller.profileId
        )
        val headers = controller.getCurrentHeaders().toMap()
        val selectionRevision = profileManager.activeProfileSelectionRevision
        val cloudSessionToken = controller.cloudSessionToken
        // Capture already-loaded addon subtitles before handing off. Preparation stays in the
        // ViewModel scope because the player screen remains alive until the intent is sent.
        val subtitleInputs = if (controller.uiState.value.subtitleStyle.preferredLanguage.trim().lowercase() != "none") {
            val addonSubtitles = controller.uiState.value.addonSubtitles
            if (addonSubtitles.isNotEmpty()) {
                addonSubtitles.map {
                    com.nuvio.tv.core.player.SubtitleInput(
                        url = it.url,
                        name = "${it.getDisplayLanguage()} - ${it.addonName}",
                        lang = it.lang
                    )
                }
            } else null
        } else null

        viewModelScope.launch {
            val requestCurrent = {
                isActive && profileManager.activeProfileId.value == metadata.profileId &&
                    profileManager.activeProfileSelectionRevision == selectionRevision &&
                    controller.getCurrentStreamUrl() == url &&
                    (controller.currentVideoId ?: contentId) == videoId &&
                    com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants.registry.permits(url)
            }
            var cachedSubtitles: List<com.nuvio.tv.core.player.SubtitleInput>? = null
            var nextEpisodeSnapshot: com.nuvio.tv.core.player.ExternalNextEpisodeSnapshot? = null
            val launched = try {
                com.nuvio.tv.core.player.ExternalPlayerHandoff.run(
                    isCurrent = requestCurrent,
                    preflight = {
                        val settings = playerSettingsDataStore.playerSettings.first()
                        if (!requestCurrent()) false else try {
                            // Builds but never sends an intent: validates the installed handler,
                            // header contract and native grant before downloads/tracking/teardown.
                            com.nuvio.tv.core.player.ExternalPlayerIntentFactory.create(
                                activityContext,
                                com.nuvio.tv.core.player.ExternalPlayerInput(
                                    url = url, headers = headers, playerPackage = settings.externalPlayerPackage
                                )
                            )
                            true
                        } catch (rejected: com.nuvio.tv.core.player.ExternalPlayerLaunchRejected) {
                            Toast.makeText(activityContext, rejected.reason.userMessage, Toast.LENGTH_LONG).show()
                            false
                        }
                    },
                    prepare = {
                        cachedSubtitles = subtitleInputs?.let { inputs ->
                            try {
                                withTimeoutOrNull(10_000L) { subtitleFileCache.cacheSubtitles(inputs) }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        }
                        nextEpisodeSnapshot = controller.metaVideos.takeIf { it.isNotEmpty() }?.let {
                            externalPlaybackTracker.resolveNextEpisodeSnapshot(metadata, it)
                        }
                    },
                    launch = {
                        externalPlaybackTracker.launchPlayer(
                            metadata = metadata,
                            url = url,
                            title = metadata.buildPlayerTitle(),
                            headers = headers,
                            resumePositionMs = controller.playbackTimeline.value.currentPosition.takeIf { it > 0L } ?: resumePositionMs,
                            subtitles = cachedSubtitles,
                            nextEpisodeSnapshot = nextEpisodeSnapshot,
                            cloudSessionToken = cloudSessionToken,
                            context = activityContext,
                            launchStillCurrent = requestCurrent
                        )
                    },
                    releaseInternal = {
                        // The external Activity has accepted the intent. Failed/cancelled
                        // preparation or rejected launch never tears down internal playback.
                        try { controller.stopAndRelease() }
                        catch (_: Exception) { Log.w("PlayerHandoff", "Internal cleanup failed after accepted external handoff") }
                    }
                )
            } catch (cancelled: CancellationException) {
                onResult(false)
                throw cancelled
            } catch (_: Exception) {
                false
            }
            onResult(launched)
        }
    }
}
