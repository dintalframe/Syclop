package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.streams.SyclopPlaybackPolicy
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test

/** Holds the real initializer at its external authorization boundary. No player is constructed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerInitializationCancellationTest {
    @After fun cleanup() {
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerLoadingDiagnosticsKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTorrentKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerPlaybackEventsKt")
    }

    @Test fun `ordinary source switch keeps its new initializer current when starting its own resume read`() = runTest {
        val f = heldAuthorization()
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTorrentKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerPlaybackEventsKt")
        // Native release, outgoing persistence and audio-route work are separate boundaries.
        every { f.controller.sourceStreamsScope } returns null
        every { f.controller.releasePlayer(false, false) } just Runs
        every { f.controller.stopTorrentStream() } just Runs
        every { f.controller.flushPlaybackSnapshotForSwitchOrExit() } just Runs
        every { f.controller.applyStreamAudioDelay(any(), any()) } just Runs
        every { f.controller._exoPlayer } returns null
        every { f.controller.streamReuseLastLinkEnabled } returns false
        val cacheProfile = mockk<ProfileManager>()
        every { cacheProfile.activeProfileId } returns MutableStateFlow(7)
        // Kotlin evaluates replace's default profile before dispatching the mocked persistence call.
        val cache = spyk(BingeGroupCacheDataStore(mockk(relaxed = true), cacheProfile))
        coEvery { cache.replace(any(), any(), any(), any(), any(), any()) } just Runs
        every { f.controller.bingeGroupCacheDataStore } returns cache
        every { f.controller.watchProgressRepository.getEpisodeProgress("tt-initializer", 1, 2, 7) } returns
            flow<WatchProgress?> { awaitCancellation() }
        val selected = Stream(name = "Owned alternative", title = null, description = null,
            url = "https://owned.example.test/media.mp4", ytId = null, infoHash = null, fileIdx = null,
            externalUrl = null, behaviorHints = null, addonName = "Owned fixture", addonLogo = null)

        f.controller.switchToSourceStream(selected)
        runCurrent()
        f.authorization.complete(false)
        runCurrent()

        assertEquals(SyclopPlaybackPolicy.BLOCKED_MESSAGE, f.ui.value.error)
        assertEquals(1L, f.authorizationCalls.get())
    }

    @Test fun `a successful authorization released after Back cannot rearm loading`() = runTest {
        val f = heldAuthorization()
        f.begin()
        f.controller.cancelAfrPreflight()

        f.authorization.complete(true)
        runCurrent()

        assertEquals(f.before, f.ui.value)
    }

    @Test fun `a successful authorization released after Start over cannot rearm old loading`() = runTest {
        val f = heldAuthorization()
        f.begin()
        f.resume.invalidate() // OnStartFromBeginning owns this existing invalidation.

        f.authorization.complete(true)
        runCurrent()

        assertEquals(f.before, f.ui.value)
    }

    @Test fun `a successful authorization from a replaced account cannot rearm loading`() = runTest {
        val f = heldAuthorization()
        f.begin()
        f.accountRevision.incrementAndGet()

        f.authorization.complete(true)
        runCurrent()

        assertEquals(f.before, f.ui.value)
    }

    @Test fun `leaving and returning to a profile cannot revive a pending initialization`() = runTest {
        val f = heldAuthorization()
        f.begin()
        f.profile.value = 8
        f.selectionRevision.incrementAndGet()
        f.profile.value = 7
        f.selectionRevision.incrementAndGet()

        f.authorization.complete(true)
        runCurrent()

        assertEquals(f.before, f.ui.value)
    }

    @Test fun `a successful authorization for an outgoing episode cannot rearm loading`() = runTest {
        val f = heldAuthorization()
        f.begin()
        f.video.value = "tt-initializer:1:3"

        f.authorization.complete(true)
        runCurrent()

        assertEquals(f.before, f.ui.value)
    }

    @Test fun `a backgrounded authorization cannot rearm loading`() = runTest {
        val f = heldAuthorization()
        f.begin()
        every { f.controller.isInBackground } returns true

        f.authorization.complete(true)
        runCurrent()

        assertEquals(f.before, f.ui.value)
    }

    @Test fun `first screen resume lets the pending initializer finish without restarting it`() = runTest {
        val f = heldAuthorization(initiallyForeground = false)
        every { f.controller._exoPlayer } returns null
        every { f.controller.pendingBackgroundCrashRecovery } returns false
        f.begin()
        val originalRequest = checkNotNull(f.controller.pendingInitializationResume)
        assertEquals(1L, f.authorizationCalls.get())

        // A new navigation entry can start playback while STARTED, before its first ON_RESUME.
        f.controller.progressWriteCoordinator.activateForeground(f.controller.currentProgressWriteOwner())
        f.controller.resumeForLifecycle()
        runCurrent()

        assertEquals(1L, f.authorizationCalls.get())
        assertSame(originalRequest, f.controller.pendingInitializationResume)
        f.authorization.complete(false)
        runCurrent()
        assertEquals(SyclopPlaybackPolicy.BLOCKED_MESSAGE, f.ui.value.error)
        assertEquals(null, f.controller.pendingInitializationResume)
    }

    @Test fun `foreground return starts a fresh paused request and old completion cannot clear it`() = runTest {
        val f = heldAuthorization()
        var background = false
        var paused = false
        every { f.controller.isInBackground } answers { background }
        every { f.controller.isInBackground = any() } answers { background = firstArg() }
        every { f.controller.userPausedManually } answers { paused }
        every { f.controller.userPausedManually = any() } answers { paused = firstArg() }
        every { f.controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { f.controller.currentMediaSession } returns null
        every { f.controller._exoPlayer } returns null
        every { f.controller.nextEpisodeAutoPlayJob } returns null
        every { f.controller.pendingBackgroundCrashRecovery } returns false
        f.begin()
        f.controller.pauseForLifecycle()
        f.controller.progressWriteCoordinator.deactivateForeground()
        f.controller.progressWriteCoordinator.activateForeground(f.controller.currentProgressWriteOwner())
        f.controller.resumeForLifecycle()
        runCurrent()
        val resumedRequest = checkNotNull(f.controller.pendingInitializationResume)

        f.authorization.complete(true)
        runCurrent()

        assertEquals(2L, f.authorizationCalls.get())
        assertTrue(paused)
        assertFalse(f.ui.value.showControls)
        assertEquals("Preparing owned file", f.ui.value.loadingMessage)
        assertSame(resumedRequest, f.controller.pendingInitializationResume)
    }

    @Test fun `released initialization cannot replace the current screen with a late authorization error`() = runTest {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val ui = MutableStateFlow(PlayerUiState(error = "Current screen"))
        val gate = AfrPreflightGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        every { controller._uiState } returns ui
        every { controller.context.getString(any()) } returns "Could not start playback"
        every { controller.firstWatchReadiness } returns null
        every { controller.debridSettingsDataStore } returns null
        every { controller.currentHeaders } returns emptyMap()
        every { controller.resumeRequestGate } returns PlayerResumeRequestGate()
        every { controller.isInBackground } returns false
        every { controller.afrPreflightGate } returns gate
        every { controller.scope } returns backgroundScope
        coEvery { controller.directDebridResolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            false
        }

        controller.initializePlayer("https://owned.example.test/media.mp4", emptyMap(), allowEngineFailover = false)
        runCurrent()
        assertTrue(entered.isCompleted)
        controller.cancelAfrPreflight() // releasePlayer uses this exact invalidation.
        release.complete(Unit)
        runCurrent()

        assertEquals("Current screen", ui.value.error)
    }

    @Test fun `authorization cancellation is not reported as a playback failure`() = runTest {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val ui = MutableStateFlow(PlayerUiState(error = "Current screen"))
        every { controller._uiState } returns ui
        every { controller.context.getString(any()) } returns "Could not start playback"
        every { controller.firstWatchReadiness } returns null
        every { controller.debridSettingsDataStore } returns null
        every { controller.currentHeaders } returns emptyMap()
        every { controller.resumeRequestGate } returns PlayerResumeRequestGate()
        every { controller.isInBackground } returns false
        every { controller.afrPreflightGate } returns AfrPreflightGate()
        every { controller.scope } returns backgroundScope
        coEvery { controller.directDebridResolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) } throws
            CancellationException("Owned authorization was canceled")

        controller.initializePlayer("https://owned.example.test/media.mp4", emptyMap(), allowEngineFailover = false)
        runCurrent()

        assertEquals("Current screen", ui.value.error)
    }

    @Test fun `current authorization rejection still explains the blocked playback`() = runTest {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val ui = MutableStateFlow(PlayerUiState())
        every { controller._uiState } returns ui
        every { controller.context.getString(any()) } returns "Could not start playback"
        every { controller.firstWatchReadiness } returns null
        every { controller.debridSettingsDataStore } returns null
        every { controller.currentHeaders } returns emptyMap()
        every { controller.resumeRequestGate } returns PlayerResumeRequestGate()
        every { controller.isInBackground } returns false
        every { controller.afrPreflightGate } returns AfrPreflightGate()
        every { controller.scope } returns backgroundScope
        coEvery { controller.directDebridResolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) } returns false

        controller.initializePlayer("https://owned.example.test/media.mp4", emptyMap(), allowEngineFailover = false)
        runCurrent()

        assertEquals(SyclopPlaybackPolicy.BLOCKED_MESSAGE, ui.value.error)
    }

    private class HeldAuthorization(
        val controller: PlayerRuntimeController,
        val ui: MutableStateFlow<PlayerUiState>,
        val before: PlayerUiState,
        val authorization: CompletableDeferred<Boolean>,
        val entered: CompletableDeferred<Unit>,
        val resume: PlayerResumeRequestGate,
        val accountRevision: AtomicLong,
        val selectionRevision: AtomicLong,
        val profile: MutableStateFlow<Int>,
        val video: MutableStateFlow<String>,
        val scope: TestScope,
        val authorizationCalls: AtomicLong
    ) {
        fun begin() {
            controller.initializePlayer("https://owned.example.test/media.mp4", emptyMap(), allowEngineFailover = false)
            scope.runCurrent()
            assertTrue(entered.isCompleted)
        }
    }

    private fun TestScope.heldAuthorization(initiallyForeground: Boolean = true): HeldAuthorization {
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerLoadingDiagnosticsKt")
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val before = PlayerUiState(showControls = true, loadingMessage = "Current session")
        val ui = MutableStateFlow(before)
        val resume = PlayerResumeRequestGate()
        val profile = MutableStateFlow(7)
        val video = MutableStateFlow("tt-initializer:1:2")
        val selection = AtomicLong(3L)
        val account = AtomicLong(4L)
        val settings = mockk<DebridSettingsDataStore>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val authorization = CompletableDeferred<Boolean>()
        val authorizationCalls = AtomicLong()
        var pendingInitialization: (() -> Unit)? = null
        every { controller.pendingInitializationResume } answers { pendingInitialization }
        every { controller.pendingInitializationResume = any() } answers { pendingInitialization = firstArg() }
        every { controller._uiState } returns ui
        every { controller.firstWatchReadiness } returns null
        every { controller.context.getString(any()) } returns "Preparing owned file"
        every { controller.afrPreflightGate } returns AfrPreflightGate()
        every { controller.resumeRequestGate } returns resume
        every { controller.scope } returns backgroundScope
        every { controller.profileId } returns 7
        every { controller.contentId } returns "tt-initializer"
        every { controller.contentType } returns "series"
        every { controller.currentVideoId } answers { video.value }
        every { controller.currentSeason } returns 1
        every { controller.currentEpisode } answers { video.value.substringAfterLast(':').toInt() }
        every { controller.currentStreamUrl } returns "https://owned.example.test/media.mp4"
        every { controller.currentHeaders } returns emptyMap()
        val coordinator = PlayerProgressWriteCoordinator(PlayerProgressWriteAuthority())
        every { controller.progressWriteCoordinator } returns coordinator
        val owner = checkNotNull(controller.currentProgressWriteOwner())
        if (initiallyForeground) coordinator.activateForeground(owner)
        check(coordinator.onPlaybackOwnerChanged(owner) == initiallyForeground)
        every { controller.isInBackground } returns false
        every { controller.debridSettingsDataStore } returns settings
        every { settings.activeProfileId } returns profile
        every { settings.activeProfileSelectionRevision } answers { selection.get() }
        every { settings.activeCredentialRevision } answers { account.get() }
        every { controller.playbackIssueReportRequestVersion } returns AtomicLong()
        every { controller.resetLoadingDiagnostics(any(), any(), any()) } just Runs
        every { controller.playerSettingsDataStore.playerSettings } returns flow<PlayerSettings> { awaitCancellation() }
        coEvery { controller.directDebridResolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) } coAnswers {
            authorizationCalls.incrementAndGet()
            entered.complete(Unit)
            authorization.await()
        }
        return HeldAuthorization(controller, ui, before, authorization, entered, resume, account, selection, profile, video,
            this, authorizationCalls)
    }

}
