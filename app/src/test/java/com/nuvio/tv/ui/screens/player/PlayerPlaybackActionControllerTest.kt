package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.core.party.PartyPlayback
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls the production event handler; only the engine, persistence and source-fetch boundaries are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPlaybackActionControllerTest {
    @After
    fun cleanUpMocks() {
        unmockkObject(NuvioExoPlayerPerformanceHelper)
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
    }

    @Test
    fun `start from beginning seeks exactly and resumes a paused player`() = runTest {
        val fixture = fixture(PlayerUiState(isPlaying = false, showPauseOverlay = true))

        fixture.controller.onEvent(PlayerEvent.OnStartFromBeginning)

        verify(exactly = 1) { fixture.player.setSeekParameters(SeekParameters.EXACT) }
        verify(exactly = 1) { fixture.player.seekTo(0L) }
        verify(exactly = 1) { fixture.player.play() }
        assertFalse(fixture.ui.value.showPauseOverlay)
        assertFalse(fixture.ui.value.playbackEnded)
        assertEquals(0L, fixture.timeline.value.currentPosition)
        assertEquals(3_600_000L, fixture.timeline.value.duration)
    }

    @Test
    fun `starting over after completion lets the rewatch save new resume progress`() = runTest {
        val fixture = fixture(PlayerUiState(playbackEnded = true), alreadyCompleted = true)
        val repository = fixture.controller.watchProgressRepository
        val saved = slot<WatchProgress>()
        coEvery {
            repository.saveProgress(capture(saved), 1, true)
        } just Runs

        fixture.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        fixture.controller.saveWatchProgressInternal(position = 60_000L, duration = 3_600_000L)
        runCurrent()

        verify(exactly = 1) { fixture.player.seekTo(0L) }
        verify(exactly = 1) { fixture.player.play() }
        assertFalse(fixture.ui.value.playbackEnded)
        assertTrue(saved.isCaptured)
        assertEquals(60_000L, saved.captured.position)
        assertEquals("tt-fixture:1:1", saved.captured.videoId)
        assertFalse(saved.captured.isCompleted())
        // A rewatch updates playback progress without changing the user's watched history.
        coVerify(exactly = 0) {
            repository.markAsCompleted(any(), any(), any())
        }
    }

    @Test
    fun `manual next opens next episode source picker without playing or switching the current episode`() = runTest {
        val next = Video(id = "tt-fixture:1:2", title = "Episode 2", released = null,
            thumbnail = null, season = 1, episode = 2, overview = null)
        val nextInfo = NextEpisodeInfo(next.id, 1, 2, next.title, null, null, null, true, unairedMessage = null)
        val fixture = fixture(PlayerUiState(currentVideoId = "tt-fixture:1:1", currentSeason = 1,
            currentEpisode = 1, nextEpisode = nextInfo))
        every { fixture.controller.nextEpisodeVideo } returns next
        every { fixture.controller.playerSettingsDataStore.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
                streamAutoPlayNextEpisodeEnabled = false, streamAutoPlayPreferBingeGroupForNextEpisode = false)
        )
        // Search itself is outside this controller transition; the actual picker still opens.
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
        every { fixture.controller.loadEpisodesIfNeeded() } just Runs
        every { fixture.controller.loadStreamsForEpisode(next, true) } just Runs

        fixture.controller.onEvent(PlayerEvent.OnPlayNextEpisode)
        runCurrent()

        assertTrue(fixture.ui.value.showEpisodesPanel)
        assertTrue(fixture.ui.value.showEpisodeStreams)
        assertEquals("tt-fixture:1:1", fixture.ui.value.currentVideoId)
        assertNull(fixture.ui.value.postPlayMode)
        verify(exactly = 1) { fixture.controller.loadStreamsForEpisode(next, true) }
        verify(exactly = 0) { fixture.player.play() }
        verify(exactly = 0) { fixture.player.setMediaSource(any()) }
    }

    private data class Fixture(
        val controller: PlayerRuntimeController,
        val player: ExoPlayer,
        val ui: MutableStateFlow<PlayerUiState>,
        val timeline: MutableStateFlow<PlaybackTimelineState>
    )

    private fun TestScope.fixture(
        initialState: PlayerUiState,
        alreadyCompleted: Boolean = false
    ): Fixture {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val player = mockk<ExoPlayer>(relaxed = true)
        val ui = MutableStateFlow(initialState)
        val timeline = MutableStateFlow(PlaybackTimelineState(currentPosition = 816_000L, duration = 3_600_000L))
        // Default arguments of the controller's real timeline method access these backing fields.
        // Initialize only the in-memory state; media, accounts and repositories remain fakes.
        PlayerRuntimeController::class.java.getDeclaredField("_uiState").apply { isAccessible = true }.set(controller, ui)
        PlayerRuntimeController::class.java.getDeclaredField("_playbackTimeline").apply { isAccessible = true }.set(controller, timeline)
        var completed = alreadyCompleted
        mockkObject(NuvioExoPlayerPerformanceHelper)
        every { NuvioExoPlayerPerformanceHelper.enabled } returns false
        every { controller._uiState } returns ui
        every { controller._playbackTimeline } returns timeline
        every { controller.updatePlaybackTimeline(any(), any(), any(), any(), any()) } answers { callOriginal() }
        every { controller._exoPlayer } returns player
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.scope } returns backgroundScope
        every { controller.resumeRequestGate } returns PlayerResumeRequestGate()
        every { controller.party.state } returns MutableStateFlow(PartyUiState())
        every { controller.party.playback } returns MutableStateFlow<PartyPlayback?>(null)
        every { controller.contentId } returns "tt-fixture"
        every { controller.contentType } returns "series"
        every { controller.currentVideoId } returns "tt-fixture:1:1"
        every { controller.currentSeason } returns 1
        every { controller.currentEpisode } returns 1
        every { controller.profileId } returns 1
        every { controller.hasMarkedCurrentEpisodeCompleted } answers { completed }
        every { controller.hasMarkedCurrentEpisodeCompleted = any() } answers { completed = firstArg() }
        every { controller.pauseOverlayJob } returns null
        every { controller.autoSkippedIntervalKeys } returns mutableSetOf()
        every { controller.seekProgressSyncDebounceMs } returns 700L
        coEvery {
            controller.watchProgressRepository.normalizeParentContentId(any(), any(), any())
        } returns "tt-fixture"
        return Fixture(controller, player, ui, timeline)
    }
}
