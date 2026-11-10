package com.nuvio.tv.ui.screens.player

// The real preparation runs; native initialization, service API and local preference reads are boundaries.
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.nuvio.tv.core.party.PartyPlayback
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.core.tracking.TrackingPlaybackOrigin
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PersistedTrackPreference
import com.nuvio.tv.data.local.TrackPreferenceDataStore
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPreparationContinuationTest {
    @After fun cleanup() {
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerInitializationKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerLoadingDiagnosticsKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTorrentKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerPlaybackEventsKt")
    }

    @Test fun `canceled outgoing authorization cannot replace the current screen with its rejection`() = runTest {
        val f = fixture(Phase.AUTH, authorized = false)
        f.begin()
        val outgoing = checkNotNull(f.controller.playbackPreparationJob)
        outgoing.cancel()
        assertTrue(outgoing.isCancelled)
        f.controller.cancelAfrPreflight()
        f.controller.resumeRequestGate.beginRequest() // The replacement preparation owns a newer token.
        f.video.value = "tt-prep:1:3"
        f.ui.value = f.ui.value.copy(error = "Current source screen", loadingMessage = "Current source")
        val current = f.ui.value

        f.release.complete(Unit)
        runCurrent()
        assertEquals(1L, f.completedLateBoundaries.get())

        assertEquals(current, f.ui.value)
        assertTrue(f.initializations.isEmpty())
    }

    @Test fun `a canceled track read cannot replace new preferences or initialize old media under a new episode`() = runTest {
        val f = fixture(Phase.TRACKS)
        f.begin()
        val outgoing = checkNotNull(f.controller.playbackPreparationJob)
        outgoing.cancel()
        assertTrue(outgoing.isCancelled)
        f.controller.cancelAfrPreflight()
        f.controller.resumeRequestGate.beginRequest() // The replacement preparation owns a newer token.
        f.video.value = "tt-prep:1:3"
        f.url.value = "https://owned.example.test/episode3.mp4"
        val currentPreference = PlayerRuntimeController.TrackPreference(
            audio = PlayerRuntimeController.RememberedTrackSelection("en", "English", "audio-en"))
        f.controller.persistedTrackPreference = currentPreference

        f.release.complete(Unit)
        runCurrent()
        assertEquals(1L, f.completedLateBoundaries.get())

        assertSame(currentPreference, f.controller.persistedTrackPreference)
        assertTrue(f.initializations.isEmpty())
    }

    @Test fun `a canceled subtitle delay read cannot overwrite the replacement video delay`() = runTest {
        val f = fixture(Phase.DELAY)
        f.begin()
        val outgoing = checkNotNull(f.controller.playbackPreparationJob)
        outgoing.cancel()
        assertTrue(outgoing.isCancelled)
        f.controller.cancelAfrPreflight()
        f.controller.resumeRequestGate.beginRequest() // The replacement preparation owns a newer token.
        f.video.value = "tt-prep:1:3"
        f.ui.value = f.ui.value.copy(subtitleDelayMs = 25)
        f.controller.subtitleDelayUs.set(25_000L)

        f.release.complete(Unit)
        runCurrent()
        assertEquals(1L, f.completedLateBoundaries.get())

        assertEquals(25, f.ui.value.subtitleDelayMs)
        assertEquals(25_000L, f.controller.subtitleDelayUs.get())
        assertTrue(f.initializations.isEmpty())
    }

    @Test fun `a canceled playback speed read cannot change the replacement session speed`() = runTest {
        val f = fixture(Phase.SPEED)
        f.begin()
        val outgoing = checkNotNull(f.controller.playbackPreparationJob)
        outgoing.cancel()
        assertTrue(outgoing.isCancelled)
        f.controller.cancelAfrPreflight()
        f.controller.resumeRequestGate.beginRequest() // The replacement preparation owns a newer token.
        f.video.value = "tt-prep:1:3"
        f.ui.value = f.ui.value.copy(playbackSpeed = 2f)

        f.release.complete(Unit)
        runCurrent()
        assertEquals(1L, f.completedLateBoundaries.get())

        assertEquals(2f, f.ui.value.playbackSpeed)
        assertTrue(f.initializations.isEmpty())
    }

    @Test fun `preparation returning after background and foreground preserves paused intent`() = runTest {
        val f = fixture(Phase.AUTH)
        f.begin()
        f.controller.pauseForLifecycle()
        f.controller.progressWriteCoordinator.deactivateForeground()
        f.controller.progressWriteCoordinator.activateForeground(f.controller.currentProgressWriteOwner())
        f.controller.resumeForLifecycle()
        runCurrent()

        f.release.complete(Unit)
        runCurrent()

        val handoff = f.initializations.single()
        assertEquals(2L, f.authorizations.get())
        assertEquals("https://owned.example.test/episode2.mp4", handoff.url)
        assertEquals("tt-prep:1:2", handoff.videoId)
        assertTrue(handoff.startPaused)
        assertSame(f.origin, handoff.origin)
    }

    @Test fun `foreground restart restores video delay after track preference has already loaded`() = runTest {
        val f = fixture(Phase.DELAY, holdFirstBoundaryOnly = true)
        f.begin()
        assertEquals("tr", f.controller.persistedTrackPreference?.audio?.language)
        assertEquals(PlayerRuntimeController.RememberedSubtitleSelection.Disabled,
            f.controller.persistedTrackPreference?.subtitle)
        assertTrue(f.initializations.isEmpty())

        f.controller.pauseForLifecycle()
        f.controller.progressWriteCoordinator.deactivateForeground()
        f.controller.progressWriteCoordinator.activateForeground(f.controller.currentProgressWriteOwner())
        f.controller.resumeForLifecycle()
        runCurrent()

        val handoff = f.initializations.single()
        assertEquals(2L, f.authorizations.get())
        assertEquals("https://owned.example.test/episode2.mp4", handoff.url)
        assertEquals("tt-prep:1:2", handoff.videoId)
        assertEquals(PlayerProgressWriteOwner(7, "tt-prep", "series", "tt-prep:1:2", 1, 2), handoff.owner)
        assertSame(f.origin, handoff.origin)
        assertTrue(handoff.startPaused)
        assertEquals(1500, handoff.subtitleDelayMs)
        assertEquals(1_500_000L, handoff.subtitleDelayUs)

        // A current-session timing edit must survive the outgoing noncooperative read's return.
        f.ui.value = f.ui.value.copy(subtitleDelayMs = 25)
        f.controller.subtitleDelayUs.set(25_000L)
        f.release.complete(Unit)
        runCurrent()

        assertEquals(1L, f.completedLateBoundaries.get())
        assertEquals(25, f.ui.value.subtitleDelayMs)
        assertEquals(25_000L, f.controller.subtitleDelayUs.get())
        assertEquals(1, f.initializations.size)
    }

    @Test fun `episode preparation resumes paused even while the stopped outgoing Exo is retained`() = runTest {
        // switchToEpisodeStream stops the outgoing engine but retains it until the new initializer.
        val outgoing = mockk<ExoPlayer>(relaxed = true)
        every { outgoing.playbackState } returns Player.STATE_IDLE
        every { outgoing.playWhenReady } returns false
        val f = fixture(Phase.AUTH, retainedOutgoingPlayer = outgoing)
        // Keep the fallback path from constructing a native MediaSession; that is not this boundary.
        every { f.controller.currentMediaSession } returns mockk<MediaSession>(relaxed = true)
        f.begin()
        f.controller.pauseForLifecycle()
        f.controller.progressWriteCoordinator.deactivateForeground()
        f.controller.progressWriteCoordinator.activateForeground(f.controller.currentProgressWriteOwner())
        f.controller.resumeForLifecycle()
        runCurrent()

        f.release.complete(Unit)
        runCurrent()

        assertEquals(2L, f.authorizations.get())
        val handoff = f.initializations.single()
        assertEquals("https://owned.example.test/episode2.mp4", handoff.url)
        assertEquals("tt-prep:1:2", handoff.videoId)
        assertEquals(PlayerProgressWriteOwner(7, "tt-prep", "series", "tt-prep:1:2", 1, 2), handoff.owner)
        assertTrue(handoff.startPaused)
        assertSame(f.origin, handoff.origin)
    }

    @Test fun `foreground return retains an existing Exo when no preparation is pending`() = runTest {
        val player = mockk<ExoPlayer>(relaxed = true)
        val f = fixture(Phase.AUTH, retainedOutgoingPlayer = player)
        every { f.controller.currentMediaSession } returns mockk<MediaSession>(relaxed = true)

        f.controller.pauseForLifecycle()
        f.controller.progressWriteCoordinator.deactivateForeground()
        f.controller.progressWriteCoordinator.activateForeground(f.controller.currentProgressWriteOwner())
        f.controller.resumeForLifecycle()
        runCurrent()

        assertSame(player, f.controller._exoPlayer)
        assertTrue(f.controller.userPausedManually)
        assertEquals(0L, f.authorizations.get())
        assertTrue(f.initializations.isEmpty())
        verify(exactly = 1) { player.setAudioAttributes(any(), true) }
        verify(exactly = 1) { player.pause() }
    }

    @Test fun `current preparation restores local choices and hands the selected file to initialization`() = runTest {
        val f = fixture(Phase.TRACKS)
        f.begin()
        f.release.complete(Unit)
        runCurrent()

        assertEquals("tr", f.controller.persistedTrackPreference?.audio?.language)
        assertEquals(1500, f.ui.value.subtitleDelayMs)
        assertEquals(1_500_000L, f.controller.subtitleDelayUs.get())
        assertEquals(1.25f, f.ui.value.playbackSpeed)
        val handoff = f.initializations.single()
        assertEquals("https://owned.example.test/episode2.mp4", handoff.url)
        assertEquals("tt-prep:1:2", handoff.videoId)
        assertEquals(false, handoff.startPaused)
        assertEquals(PlayerProgressWriteOwner(7, "tt-prep", "series", "tt-prep:1:2", 1, 2), handoff.owner)
        assertSame(f.origin, handoff.origin)
    }

    @Test fun `selecting a different episode with no saved delay does not inherit the outgoing calibration`() = runTest {
        val handoff = selectedEpisodeHandoff(savedDelayMs = null)

        assertEquals("UI=${handoff.subtitleDelayMs}, atomic=${handoff.subtitleDelayUs}",
            0, handoff.subtitleDelayMs)
        assertEquals(0L, handoff.subtitleDelayUs)
    }

    @Test fun `selecting a different episode with a saved zero delay does not inherit the outgoing calibration`() = runTest {
        val handoff = selectedEpisodeHandoff(savedDelayMs = 0)

        assertEquals("UI=${handoff.subtitleDelayMs}, atomic=${handoff.subtitleDelayUs}",
            0, handoff.subtitleDelayMs)
        assertEquals(0L, handoff.subtitleDelayUs)
    }

    @Test fun `selecting a different episode restores that videos own nonzero delay`() = runTest {
        val handoff = selectedEpisodeHandoff(savedDelayMs = 250)

        assertEquals(250, handoff.subtitleDelayMs)
        assertEquals(250_000L, handoff.subtitleDelayUs)
    }

    @Test fun `same video preparation with no saved delay retains its live calibration after tracks are cached`() = runTest {
        val f = fixture(Phase.AUTH)
        f.ui.value = f.ui.value.copy(subtitleDelayMs = 1500)
        f.controller.subtitleDelayUs.set(1_500_000L)
        f.controller.persistedTrackPreference = PlayerRuntimeController.TrackPreference(
            audio = PlayerRuntimeController.RememberedTrackSelection("tr", "Turkish", "audio-tr"))
        val delayStore = f.controller.trackPreferenceDataStore
        coEvery { delayStore.loadSubtitleDelayMs("tt-prep:1:2") } returns null

        f.begin()
        f.release.complete(Unit)
        runCurrent()

        val handoff = f.initializations.single()
        assertEquals("tt-prep:1:2", handoff.videoId)
        assertEquals(PlayerProgressWriteOwner(7, "tt-prep", "series", "tt-prep:1:2", 1, 2), handoff.owner)
        assertEquals(1500, handoff.subtitleDelayMs)
        assertEquals(1_500_000L, handoff.subtitleDelayUs)
        coVerify(exactly = 1) { delayStore.loadSubtitleDelayMs("tt-prep:1:2") }
    }

    private fun TestScope.selectedEpisodeHandoff(savedDelayMs: Int?): Handoff {
        val outgoing = mockk<ExoPlayer>(relaxed = true)
        val f = fixture(Phase.AUTH, retainedOutgoingPlayer = outgoing)
        val target = Video(id = "tt-prep:1:3", title = "Episode 3", released = null,
            thumbnail = null, season = 1, episode = 3, overview = null)
        val selected = Stream(name = "Owned next episode", title = null, description = null,
            url = "https://owned.example.test/episode3.mp4", ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null, behaviorHints = null,
            addonName = "Owned fixture", addonLogo = null)
        // Real event/switch/preparation; native engine and unrelated metadata/cache work are boundaries.
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTorrentKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerPlaybackEventsKt")
        every { f.controller.stopTorrentStream() } just Runs
        every { f.controller.flushPlaybackSnapshotForSwitchOrExit() } just Runs
        every { f.controller.applyStreamAudioDelay(any(), any()) } just Runs
        every { f.controller.recomputeNextEpisode(any()) } just Runs
        every { f.controller.updateEpisodeDescription() } just Runs
        every { f.controller.showStreamSourceIndicator(any()) } just Runs
        every { f.controller.skipIntroEnabled } returns false
        every { f.controller.parentalGuideEnabled } returns false
        every { f.controller.isServerStream } returns false
        every { f.controller.nextEpisodeAutoPlayJob } returns null
        every { f.controller.stillWatchingPromptJob } returns null
        every { f.controller.autoSkippedIntervalKeys } returns mutableSetOf()
        var videoId: String? = "tt-prep:1:2"
        var season: Int? = 1
        var episode: Int? = 2
        var episodeTitle: String? = "Episode 2"
        var headers = emptyMap<String, String>()
        var filename: String? = "Owned.S01E02.mp4"
        var mime: String? = androidx.media3.common.MimeTypes.VIDEO_MP4
        every { f.controller.currentVideoId } answers { videoId }
        every { f.controller.currentVideoId = any() } answers { videoId = firstArg() }
        every { f.controller.currentSeason } answers { season }
        every { f.controller.currentSeason = any() } answers { season = firstArg() }
        every { f.controller.currentEpisode } answers { episode }
        every { f.controller.currentEpisode = any() } answers { episode = firstArg() }
        every { f.controller.currentEpisodeTitle } answers { episodeTitle }
        every { f.controller.currentEpisodeTitle = any() } answers { episodeTitle = firstArg() }
        every { f.controller.currentHeaders } answers { headers }
        every { f.controller.currentHeaders = any() } answers { headers = firstArg() }
        every { f.controller.currentFilename } answers { filename }
        every { f.controller.currentFilename = any() } answers { filename = firstArg() }
        every { f.controller.currentStreamMimeType } answers { mime }
        every { f.controller.currentStreamMimeType = any() } answers { mime = firstArg() }
        val cacheProfile = mockk<ProfileManager>()
        every { cacheProfile.activeProfileId } returns MutableStateFlow(7)
        val cache = spyk(BingeGroupCacheDataStore(mockk(relaxed = true), cacheProfile))
        coEvery { cache.replace(any(), any(), any(), any(), any(), any()) } just Runs
        every { f.controller.bingeGroupCacheDataStore } returns cache
        every { f.controller.watchProgressRepository.getEpisodeProgress("tt-prep", 1, 3, 7) } returns
            flowOf<WatchProgress?>(null)
        val delayStore = f.controller.trackPreferenceDataStore
        coEvery { delayStore.loadSubtitleDelayMs(any()) } coAnswers {
            check(firstArg<String>() == target.id)
            savedDelayMs
        }
        f.ui.value = f.ui.value.copy(subtitleDelayMs = 1500, episodes = listOf(target),
            episodeStreamsForVideoId = target.id, episodeStreamsSeason = 1, episodeStreamsEpisode = 3,
            episodeStreamsTitle = target.title)
        f.controller.subtitleDelayUs.set(1_500_000L)
        assertEquals("tt-prep:1:2", f.controller.currentVideoId)
        assertEquals(1500, f.ui.value.subtitleDelayMs)
        assertEquals(1_500_000L, f.controller.subtitleDelayUs.get())

        f.controller.onEvent(PlayerEvent.OnEpisodeStreamSelected(selected))
        runCurrent()
        assertTrue("Real preparation must reach authorization", f.entered.isCompleted)
        f.release.complete(Unit)
        runCurrent()

        val handoff = f.initializations.single()
        assertEquals(target.id, f.controller.currentVideoId)
        assertEquals(target.id, f.ui.value.currentVideoId)
        assertEquals(1, f.ui.value.currentSeason)
        assertEquals(3, f.ui.value.currentEpisode)
        assertEquals("https://owned.example.test/episode3.mp4", handoff.url)
        assertEquals(target.id, handoff.videoId)
        assertEquals(PlayerProgressWriteOwner(7, "tt-prep", "series", target.id, 1, 3), handoff.owner)
        assertEquals(1L, f.authorizations.get())
        assertSame(f.controller.playbackOrigin, handoff.origin)
        assertTrue("A different video must have its own captured playback origin", handoff.origin !== f.origin)
        val lease = checkNotNull(f.controller.progressWriteCoordinator.capture(
            checkNotNull(handoff.owner), completedAtCapture = false))
        assertTrue(f.controller.progressWriteCoordinator.mayPublish(lease))
        coVerify(exactly = 1) { f.controller.directDebridResolver.isPlaybackAuthorized(
            "https://owned.example.test/episode3.mp4", 7, target.id, 1, 3, "series") }
        coVerify(exactly = 1) { delayStore.loadSubtitleDelayMs(target.id) }
        return handoff
    }

    private enum class Phase { AUTH, TRACKS, DELAY, SPEED }
    private data class Handoff(val url: String, val videoId: String?, val startPaused: Boolean,
        val owner: PlayerProgressWriteOwner?, val origin: Deferred<TrackingPlaybackOrigin>,
        val subtitleDelayMs: Int, val subtitleDelayUs: Long)
    private class Fixture(val controller: PlayerRuntimeController, val ui: MutableStateFlow<PlayerUiState>,
        val video: MutableStateFlow<String>, val url: MutableStateFlow<String>, val release: CompletableDeferred<Unit>,
        val entered: CompletableDeferred<Unit>, val initializations: MutableList<Handoff>, val scope: TestScope,
        val completedLateBoundaries: AtomicLong, val origin: Deferred<TrackingPlaybackOrigin>,
        val authorizations: AtomicLong) {
        fun begin() {
            controller.preparePlaybackBeforeStart(url.value, emptyMap(), loadSavedProgress = false)
            scope.runCurrent()
            assertTrue(entered.isCompleted)
        }
    }

    private fun TestScope.fixture(phase: Phase, authorized: Boolean = true,
        retainedOutgoingPlayer: ExoPlayer? = null, holdFirstBoundaryOnly: Boolean = false): Fixture {
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerInitializationKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerLoadingDiagnosticsKt")
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val preferences = mockk<TrackPreferenceDataStore>()
        val ui = MutableStateFlow(PlayerUiState(currentVideoId = "tt-prep:1:2", currentSeason = 1,
            currentEpisode = 2, showControls = true))
        val video = MutableStateFlow("tt-prep:1:2")
        val url = MutableStateFlow("https://owned.example.test/episode2.mp4")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completedLateBoundaries = AtomicLong()
        val authorizations = AtomicLong()
        var pendingInitialization: (() -> Unit)? = null
        var preparation: Job? = null
        var mapping: Job? = null
        var persisted: PlayerRuntimeController.TrackPreference? = null
        var background = false
        var paused = false
        val origin = CompletableDeferred(TrackingPlaybackOrigin(7, emptyMap(), "fixture-account-A"))
        every { controller._uiState } returns ui
        every { controller.scope } returns backgroundScope
        every { controller.context.getString(any()) } returns "Preparing owned media"
        every { controller.profileId } returns 7
        every { controller.contentId } returns "tt-prep"
        every { controller.contentType } returns "series"
        every { controller.currentVideoId } answers { video.value }
        every { controller.currentSeason } returns 1
        every { controller.currentEpisode } answers { video.value.substringAfterLast(':').toInt() }
        every { controller.currentStreamUrl } answers { url.value }
        every { controller.currentStreamUrl = any() } answers { url.value = firstArg() }
        every { controller.currentHeaders } returns emptyMap()
        PlayerRuntimeController::class.java.getDeclaredField("playbackOrigin").apply { isAccessible = true }
            .set(controller, origin)
        every { controller.playbackOrigin } answers { callOriginal() }
        every { controller.playbackOrigin = any() } answers { callOriginal() }
        every { controller.preparePlaybackOriginForCurrentOwner() } answers { callOriginal() }
        every { controller.capturePlaybackOrigin() } returns CompletableDeferred(
            TrackingPlaybackOrigin(7, emptyMap(), "replacement-fixture-scope"))
        every { controller.currentScrobbleItem } returns null
        every { controller.resumeRequestGate } returns PlayerResumeRequestGate()
        every { controller.afrPreflightGate } returns AfrPreflightGate()
        every { controller.debridSettingsDataStore } returns null
        every { controller.pendingInitializationResume } answers { pendingInitialization }
        every { controller.pendingInitializationResume = any() } answers { pendingInitialization = firstArg() }
        every { controller.playbackPreparationJob } answers { preparation }
        every { controller.playbackPreparationJob = any() } answers { preparation = firstArg() }
        every { controller.traktMappingJob } answers { mapping }
        every { controller.traktMappingJob = any() } answers { mapping = firstArg() }
        every { controller.persistedTrackPreference } answers { persisted }
        every { controller.persistedTrackPreference = any() } answers { persisted = firstArg() }
        every { controller.subtitleDelayUs } returns AtomicLong()
        every { controller.trackPreferenceDataStore } returns preferences
        every { controller.isInBackground } answers { background }
        every { controller.isInBackground = any() } answers { background = firstArg() }
        every { controller.userPausedManually } answers { paused }
        every { controller.userPausedManually = any() } answers { paused = firstArg() }
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.currentMediaSession } returns null
        every { controller._exoPlayer } returns retainedOutgoingPlayer
        every { controller.nextEpisodeAutoPlayJob } returns null
        every { controller.pendingBackgroundCrashRecovery } returns false
        every { controller.pendingPlaybackRawEventLines } returns ArrayDeque()
        every { controller.party.state } returns MutableStateFlow(PartyUiState())
        every { controller.party.playback } returns MutableStateFlow<PartyPlayback?>(null)
        every { controller.recordLoadingDiagnosticEvent(any(), any(), any(), any()) } just Runs
        coEvery { controller.traktEpisodeMappingService.isTraktAuthenticated() } returns false
        val coordinator = PlayerProgressWriteCoordinator(PlayerProgressWriteAuthority())
        every { controller.progressWriteCoordinator } returns coordinator
        val owner = checkNotNull(controller.currentProgressWriteOwner())
        PlayerRuntimeController::class.java.getDeclaredField("playbackOriginOwner").apply { isAccessible = true }
            .set(controller, owner)
        coordinator.activateForeground(owner)
        check(coordinator.onPlaybackOwnerChanged(owner))
        suspend fun hold(at: Phase) {
            if (phase == at && (!holdFirstBoundaryOnly || !entered.isCompleted)) {
                entered.complete(Unit)
                // No dispatcher change: this boundary intentionally returns even after its caller Job is canceled.
                withContext(NonCancellable) { release.await() }
                completedLateBoundaries.incrementAndGet()
            }
        }
        coEvery { controller.directDebridResolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) } coAnswers {
            authorizations.incrementAndGet()
            hold(Phase.AUTH)
            authorized
        }
        coEvery { preferences.load("tt-prep") } coAnswers {
            hold(Phase.TRACKS)
            PersistedTrackPreference(subtitleType = "DISABLED", subtitleLanguage = null,
                subtitleName = null, subtitleTrackId = null, addonSubtitleId = null,
                addonSubtitleUrl = null, addonSubtitleAddonName = null,
                audioLanguage = "tr", audioName = "Turkish", audioTrackId = "audio-tr")
        }
        coEvery { preferences.loadSubtitleDelayMs(any()) } coAnswers { hold(Phase.DELAY); 1500 }
        coEvery { preferences.loadPlaybackSpeed("tt-prep") } coAnswers { hold(Phase.SPEED); 1.25f }
        val initializations = mutableListOf<Handoff>()
        every { controller.initializePlayer(any(), any(), any(), any(), any()) } answers {
            initializations += Handoff(arg<String>(1), controller.currentVideoId, arg<Boolean>(5),
                controller.currentProgressWriteOwner(), controller.playbackOrigin,
                ui.value.subtitleDelayMs, controller.subtitleDelayUs.get())
        }
        return Fixture(controller, ui, video, url, release, entered, initializations, this,
            completedLateBoundaries, origin, authorizations)
    }
}
