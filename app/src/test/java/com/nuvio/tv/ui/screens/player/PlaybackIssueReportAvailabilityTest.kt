package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.remote.api.PlaybackIssueReportApi
import com.nuvio.tv.data.repository.PlaybackIssueReportInput
import com.nuvio.tv.data.repository.PlaybackIssueReportRepository
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real production dispatch/timer functions, in-memory state and mocked submission boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackIssueReportAvailabilityTest {
    @Before
    fun requireTheCurrentUnconfiguredBuild() {
        assertTrue("These regressions qualify the build with no reporting service configured",
            BuildConfig.PLAYBACK_REPORTS_BASE_URL.isBlank())
    }

    @Test
    fun `unavailable direct report cannot snapshot submit or change report state`() = runTest {
        val f = fixture()

        f.controller.submitPlaybackIssueReport()
        runCurrent()

        assertNoRemoteAttempt(f)
    }

    @Test
    fun `unavailable controller Report event is inert even with a retained enabled preference`() = runTest {
        val f = fixture()

        f.controller.onEvent(PlayerEvent.OnReportPlaybackIssue)
        runCurrent()

        assertNoRemoteAttempt(f)
    }

    @Test
    fun `unavailable real view model Report dispatch reaches the same inert boundary`() = runTest {
        val f = fixture()
        val viewModel = mockk<PlayerViewModel>(relaxed = true)
        PlayerViewModel::class.java.getDeclaredField("controller").apply {
            isAccessible = true
        }.set(viewModel, f.controller)
        every { viewModel.onEvent(any()) } answers { callOriginal() }

        viewModel.onEvent(PlayerEvent.OnReportPlaybackIssue)
        runCurrent()

        assertNoRemoteAttempt(f)
    }

    @Test
    fun `long loading cannot offer an unavailable remote report after its real delay`() = runTest {
        val f = fixture(PlayerUiState(
            playbackIssueReportsEnabled = true,
            showLoadingOverlay = true,
            isBuffering = true
        ))
        f.controller.scheduleLoadingIssueReportAvailability()
        runCurrent()
        advanceTimeBy(45_001L)
        runCurrent()

        assertFalse(f.ui.value.loadingIssueReportVisible)
        assertEquals(0L, f.ui.value.loadingIssueElapsedMs)
        coVerify(exactly = 0) { f.repository.submit(any()) }
    }

    @Test
    fun `a disabled preference remains an independent no submission control`() = runTest {
        val f = fixture(PlayerUiState(playbackIssueReportsEnabled = false))

        f.controller.onEvent(PlayerEvent.OnReportPlaybackIssue)
        runCurrent()

        assertNoRemoteAttempt(f)
    }

    @Test
    fun `sending and sent states retain their duplicate submission protection`() = runTest {
        for (status in listOf(PlaybackIssueReportStatus.Sending, PlaybackIssueReportStatus.Sent)) {
            val f = fixture(PlayerUiState(
                playbackIssueReportsEnabled = true,
                playbackIssueReportStatus = status,
                playbackIssueReportId = "owned-existing-report"
            ))

            f.controller.submitPlaybackIssueReport()
            runCurrent()

            assertNoRemoteAttempt(f)
        }
    }

    @Test
    fun `local loading diagnostics still record and remain readable without a report service`() = runTest {
        val f = fixture(PlayerUiState(playbackIssueReportsEnabled = false))

        f.controller.recordLoadingDiagnosticEvent(
            phase = "owned_fixture", message = "Preparing owned local content", detail = "local-only"
        )
        val snapshot = f.controller.buildPlaybackIssueLoadingInput("local-diagnostics")

        assertEquals(1, snapshot.events.size)
        assertEquals("owned_fixture", snapshot.events.single().phase)
        assertEquals("Preparing owned local content", snapshot.events.single().message)
        assertTrue(snapshot.rawEventLines.single().contains("phase=owned_fixture"))
        assertFalse(f.ui.value.loadingIssueReportVisible)
        coVerify(exactly = 0) { f.repository.submit(any()) }
    }

    @Test
    fun `repository rejects an absent endpoint before reading payload or calling the API`() = runTest {
        val api = mockk<PlaybackIssueReportApi>()
        val input = mockk<PlaybackIssueReportInput>()

        val result = PlaybackIssueReportRepository(api).submit(input)

        assertTrue(result.isFailure)
        assertEquals("Playback report endpoint is not configured", result.exceptionOrNull()?.message)
        verify { input wasNot Called }
        coVerify(exactly = 0) { api.createPlaybackIssueReport(any()) }
    }

    private fun assertNoRemoteAttempt(f: Fixture) {
        assertEquals("Unavailable reporting must return before opening a request", 7L, f.requestVersion.get())
        assertEquals(f.initialState, f.ui.value)
        verify(exactly = 0) { f.analytics.snapshot(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.repository.submit(any()) }
    }

    private data class Fixture(
        val controller: PlayerRuntimeController,
        val repository: PlaybackIssueReportRepository,
        val analytics: PlayerPlaybackAnalyticsDiagnostics,
        val ui: MutableStateFlow<PlayerUiState>,
        val initialState: PlayerUiState,
        val requestVersion: AtomicLong
    )

    private fun TestScope.fixture(initialState: PlayerUiState = PlayerUiState(
        playbackIssueReportsEnabled = true, error = "Owned playback stopped"
    )): Fixture {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val repository = mockk<PlaybackIssueReportRepository>()
        val analytics = mockk<PlayerPlaybackAnalyticsDiagnostics>()
        val ui = MutableStateFlow(initialState)
        val timeline = MutableStateFlow(PlaybackTimelineState())
        val requestVersion = AtomicLong(7L)
        // Follow the existing controller tests: real extension functions, fake native/service boundaries.
        PlayerRuntimeController::class.java.getDeclaredField("_uiState").apply {
            isAccessible = true
        }.set(controller, ui)
        PlayerRuntimeController::class.java.getDeclaredField("_playbackTimeline").apply {
            isAccessible = true
        }.set(controller, timeline)
        val analyticsSnapshot = PlayerPlaybackAnalyticsDiagnostics().snapshot(null, false, 0, 0L, 0L)
        every { analytics.snapshot(any(), any(), any(), any(), any()) } returns analyticsSnapshot
        coEvery { repository.submit(any()) } returns Result.failure(IllegalStateException("Owned fake submission boundary"))
        every { controller._uiState } returns ui
        every { controller._playbackTimeline } returns timeline
        every { controller.scope } returns backgroundScope
        every { controller.playbackIssueReportRepository } returns repository
        every { controller.playbackIssueReportRequestVersion } returns requestVersion
        every { controller.playbackAnalyticsDiagnostics } returns analytics
        every { controller.currentPlayerSettingsForReport } returns PlayerSettings()
        every { controller.lastPlaybackDiagnosticsForReport } returns LastPlaybackDiagnostics(timestampMs = 1L)
        every { controller.lastPlaybackIssueError } returns null
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.currentStreamUrl } returns "https://owned.example.invalid/fixture.mp4"
        every { controller.currentHeaders } returns emptyMap()
        every { controller.currentStreamResponseHeaders } returns emptyMap()
        every { controller._exoPlayer } returns null
        every { controller.mpvView } returns null
        every { controller.pauseOverlayJob } returns null
        every { controller.startupLoadingReportJob } returns null
        every { controller.hasRenderedFirstFrame } returns false
        every { controller.currentLoadingPhase } returns "idle"
        every { controller.lastLoadingDiagnosticSignature } returns ""
        every { controller.loadingDiagnosticEvents } returns ArrayDeque()
        every { controller.loadingDiagnosticRawEventLines } returns ArrayDeque()
        return Fixture(controller, repository, analytics, ui, initialState, requestVersion)
    }
}
