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
import com.nuvio.tv.domain.repository.WatchProgressRepository
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
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertSame
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
    fun `completion waiting for normalization cannot overwrite restarted progress or relatch completion`() = runTest {
        val fixture = fixture(PlayerUiState())
        val repository = fixture.controller.watchProgressRepository
        val normalizationEntered = CompletableDeferred<Unit>()
        val releaseNormalization = CompletableDeferred<Unit>()
        val saved = mutableListOf<WatchProgress>()
        var normalizationCalls = 0
        coEvery { repository.normalizeParentContentId(any(), any(), any()) } coAnswers {
            if (++normalizationCalls == 1) {
                normalizationEntered.complete(Unit)
                releaseNormalization.await()
            }
            "tt-fixture"
        }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }

        fixture.controller.saveWatchProgressInternal(position = 3_400_000L, duration = 3_600_000L)
        runCurrent()
        assertTrue(normalizationEntered.isCompleted)
        fixture.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        fixture.controller.saveWatchProgressInternal(position = 60_000L, duration = 3_600_000L)
        runCurrent()
        releaseNormalization.complete(Unit)
        runCurrent()

        assertEquals(listOf(60_000L), saved.map { it.position })
        assertFalse(fixture.controller.hasMarkedCurrentEpisodeCompleted)
        coVerify(exactly = 0) { repository.markAsCompleted(any(), any(), any()) }
    }

    @Test
    fun `restart progress is persisted after an already entered completion write finishes`() = runTest {
        val fixture = fixture(PlayerUiState())
        val repository = fixture.controller.watchProgressRepository
        val completionEntered = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        coEvery { repository.markAsCompleted(any(), 1, false) } coAnswers {
            completionEntered.complete(Unit)
            releaseCompletion.await()
            persisted += "complete"
        }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers {
            persisted += "resume:${firstArg<WatchProgress>().position}"
        }

        fixture.controller.saveWatchProgressInternal(position = 3_400_000L, duration = 3_600_000L)
        runCurrent()
        assertTrue(completionEntered.isCompleted)
        assertTrue(fixture.controller.hasMarkedCurrentEpisodeCompleted)
        fixture.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        fixture.controller.saveWatchProgressInternal(position = 60_000L, duration = 3_600_000L)
        runCurrent()
        assertTrue(persisted.isEmpty())
        releaseCompletion.complete(Unit)
        runCurrent()

        assertEquals(listOf("complete", "resume:60000"), persisted)
        assertFalse(fixture.controller.hasMarkedCurrentEpisodeCompleted)
    }

    @Test
    fun `restarting the next episode preserves the previous episode pending final save`() = runTest {
        val fixture = fixture(PlayerUiState())
        val repository = fixture.controller.watchProgressRepository
        val recommendations = fixture.controller.tvRecommendationManager
        val normalizationEntered = CompletableDeferred<Unit>()
        val releaseNormalization = CompletableDeferred<Unit>()
        val completed = mutableListOf<WatchProgress>()
        val saved = mutableListOf<WatchProgress>()
        coEvery { repository.normalizeParentContentId(any(), any(), any()) } coAnswers {
            if (arg<String>(1) == "tt-fixture:1:1") {
                normalizationEntered.complete(Unit)
                releaseNormalization.await()
            }
            "tt-fixture"
        }
        coEvery { repository.markAsCompleted(any(), 1, false) } coAnswers { completed += firstArg<WatchProgress>() }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }

        fixture.controller.saveWatchProgressInternal(position = 3_400_000L, duration = 3_600_000L)
        runCurrent()
        assertTrue(normalizationEntered.isCompleted)
        // The player has moved to a different episode while its outgoing final save is pending.
        every { fixture.controller.currentVideoId } returns "tt-fixture:1:2"
        every { fixture.controller.currentEpisode } returns 2
        fixture.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        fixture.controller.saveWatchProgressInternal(position = 60_000L, duration = 3_600_000L)
        runCurrent()
        releaseNormalization.complete(Unit)
        runCurrent()

        assertEquals(listOf("tt-fixture:1:1"), completed.map { it.videoId })
        assertEquals(listOf("tt-fixture:1:2"), saved.map { it.videoId })
        assertEquals(listOf(60_000L), saved.map { it.position })
        assertFalse(fixture.controller.hasMarkedCurrentEpisodeCompleted)
        coVerify(exactly = 0) { recommendations.onProgressRemoved(any()) }
        coVerify(exactly = 1) { recommendations.updateSingleWatchNextProgram(match { it.videoId == "tt-fixture:1:2" }) }
    }

    @Test
    fun `outgoing completion suppresses its already queued lower progress without latching the next episode`() = runTest {
        val fixture = fixture(PlayerUiState())
        val repository = fixture.controller.watchProgressRepository
        val releaseCompletionNormalization = CompletableDeferred<Unit>()
        val releaseLowerNormalization = CompletableDeferred<Unit>()
        val completed = mutableListOf<WatchProgress>()
        val saved = mutableListOf<WatchProgress>()
        var normalizationCalls = 0
        coEvery { repository.normalizeParentContentId(any(), any(), any()) } coAnswers {
            when (++normalizationCalls) {
                1 -> releaseCompletionNormalization.await()
                2 -> releaseLowerNormalization.await()
            }
            "tt-fixture"
        }
        coEvery { repository.markAsCompleted(any(), 1, false) } coAnswers { completed += firstArg<WatchProgress>() }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }

        fixture.controller.saveWatchProgressInternal(position = 3_400_000L, duration = 3_600_000L)
        fixture.controller.saveWatchProgressInternal(position = 30_000L, duration = 0L)
        runCurrent()
        assertEquals(2, normalizationCalls)
        assertFalse(fixture.controller.hasMarkedCurrentEpisodeCompleted)
        every { fixture.controller.currentVideoId } returns "tt-fixture:1:2"
        every { fixture.controller.currentEpisode } returns 2
        fixture.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        releaseCompletionNormalization.complete(Unit)
        runCurrent()
        releaseLowerNormalization.complete(Unit)
        runCurrent()
        fixture.controller.saveWatchProgressInternal(position = 60_000L, duration = 3_600_000L)
        runCurrent()

        assertEquals(listOf("tt-fixture:1:1"), completed.map { it.videoId })
        assertEquals(listOf("tt-fixture:1:2"), saved.map { it.videoId })
        assertEquals(listOf(60_000L), saved.map { it.position })
        assertFalse(fixture.controller.hasMarkedCurrentEpisodeCompleted)
    }

    @Test
    fun `returning to a completed episode after another episode opens a new viewing session`() = runTest {
        val fixture = fixture(PlayerUiState())
        val repository = fixture.controller.watchProgressRepository
        val completed = mutableListOf<WatchProgress>()
        val saved = mutableListOf<WatchProgress>()
        coEvery { repository.markAsCompleted(any(), 1, false) } coAnswers { completed += firstArg<WatchProgress>() }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }

        fixture.controller.saveWatchProgressInternal(position = 3_400_000L, duration = 3_600_000L)
        runCurrent()
        every { fixture.controller.currentVideoId } returns "tt-fixture:1:2"
        every { fixture.controller.currentEpisode } returns 2
        // Selecting another episode resets this controller latch during stream initialization.
        fixture.controller.hasMarkedCurrentEpisodeCompleted = false
        prepareDenied(fixture)
        fixture.controller.saveWatchProgressInternal(position = 60_000L, duration = 3_600_000L)
        runCurrent()
        every { fixture.controller.currentVideoId } returns "tt-fixture:1:1"
        every { fixture.controller.currentEpisode } returns 1
        fixture.controller.hasMarkedCurrentEpisodeCompleted = false
        prepareDenied(fixture)
        fixture.controller.saveWatchProgressInternal(position = 120_000L, duration = 3_600_000L)
        runCurrent()

        assertEquals(listOf("tt-fixture:1:1"), completed.map { it.videoId })
        assertEquals(listOf("tt-fixture:1:2", "tt-fixture:1:1"), saved.map { it.videoId })
        assertEquals(listOf(60_000L, 120_000L), saved.map { it.position })
    }

    @Test
    fun `returning after an unsaved failed episode still opens a fresh rewatch session`() = runTest {
        val fixture = fixture(PlayerUiState())
        val repository = fixture.controller.watchProgressRepository
        val resolver = fixture.controller.directDebridResolver
        val completed = mutableListOf<WatchProgress>()
        val saved = mutableListOf<WatchProgress>()
        coEvery { repository.markAsCompleted(any(), 1, false) } coAnswers { completed += firstArg<WatchProgress>() }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }
        // Both preparation requests are denied. No source, playback grant or network is supplied.
        coEvery { resolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) } returns false

        fixture.controller.saveWatchProgressInternal(position = 3_400_000L, duration = 3_600_000L)
        runCurrent()
        every { fixture.controller.currentVideoId } returns "tt-fixture:1:2"
        every { fixture.controller.currentEpisode } returns 2
        fixture.controller.hasMarkedCurrentEpisodeCompleted = false
        fixture.controller.preparePlaybackBeforeStart("https://category.example.invalid/unavailable.mp4", emptyMap(), true)
        runCurrent()
        // Episode 2 was selected, but never rendered a frame or emitted any progress save.
        every { fixture.controller.currentVideoId } returns "tt-fixture:1:1"
        every { fixture.controller.currentEpisode } returns 1
        fixture.controller.hasMarkedCurrentEpisodeCompleted = false
        fixture.controller.preparePlaybackBeforeStart("https://category.example.invalid/unavailable.mp4", emptyMap(), true)
        runCurrent()
        fixture.controller.saveWatchProgressInternal(position = 120_000L, duration = 3_600_000L)
        runCurrent()

        assertEquals(listOf("tt-fixture:1:1"), completed.map { it.videoId })
        assertEquals(listOf("tt-fixture:1:1"), saved.map { it.videoId })
        assertEquals(listOf(120_000L), saved.map { it.position })
        coVerify(exactly = 2) { resolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.player.setMediaSource(any()) }
    }

    @Test
    fun `new controller rewatch rejects an old completion and inactive reclaim attempts`() = runTest {
        val authority = PlayerProgressWriteAuthority()
        val repository = mockk<WatchProgressRepository>(relaxed = true)
        val old = fixture(PlayerUiState(), authority = authority, repository = repository)
        val current = fixture(PlayerUiState(), authority = authority, repository = repository, activate = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<WatchProgress>()
        var normalizations = 0
        coEvery { repository.normalizeParentContentId(any(), any(), any()) } coAnswers {
            if (++normalizations == 1) { entered.complete(Unit); release.await() }
            "tt-fixture"
        }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }

        old.controller.saveWatchProgressInternal(3_400_000L, 3_600_000L)
        runCurrent()
        assertTrue(entered.isCompleted)
        foreground(current)
        prepareDenied(current)
        background(old) // A late old ON_PAUSE/onDispose must not revoke B's activation.
        current.controller.saveWatchProgressInternal(120_000L, 3_600_000L)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        // Neither a late preparation, Start0 nor final autosave may reactivate old ownership.
        prepareDenied(old)
        old.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        old.controller.saveWatchProgressInternal(900_000L, 3_600_000L)
        runCurrent()
        current.controller.saveWatchProgressInternal(180_000L, 3_600_000L)
        runCurrent()

        assertEquals(listOf(120_000L, 180_000L), saved.map { it.position })
        assertFalse(current.controller.hasMarkedCurrentEpisodeCompleted)
        coVerify(exactly = 0) { repository.markAsCompleted(any(), any(), any()) }
        verify(exactly = 0) { old.player.seekTo(any()) }
    }

    @Test
    fun `new controller resume waits for already entered old completion persistence`() = runTest {
        val authority = PlayerProgressWriteAuthority()
        val repository = mockk<WatchProgressRepository>(relaxed = true)
        val old = fixture(PlayerUiState(), authority = authority, repository = repository)
        val current = fixture(PlayerUiState(), authority = authority, repository = repository, activate = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        coEvery { repository.markAsCompleted(any(), 1, false) } coAnswers {
            entered.complete(Unit)
            release.await()
            persisted += "old-completion"
        }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers {
            persisted += "new-resume:${firstArg<WatchProgress>().position}"
        }

        old.controller.saveWatchProgressInternal(3_400_000L, 3_600_000L)
        runCurrent()
        assertTrue(entered.isCompleted)
        background(old)
        foreground(current)
        prepareDenied(current)
        current.controller.saveWatchProgressInternal(120_000L, 3_600_000L)
        runCurrent()
        assertTrue(persisted.isEmpty())
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf("old-completion", "new-resume:120000"), persisted)
        assertFalse(current.controller.hasMarkedCurrentEpisodeCompleted)
    }

    @Test
    fun `new controller preserves captured finals for another episode or profile`() = runTest {
        for ((nextProfile, nextEpisode) in listOf(1 to 2, 2 to 1)) {
            val authority = PlayerProgressWriteAuthority()
            val repository = mockk<WatchProgressRepository>(relaxed = true)
            val old = fixture(PlayerUiState(), authority = authority, repository = repository)
            val current = fixture(PlayerUiState(), authority = authority, repository = repository,
                profileId = nextProfile, episode = nextEpisode, activate = false)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val completed = mutableListOf<Pair<Int, WatchProgress>>()
            val saved = mutableListOf<Pair<Int, WatchProgress>>()
            val oldRecommendations = old.controller.tvRecommendationManager
            coEvery { repository.normalizeParentContentId(any(), any(), any()) } coAnswers {
                if (arg<String>(1) == "tt-fixture:1:1" && arg<Int>(2) == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                "tt-fixture"
            }
            coEvery { repository.markAsCompleted(any(), any(), false) } coAnswers {
                completed += arg<Int>(1) to firstArg<WatchProgress>()
            }
            coEvery { repository.saveProgress(any(), any(), true) } coAnswers {
                saved += arg<Int>(1) to firstArg<WatchProgress>()
            }

            old.controller.saveWatchProgressInternal(3_400_000L, 3_600_000L)
            runCurrent()
            assertTrue(entered.isCompleted)
            background(old)
            foreground(current)
            prepareDenied(current)
            current.controller.saveWatchProgressInternal(120_000L, 3_600_000L)
            runCurrent()
            release.complete(Unit)
            runCurrent()

            assertEquals(listOf(1 to "tt-fixture:1:1"), completed.map { it.first to it.second.videoId })
            assertEquals(listOf(nextProfile to "tt-fixture:1:$nextEpisode"), saved.map { it.first to it.second.videoId })
            assertFalse(current.controller.hasMarkedCurrentEpisodeCompleted)
            coVerify(exactly = 0) { oldRecommendations.onProgressRemoved(any()) }
        }
    }

    @Test
    fun `retained older controller can reacquire only after its real foreground reentry`() = runTest {
        val authority = PlayerProgressWriteAuthority()
        val repository = mockk<WatchProgressRepository>(relaxed = true)
        val first = fixture(PlayerUiState(), authority = authority, repository = repository)
        val second = fixture(PlayerUiState(), authority = authority, repository = repository, activate = false)
        val saved = mutableListOf<WatchProgress>()
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }
        first.controller.saveWatchProgressInternal(60_000L, 3_600_000L)
        runCurrent()
        background(first)
        foreground(second)
        prepareDenied(second)
        second.controller.saveWatchProgressInternal(120_000L, 3_600_000L)
        runCurrent()
        background(second)
        // This is the retained PlayerScreen A receiving its own ON_RESUME, without a new VM.
        foreground(first)
        first.controller.saveWatchProgressInternal(150_000L, 3_600_000L)
        runCurrent()
        assertEquals(listOf(60_000L, 120_000L, 150_000L), saved.map { it.position })
        first.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        first.controller.saveWatchProgressInternal(180_000L, 3_600_000L)
        runCurrent()
        second.controller.onEvent(PlayerEvent.OnStartFromBeginning)
        second.controller.saveWatchProgressInternal(900_000L, 3_600_000L)
        runCurrent()

        assertEquals(listOf(60_000L, 120_000L, 150_000L, 180_000L), saved.map { it.position })
        verify(exactly = 1) { first.player.seekTo(0L) }
        verify(exactly = 0) { second.player.seekTo(any()) }
    }

    @Test
    fun `ordinary pause resume keeps pending final saves and completed state without a competing owner`() = runTest {
        for (captureBeforePause in listOf(true, false)) {
            val fixture = fixture(PlayerUiState())
            val repository = fixture.controller.watchProgressRepository
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { repository.normalizeParentContentId(any(), any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                "tt-fixture"
            }
            if (captureBeforePause) fixture.controller.saveWatchProgressInternal(3_400_000L, 3_600_000L)
            background(fixture)
            // releasePlayer/onCleared may capture the final snapshot after ON_PAUSE.
            if (!captureBeforePause) fixture.controller.saveWatchProgressInternal(3_400_000L, 3_600_000L)
            runCurrent()
            assertTrue(entered.isCompleted)
            foreground(fixture)
            release.complete(Unit)
            runCurrent()
            fixture.controller.saveWatchProgressInternal(30_000L, 0L)
            runCurrent()

            assertTrue(fixture.controller.hasMarkedCurrentEpisodeCompleted)
            coVerify(exactly = 1) { repository.markAsCompleted(any(), 1, false) }
            coVerify(exactly = 0) { repository.saveProgress(any(), 1, any()) }
        }
    }

    @Test
    fun `cold preparation before first foreground activates its recorded owner without another prepare`() = runTest {
        val fixture = fixture(PlayerUiState(), activate = false)
        val repository = fixture.controller.watchProgressRepository
        val resolver = fixture.controller.directDebridResolver
        val saved = mutableListOf<WatchProgress>()
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }

        prepareDenied(fixture)
        fixture.controller.saveWatchProgressInternal(60_000L, 3_600_000L)
        runCurrent()
        assertTrue(saved.isEmpty())
        foreground(fixture)
        fixture.controller.saveWatchProgressInternal(120_000L, 3_600_000L)
        runCurrent()

        assertEquals(listOf(120_000L), saved.map { it.position })
        coVerify(exactly = 1) { resolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.player.setMediaSource(any()) }
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

    @Test
    fun `progress keeps the playback start account scope across asynchronous normalization`() = runTest {
        val repository = mockk<WatchProgressRepository>(relaxed = true)
        val fixture = fixture(PlayerUiState(), repository = repository, capturedPlaybackScope = "original-account")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var currentAccountScope = "original-account"
        val saved = mutableListOf<WatchProgress>()
        coEvery { repository.captureLocalPlaybackScope(1) } answers { currentAccountScope }
        coEvery { repository.normalizeParentContentId(any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            "tt-fixture"
        }
        coEvery { repository.saveProgress(any(), 1, true) } coAnswers { saved += firstArg<WatchProgress>() }

        fixture.controller.saveWatchProgressInternal(120_000L, 3_600_000L)
        runCurrent()
        assertTrue(entered.isCompleted)
        currentAccountScope = "replacement-account"
        release.complete(Unit)
        runCurrent()

        assertEquals("original-account", saved.single().localPlaybackScope)
        assertEquals("tt-fixture:1:1", saved.single().videoId)
        coVerify(exactly = 0) { repository.captureLocalPlaybackScope(any()) }
    }

    private data class Fixture(
        val controller: PlayerRuntimeController,
        val player: ExoPlayer,
        val ui: MutableStateFlow<PlayerUiState>,
        val timeline: MutableStateFlow<PlaybackTimelineState>
    )

    private fun TestScope.fixture(
        initialState: PlayerUiState,
        alreadyCompleted: Boolean = false,
        authority: PlayerProgressWriteAuthority = PlayerProgressWriteAuthority(),
        repository: WatchProgressRepository = mockk(relaxed = true),
        profileId: Int = 1,
        episode: Int = 1,
        activate: Boolean = true,
        capturedPlaybackScope: String? = null
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
        val coordinator = PlayerProgressWriteCoordinator(authority)
        every { controller.progressWriteCoordinator } returns coordinator
        every { controller.watchProgressRepository } returns repository
        every { controller.localPlaybackScope } returns CompletableDeferred<String?>(value = capturedPlaybackScope)
        every { controller.party.state } returns MutableStateFlow(PartyUiState())
        every { controller.party.playback } returns MutableStateFlow<PartyPlayback?>(null)
        every { controller.contentId } returns "tt-fixture"
        every { controller.contentType } returns "series"
        every { controller.currentVideoId } returns "tt-fixture:1:$episode"
        every { controller.currentSeason } returns 1
        every { controller.currentEpisode } returns episode
        every { controller.profileId } returns profileId
        every { controller.hasMarkedCurrentEpisodeCompleted } answers { completed }
        every { controller.hasMarkedCurrentEpisodeCompleted = any() } answers { completed = firstArg() }
        every { controller.pauseOverlayJob } returns null
        every { controller.autoSkippedIntervalKeys } returns mutableSetOf()
        every { controller.pendingPlaybackRawEventLines } returns ArrayDeque()
        every { controller.seekProgressSyncDebounceMs } returns 700L
        // Stub the passed persistence boundary directly. Chained stubbing through a relaxed
        // controller getter can replace it with a hierarchical child mock, hiding writes from
        // the shared repository used by the two-controller regressions.
        coEvery {
            repository.normalizeParentContentId(any(), any(), any())
        } returns "tt-fixture"
        assertSame(repository, controller.watchProgressRepository)
        if (activate) {
            coordinator.activateForeground(controller.currentProgressWriteOwner())
            coordinator.onPlaybackOwnerChanged(checkNotNull(controller.currentProgressWriteOwner()))
        }
        return Fixture(controller, player, ui, timeline)
    }

    private fun TestScope.prepareDenied(fixture: Fixture) {
        val resolver = fixture.controller.directDebridResolver
        coEvery { resolver.isPlaybackAuthorized(any(), any(), any(), any(), any(), any()) } returns false
        fixture.controller.preparePlaybackBeforeStart("https://playback.example.invalid/unavailable.mp4", emptyMap(), true)
        runCurrent()
    }

    private fun foreground(fixture: Fixture) {
        fixture.controller.progressWriteCoordinator.activateForeground(fixture.controller.currentProgressWriteOwner())
    }

    private fun background(fixture: Fixture) {
        fixture.controller.progressWriteCoordinator.deactivateForeground()
    }
}
