package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.party.PartyPlayback
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.core.tracking.TrackingPlaybackOrigin
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.repository.SubtitleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real subtitle fetch and event handlers; service results/native player are controlled boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSubtitleLookupOwnershipTest {
    @Test fun `a late old episode list cannot replace the new episode subtitle state`() = runTest {
        val f = Fixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        try {
            f.answer = { withContext(NonCancellable) { finish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(listOf(VIDEO), f.requestedVideos)

            f.video = "tt-owned:1:2"
            f.episode = 2
            f.gate.invalidate()
            f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), isLoadingAddonSubtitles = false)
            finish.complete(Unit)
            runCurrent()

            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertFalse(f.ui.value.isLoadingAddonSubtitles)
        } finally {
            finish.complete(Unit)
        }
    }

    @Test fun `a late old source list cannot replace subtitles after a same-video source switch`() = runTest {
        val f = Fixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        try {
            f.answer = { withContext(NonCancellable) { finish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(1, f.calls)

            f.url = "https://playback.example.invalid/owned-source-b.mp4"
            f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), isLoadingAddonSubtitles = false)
            finish.complete(Unit)
            runCurrent()

            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertFalse(f.ui.value.isLoadingAddonSubtitles)
        } finally {
            finish.complete(Unit)
        }
    }

    @Test fun `a late old profile list cannot publish to the replacement profile`() = runTest {
        val f = Fixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        try {
            f.answer = { withContext(NonCancellable) { finish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(1, f.calls)

            f.activeProfile.value = 2
            f.selectionRevision++
            f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), isLoadingAddonSubtitles = false)
            finish.complete(Unit)
            runCurrent()

            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
        } finally {
            finish.complete(Unit)
        }
    }

    @Test fun `a late failed old episode cannot set an error or stop the new request loading state`() = runTest {
        val f = Fixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        try {
            f.answer = { withContext(NonCancellable) { finish.await(); throw IllegalStateException(FAILURE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(1, f.calls)

            f.video = "tt-owned:1:2"
            f.episode = 2
            f.gate.invalidate()
            f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), isLoadingAddonSubtitles = true)
            finish.complete(Unit)
            runCurrent()

            assertNull(f.ui.value.addonSubtitlesError)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
        } finally {
            finish.complete(Unit)
        }
    }

    @Test fun `the newer same-owner lookup wins when an older uncooperative request finishes late`() = runTest {
        val f = Fixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        try {
            f.answer = { withContext(NonCancellable) { finish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(1, f.calls)
            f.answer = { listOf(NEW_SUBTITLE) }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(2, f.calls)
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)

            finish.complete(Unit)
            runCurrent()

            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
        } finally {
            finish.complete(Unit)
        }
    }

    @Test fun `current-owner failure retains valid existing subtitles and manual track selection`() = runTest {
        val f = Fixture(backgroundScope)
        f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), selectedAddonSubtitle = NEW_SUBTITLE)
        f.answer = { throw IllegalStateException(FAILURE) }

        f.controller.fetchAddonSubtitles()
        runCurrent()

        assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
        assertEquals(NEW_SUBTITLE, f.ui.value.selectedAddonSubtitle)
        assertTrue(f.ui.value.addonSubtitlesError != null)
        assertFalse(f.ui.value.isLoadingAddonSubtitles)
    }

    @Test fun `a canceled current lookup does not publish a provider failure or remove valid subtitles`() = runTest {
        val f = Fixture(backgroundScope)
        f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), selectedAddonSubtitle = NEW_SUBTITLE)
        f.answer = { throw CancellationException("Owned cancellation") }

        f.controller.fetchAddonSubtitles()
        runCurrent()

        assertNull(f.ui.value.addonSubtitlesError)
        assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
        assertEquals(NEW_SUBTITLE, f.ui.value.selectedAddonSubtitle)
    }

    @Test fun `actual Retry in the failed subtitle overlay retries lookup while retaining playback and manual selection`() = runTest {
        val f = Fixture(backgroundScope)
        f.ui.value = f.ui.value.copy(
            showSubtitleOverlay = true, addonSubtitlesError = FAILURE,
            addonSubtitles = listOf(NEW_SUBTITLE), selectedAddonSubtitle = NEW_SUBTITLE,
            audioTracks = listOf(TrackInfo(0, "Turkish", "tr", "owned-tr", isSelected = true)),
            selectedAudioTrackIndex = 0
        )
        f.answer = { listOf(NEW_SUBTITLE) }

        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        assertEquals(1, f.calls)
        assertEquals(listOf(VIDEO), f.requestedVideos)
        assertNull(f.ui.value.addonSubtitlesError)
        assertEquals(NEW_SUBTITLE, f.ui.value.selectedAddonSubtitle)
        assertEquals(0, f.ui.value.selectedAudioTrackIndex)
        assertTrue(f.controller.isUserExplicitSubtitleSelection)
        assertEquals(URL, f.url)
        assertSame(f.player, f.controller._exoPlayer)
        verify(exactly = 0) { f.player.stop() }
        verify(exactly = 0) { f.player.release() }
    }

    @Test fun `actual Retry while the subtitle lookup is loading does not enqueue another request`() = runTest {
        val f = Fixture(backgroundScope)
        f.ui.value = f.ui.value.copy(showSubtitleOverlay = true, addonSubtitlesError = FAILURE)
        val finish = CompletableDeferred<Unit>()
        f.answer = { finish.await(); listOf(NEW_SUBTITLE) }
        f.controller.fetchAddonSubtitles()
        runCurrent()
        assertTrue(f.ui.value.isLoadingAddonSubtitles)
        assertEquals(1, f.calls)

        f.controller.onEvent(PlayerEvent.OnRetry)
        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()
        assertEquals(1, f.calls)
        finish.complete(Unit)
        runCurrent()

        assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
        coVerify(exactly = 1) { f.repository.getSubtitles(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // Follow-on only after the approved callback signature change. Never baseline RED evidence.
    @Test fun `callback partial results and error survive the final valid list update`() = runTest {
        val f = Fixture(backgroundScope)
        f.answer = {
            f.callback?.invoke(NetworkResult.Success(listOf(NEW_SUBTITLE)))
            f.callback?.invoke(NetworkResult.Error("owned opaque provider failure"))
            listOf(NEW_SUBTITLE)
        }

        f.controller.fetchAddonSubtitles()
        runCurrent()

        assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
        assertTrue("The trailing List must not erase aggregate failure", f.ui.value.addonSubtitlesError != null)
        assertFalse(f.ui.value.isLoadingAddonSubtitles)
        assertTrue(f.controller.isUserExplicitSubtitleSelection)
    }

    @Test fun `an all-failed retry keeps known valid partial subtitles alongside stream sidecars`() = runTest {
        val f = Fixture(backgroundScope)
        val sidecar = NEW_SUBTITLE.copy(id = "owned-sidecar", isStreamProvided = true)
        f.sidecars = listOf(sidecar)
        f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), selectedAddonSubtitle = NEW_SUBTITLE)
        f.answer = {
            f.callback?.invoke(NetworkResult.Error("owned lookup failure"))
            emptyList()
        }

        f.controller.fetchAddonSubtitles()
        runCurrent()

        assertEquals(setOf(NEW_SUBTITLE, sidecar), f.ui.value.addonSubtitles.toSet())
        assertEquals(NEW_SUBTITLE, f.ui.value.selectedAddonSubtitle)
        assertTrue(f.ui.value.addonSubtitlesError != null)
        assertFalse(f.ui.value.isLoadingAddonSubtitles)
        assertTrue(f.controller.isUserExplicitSubtitleSelection)
    }

    @Test fun `late incremental success and error cannot publish after an episode owner change`() = runTest {
        val f = Fixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        try {
            f.answer = { withContext(NonCancellable) { finish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(1, f.calls)
            val late = requireNotNull(f.callback)
            f.video = "tt-owned:1:2"
            f.episode = 2
            f.gate.invalidate()
            f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), isLoadingAddonSubtitles = true)

            late(NetworkResult.Success(listOf(OLD_SUBTITLE)))
            late(NetworkResult.Error("owned late failure"))
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)
            finish.complete(Unit)
            runCurrent()
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)
        } finally {
            finish.complete(Unit)
        }
    }

    @Test fun `a superseded same-owner callback cannot overwrite the newer result or failure state`() = runTest {
        val f = Fixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        try {
            f.answer = { withContext(NonCancellable) { finish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            val late = requireNotNull(f.callback)
            f.answer = { listOf(NEW_SUBTITLE) }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(2, f.calls)
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)

            late(NetworkResult.Success(listOf(OLD_SUBTITLE)))
            late(NetworkResult.Error("owned superseded failure"))
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            finish.complete(Unit)
            runCurrent()
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
        } finally {
            finish.complete(Unit)
        }
    }

    @Test fun `a lookup completed in background restarts on real foreground return without stale subtitle mutation`() = runTest {
        val f = Fixture(backgroundScope)
        var background = false
        var paused = false
        val afr = AfrPreflightGate()
        val mediaSession = mockk<MediaSession>(relaxed = true)
        val resumedSession = mockk<MediaSession>(relaxed = true)
        var currentSession: MediaSession? = mediaSession
        every { f.controller.isInBackground } answers { background }
        every { f.controller.isInBackground = any() } answers { background = firstArg() }
        every { f.controller.userPausedManually } answers { paused }
        every { f.controller.userPausedManually = any() } answers { paused = firstArg() }
        every { f.controller.afrPreflightGate } returns afr
        every { f.controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { f.controller.context } returns mockk<Context>(relaxed = true)
        every { f.controller.currentMediaSession } answers { currentSession }
        every { f.controller.currentMediaSession = any() } answers { currentSession = firstArg() }
        every { f.player.currentMediaItem } returns null
        every { f.controller.nextEpisodeAutoPlayJob } returns null
        every { f.controller.pendingInitializationResume } returns null
        every { f.controller.pendingBackgroundCrashRecovery } returns false
        every { f.controller.currentScrobbleItem } returns null
        every { f.player.audioAttributes } returns AudioAttributes.DEFAULT
        every { f.player.canAdvertiseSession() } returns true
        f.ui.value = f.ui.value.copy(addonSubtitles = listOf(NEW_SUBTITLE), selectedAddonSubtitle = NEW_SUBTITLE)
        f.controller.onEvent(PlayerEvent.OnShowSubtitleOverlay)
        val oldFinish = CompletableDeferred<Unit>()
        val freshFinish = CompletableDeferred<Unit>()
        val fresh = NEW_SUBTITLE.copy(id = "fresh-en", url = "https://captions.example.invalid/fresh.vtt")
        try {
            // Native construction only is faked; actual pause clears and resume replaces the backed session.
            mockkConstructor(MediaSession.Builder::class)
            every { anyConstructed<MediaSession.Builder>().build() } returns resumedSession
            f.answer = { withContext(NonCancellable) { oldFinish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(1, f.calls)
            val oldCallback = requireNotNull(f.callback)
            val oldJob = requireNotNull(f.controller.subtitleFetchJob)
            assertTrue(oldJob.isActive)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)
            val resumeBeforePause = f.gate.token()
            val afrBeforePause = afr.token()

            // Exact PlayerScreen ordering. Actual pause only cancels AFR, not the resume token.
            f.controller.pauseForLifecycle()
            f.controller.progressWriteCoordinator.deactivateForeground()
            assertTrue(background)
            assertTrue(paused)
            assertNull(currentSession)
            verify(exactly = 1) { mediaSession.release() }
            assertEquals(resumeBeforePause, f.gate.token())
            assertFalse(afr.isCurrent(afrBeforePause))
            oldFinish.complete(Unit) // Uncooperative old IO finishes while background.
            runCurrent()
            assertTrue(oldJob.isCompleted)
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertTrue(f.ui.value.isLoadingAddonSubtitles) // Existing intent to finish the interrupted lookup.
            assertTrue(f.ui.value.showSubtitleOverlay)

            f.answer = { freshFinish.await(); listOf(fresh) }
            f.controller.progressWriteCoordinator.activateForeground(f.controller.currentProgressWriteOwner())
            f.controller.resumeForLifecycle()
            runCurrent()

            assertFalse(background)
            assertSame("The native session boundary must be restored before accepting behavior RED", resumedSession, currentSession)
            assertEquals("A foreground retained player must restart the interrupted lookup", 2, f.calls)
            assertEquals(listOf(VIDEO, VIDEO), f.requestedVideos)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)
            oldCallback(NetworkResult.Success(listOf(OLD_SUBTITLE)))
            oldCallback(NetworkResult.Error("owned old failure"))
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)

            freshFinish.complete(Unit)
            runCurrent()
            assertEquals(listOf(fresh), f.ui.value.addonSubtitles)
            assertFalse(f.ui.value.isLoadingAddonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertEquals(NEW_SUBTITLE, f.ui.value.selectedAddonSubtitle)
            assertTrue(f.controller.isUserExplicitSubtitleSelection)
            assertTrue(paused)
            assertSame(f.player, f.controller._exoPlayer)
            verify(exactly = 1) { f.player.pause() }
            verify(exactly = 0) { f.player.stop() }
            verify(exactly = 0) { f.player.release() }
        } finally {
            oldFinish.complete(Unit)
            freshFinish.complete(Unit)
            unmockkConstructor(MediaSession.Builder::class)
        }
    }

    @Test fun `actual start from beginning replaces a still-loading subtitle lookup under the fresh visit`() = runTest {
        val f = Fixture(backgroundScope)
        val timeline = MutableStateFlow(PlaybackTimelineState(currentPosition = 45_000L, duration = 130_000L))
        // The real timeline method's default arguments read these fields on this isolated mock.
        PlayerRuntimeController::class.java.getDeclaredField("_uiState").apply { isAccessible = true }.set(f.controller, f.ui)
        PlayerRuntimeController::class.java.getDeclaredField("_playbackTimeline").apply { isAccessible = true }.set(f.controller, timeline)
        every { f.controller._playbackTimeline } returns timeline
        every { f.controller.updatePlaybackTimeline(any(), any(), any(), any(), any()) } answers { callOriginal() }
        every { f.controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { f.controller.party.state } returns MutableStateFlow(PartyUiState())
        every { f.controller.party.playback } returns MutableStateFlow<PartyPlayback?>(null)
        every { f.controller.autoSkippedIntervalKeys } returns mutableSetOf()
        every { f.controller.nextEpisodeAutoPlayJob } returns null
        every { f.controller.stillWatchingPromptJob } returns null
        every { f.controller.seekProgressSyncDebounceMs } returns 700L
        every { f.player.duration } returns 130_000L
        var paused = true
        every { f.controller.userPausedManually } answers { paused }
        every { f.controller.userPausedManually = any() } answers { paused = firstArg() }
        f.ui.value = f.ui.value.copy(showSubtitleOverlay = true,
            addonSubtitles = listOf(NEW_SUBTITLE), selectedAddonSubtitle = NEW_SUBTITLE,
            audioTracks = listOf(TrackInfo(0, "Turkish", "tr", "owned-tr", isSelected = true)),
            selectedAudioTrackIndex = 0)
        val oldFinish = CompletableDeferred<Unit>()
        val freshFinish = CompletableDeferred<Unit>()
        val fresh = NEW_SUBTITLE.copy(id = "fresh-restart-en", url = "https://captions.example.invalid/restart.vtt")
        try {
            mockkObject(NuvioExoPlayerPerformanceHelper)
            every { NuvioExoPlayerPerformanceHelper.enabled } returns false
            f.answer = { withContext(NonCancellable) { oldFinish.await(); listOf(OLD_SUBTITLE) } }
            f.controller.fetchAddonSubtitles()
            runCurrent()
            assertEquals(1, f.calls)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)
            val oldCallback = requireNotNull(f.callback)
            val oldJob = requireNotNull(f.controller.subtitleFetchJob)
            val oldToken = f.gate.token()
            val owner = requireNotNull(f.controller.currentProgressWriteOwner())
            val oldLease = requireNotNull(f.controller.progressWriteCoordinator.capture(owner, completedAtCapture = false))
            assertTrue(f.controller.progressWriteCoordinator.mayPublish(oldLease))
            f.answer = { freshFinish.await(); listOf(fresh) }

            f.controller.onEvent(PlayerEvent.OnStartFromBeginning)
            runCurrent()

            // Establish a successful real restart before accepting a lookup-liveness RED.
            verify(exactly = 1) { f.player.setSeekParameters(SeekParameters.EXACT) }
            verify(exactly = 1) { f.player.seekTo(0L) }
            verify(exactly = 1) { f.player.play() }
            assertEquals(0L, timeline.value.currentPosition)
            assertFalse(paused)
            assertFalse(f.gate.isCurrent(oldToken))
            assertFalse(f.controller.progressWriteCoordinator.mayPublish(oldLease))
            val freshLease = requireNotNull(f.controller.progressWriteCoordinator.capture(owner, completedAtCapture = false))
            assertTrue(f.controller.progressWriteCoordinator.mayPublish(freshLease))
            assertEquals(owner, f.controller.currentProgressWriteOwner())
            assertEquals(URL, f.url)
            assertSame(f.player, f.controller._exoPlayer)
            assertEquals("A successful restart must replace the in-flight lookup without another Retry", 2, f.calls)
            assertEquals(listOf(VIDEO, VIDEO), f.requestedVideos)
            assertTrue(f.controller.subtitleFetchJob !== oldJob)
            assertTrue(requireNotNull(f.controller.subtitleFetchJob).isActive)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)

            oldCallback(NetworkResult.Success(listOf(OLD_SUBTITLE)))
            oldCallback(NetworkResult.Error("owned superseded restart failure"))
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)
            oldFinish.complete(Unit)
            runCurrent()
            assertTrue(oldJob.isCompleted)
            assertEquals(listOf(NEW_SUBTITLE), f.ui.value.addonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertTrue(f.ui.value.isLoadingAddonSubtitles)

            freshFinish.complete(Unit)
            runCurrent()
            assertEquals(listOf(fresh), f.ui.value.addonSubtitles)
            assertFalse(f.ui.value.isLoadingAddonSubtitles)
            assertNull(f.ui.value.addonSubtitlesError)
            assertEquals(NEW_SUBTITLE, f.ui.value.selectedAddonSubtitle)
            assertEquals(0, f.ui.value.selectedAudioTrackIndex)
            assertTrue(f.controller.isUserExplicitSubtitleSelection)
            assertTrue(f.ui.value.showSubtitleOverlay)
            assertSame(f.player, f.controller._exoPlayer)
            assertEquals(2, f.calls)
            coVerify(exactly = 2) { f.repository.getSubtitles(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { f.player.stop() }
            verify(exactly = 0) { f.player.release() }
        } finally {
            oldFinish.complete(Unit)
            freshFinish.complete(Unit)
            unmockkObject(NuvioExoPlayerPerformanceHelper)
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val player = mockk<ExoPlayer>(relaxed = true)
        val repository = mockk<SubtitleRepository>()
        private val installed = mockk<AddonRepositoryImpl>()
        val ui = MutableStateFlow(PlayerUiState())
        val gate = PlayerResumeRequestGate()
        private val progress = PlayerProgressWriteCoordinator(PlayerProgressWriteAuthority())
        private var fetchJob: Job? = null
        var video: String? = VIDEO
        var episode: Int? = 1
        val activeProfile = MutableStateFlow(1)
        var selectionRevision = 1L
        var credentialRevision = 1L
        private val settings = mockk<DebridSettingsDataStore>()
        var url = URL
        var explicitSubtitleSelection = true
        var nativePlayer: ExoPlayer? = player
        var calls = 0
        val requestedVideos = mutableListOf<String?>()
        var answer: suspend () -> List<Subtitle> = { emptyList() }
        var callback: ((NetworkResult<List<Subtitle>>) -> Unit)? = null
        var sidecars: List<Subtitle> = emptyList()
        private val origin = mockk<Deferred<TrackingPlaybackOrigin>>(relaxed = true)

        init {
            every { controller._uiState } returns ui
            every { controller.scope } returns scope
            every { controller.subtitleRepository } returns repository
            every { controller.addonRepository } returns installed
            every { installed.getInstalledAddons() } returns flowOf(emptyList())
            every { controller.contentId } returns "tt-owned"
            every { controller.contentType } returns "series"
            every { controller.profileId } returns 1
            every { controller.currentVideoId } answers { video }
            every { controller.currentSeason } returns 1
            every { controller.currentEpisode } answers { episode }
            every { controller.currentStreamUrl } answers { url }
            every { controller.currentHeaders } returns emptyMap()
            every { controller.currentVideoHash } returns "owned-hash"
            every { controller.currentVideoSize } returns 128L
            every { controller.currentFilename } returns "owned.mp4"
            every { controller.streamSubtitles } answers { sidecars }
            every { controller.currentInfoHash } returns null
            every { controller.currentFileIdx } returns null
            every { controller._exoPlayer } answers { nativePlayer }
            every { controller._exoPlayer = any() } answers { nativePlayer = firstArg() }
            every { controller.resumeRequestGate } returns gate
            every { controller.progressWriteCoordinator } returns progress
            every { controller.subtitleFetchJob } answers { fetchJob }
            every { controller.subtitleFetchJob = any() } answers { fetchJob = firstArg() }
            val initialOwner = PlayerProgressWriteOwner(1, "tt-owned", "series", VIDEO, 1, 1)
            progress.activateForeground(initialOwner)
            progress.onPlaybackOwnerChanged(initialOwner)
            every { controller.playbackOrigin } returns origin
            every { controller.debridSettingsDataStore } returns settings
            every { settings.activeProfileId } returns activeProfile
            every { settings.activeProfileSelectionRevision } answers { selectionRevision }
            every { settings.activeCredentialRevision } answers { credentialRevision }
            every { controller.isInBackground } returns false
            every { controller.isReleasingPlayer } returns false
            every { controller.pauseOverlayJob } returns null
            every { controller.pendingRestoredAddonSubtitle } returns null
            every { controller.pendingEngineSwitchTrackPreference } returns null
            every { controller.persistedTrackPreference } returns null
            every { controller.isUserExplicitSubtitleSelection } answers { explicitSubtitleSelection }
            every { controller.isUserExplicitSubtitleSelection = any() } answers { explicitSubtitleSelection = firstArg() }
            coEvery { repository.getSubtitles(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                calls++
                requestedVideos.add(arg(2))
                callback = arg(7)
                answer()
            }
        }
    }

    private companion object {
        const val VIDEO = "tt-owned:1:1"
        const val URL = "https://playback.example.invalid/owned-source-a.mp4"
        const val FAILURE = "Owned lookup failed"
        val OLD_SUBTITLE = Subtitle("old-en", "https://captions.example.invalid/old.vtt", "en", "owned", null)
        val NEW_SUBTITLE = Subtitle("new-en", "https://captions.example.invalid/new.vtt", "en", "owned", null)
    }
}
