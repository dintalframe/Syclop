package com.nuvio.tv.ui.screens.player

// Real Retry/source handlers and restore; native release/construction and track application are fakes.
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import com.nuvio.tv.core.party.PartyPlayback
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingPlaybackOrigin
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.Stream
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManualRetryContinuationTest {
    @After fun cleanup() {
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerInitializationKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTrackSelectionKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTorrentKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerPlaybackEventsKt")
    }

    @Test fun `manual source selection preserves paused Turkish audio and explicit Off on the selected file`() = runTest {
        val f = sourceFixture(paused = true, subtitlesOff = true)

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        runCurrent()

        val handoff = f.handoffs.single()
        assertTrue(handoff.startPaused)
        assertEquals(InternalPlayerEngine.EXOPLAYER, handoff.engineOverride)
        assertFalse(handoff.allowEngineFailover)
        assertSelectedSourceAndOwner(f, handoff)
        assertEquals("tr", handoff.trackPreferenceBeforeDiscovery?.audio?.language)
        assertNull(handoff.trackPreferenceBeforeDiscovery?.audio?.trackId)
        assertEquals(1, f.ui.value.selectedAudioTrackIndex)
        assertEquals("source-b-tr", f.ui.value.audioTracks[1].trackId)
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
        assertTrue(f.controller.subtitleDisabledByPersistedPreference)
    }

    @Test fun `playing source selection restores known embedded languages with new file track identifiers`() = runTest {
        val f = sourceFixture(paused = false, subtitlesOff = false)

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        runCurrent()

        val handoff = f.handoffs.single()
        assertFalse(handoff.startPaused)
        assertSelectedSourceAndOwner(f, handoff)
        assertEquals("tr", handoff.trackPreferenceBeforeDiscovery?.audio?.language)
        assertNull(handoff.trackPreferenceBeforeDiscovery?.audio?.trackId)
        val text = handoff.trackPreferenceBeforeDiscovery?.subtitle as?
            PlayerRuntimeController.RememberedSubtitleSelection.Internal
        assertEquals("en", text?.track?.language)
        assertNull(text?.track?.trackId)
        assertNull(text?.track?.indexHint)
        assertNull(text?.track?.languageIndexHint)
        assertEquals(1, f.ui.value.selectedAudioTrackIndex)
        assertEquals("source-b-tr", f.ui.value.audioTracks[1].trackId)
        assertEquals(1, f.ui.value.selectedSubtitleTrackIndex)
        assertEquals("source-b-en-text", f.ui.value.subtitleTracks[1].trackId)
    }

    @Test fun `source selection does not pretend an undetermined audio language is Turkish`() = runTest {
        val f = sourceFixture(paused = true, subtitlesOff = true)
        every { f.oldPlayer.currentTracks } returns priorTracks(subtitlesOff = true, audioLanguage = "und")

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        runCurrent()

        val handoff = f.handoffs.single()
        assertTrue(handoff.startPaused)
        assertSelectedSourceAndOwner(f, handoff)
        assertNull(handoff.trackPreferenceBeforeDiscovery?.audio)
        assertEquals(PlayerRuntimeController.RememberedSubtitleSelection.Disabled,
            handoff.trackPreferenceBeforeDiscovery?.subtitle)
        assertEquals(0, f.ui.value.selectedAudioTrackIndex)
        assertEquals("en", f.ui.value.audioTracks[0].language)
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
    }

    @Test fun `source selection rejects non-language primary tags even when subtags are present`() = runTest {
        for (language in listOf("und-Latn", "und-US", "mul-Latn", "zxx-US")) {
            val f = sourceFixture(paused = true, subtitlesOff = true)
            every { f.oldPlayer.currentTracks } returns priorTracks(subtitlesOff = true, audioLanguage = language)

            f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
            runCurrent()

            val handoff = f.handoffs.single()
            assertSelectedSourceAndOwner(f, handoff)
            assertNull(language, handoff.trackPreferenceBeforeDiscovery?.audio)
            assertEquals(PlayerRuntimeController.RememberedSubtitleSelection.Disabled,
                handoff.trackPreferenceBeforeDiscovery?.subtitle)
            assertEquals(0, f.ui.value.selectedAudioTrackIndex)
            assertEquals("en", f.ui.value.audioTracks[0].language)
            assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
        }
    }

    @Test fun `startup source selection retains the existing engine and failover policy`() = runTest {
        // Legacy startup still owns an asynchronous saved read rather than an in-memory visit.
        val f = sourceFixture(paused = true, subtitlesOff = true, deferInitialization = true)
        every { f.controller.hasRenderedFirstFrame } returns false

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        runCurrent()

        val handoff = f.handoffs.single()
        assertFalse(handoff.startPaused)
        assertNull(handoff.engineOverride)
        assertTrue(handoff.allowEngineFailover)
        assertNull(handoff.trackPreferenceBeforeDiscovery)
        assertSelectedSourceAndOwner(f, handoff)
    }

    @Test fun `rendered source change starts at current position and a held older resume cannot rewind it`() = runTest {
        val f = sourceFixture(paused = true, subtitlesOff = true)
        val entered = CompletableDeferred<Unit>()
        val olderSaved = CompletableDeferred<WatchProgress>()
        every { f.controller.watchProgressRepository.getEpisodeProgress("tt-retry", 1, 2, 7) } returns flow {
            entered.complete(Unit)
            emit(olderSaved.await())
        }
        f.controller.loadSavedProgressFor(1, 2)
        runCurrent()
        assertTrue(entered.isCompleted)
        assertSame(f.oldPlayer, f.controller._exoPlayer)
        assertEquals(45_000L, f.controller.currentPlaybackPositionMs())

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        val initialHandoff = f.handoffs.single() // Before running or releasing saved storage.
        runCurrent()
        olderSaved.complete(WatchProgress("tt-retry", "series", "Owned fixture", null, null, null,
            "tt-retry:1:2", 1, 2, null, 12_000L, 130_000L, 123L))
        runCurrent()

        assertSelectedSourceAndOwner(f, initialHandoff)
        assertEquals(45_000L, f.controller.resolvePendingInitialResumePosition())
        assertEquals(1, f.handoffs.size)
    }

    @Test fun `an old queued resume entry cannot erase the new source initial position`() = runTest {
        val f = sourceFixture(paused = true, subtitlesOff = true, deferInitialization = true)
        val queueEntryPassed = CompletableDeferred<Unit>()
        every { f.controller.watchProgressRepository.getEpisodeProgress("tt-retry", 1, 2, 7) } returns flow<WatchProgress?> {
            awaitCancellation()
        }

        f.controller.loadSavedProgressFor(1, 2) // Queued; it must not run before source selection.
        backgroundScope.launch { queueEntryPassed.complete(Unit) }
        assertFalse(queueEntryPassed.isCompleted)

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        assertTrue(f.handoffs.isEmpty()) // Construction observation is queued after the obsolete Job.
        runCurrent()

        assertTrue(queueEntryPassed.isCompleted)
        val handoff = f.handoffs.single()
        assertSelectedSourceAndOwner(f, handoff)
        assertTrue(handoff.startPaused)
    }

    @Test fun `a covered playback visit cannot carry source continuity into the foreground owner`() = runTest {
        val f = sourceFixture(paused = true, subtitlesOff = true)
        f.controller.progressWriteCoordinator.deactivateForeground()

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        runCurrent()

        assertTrue(f.handoffs.isEmpty())
        assertSame(f.oldPlayer, f.controller._exoPlayer)
        assertEquals("https://playback.example.invalid/granted/selected.mp4", f.controller.currentStreamUrl)
    }

    @Test fun `a background playback visit cannot start a source continuity handoff`() = runTest {
        val f = sourceFixture(paused = true, subtitlesOff = true)
        every { f.controller.isInBackground } returns true

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        runCurrent()

        assertTrue(f.handoffs.isEmpty())
        assertSame(f.oldPlayer, f.controller._exoPlayer)
    }

    @Test fun `manual Retry continues the same playing file at 45 seconds with Turkish and English`() = runTest {
        val f = fixture(paused = false, subtitlesOff = false)

        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        val handoff = f.handoffs.single()
        assertEquals(45_000L, handoff.initialMediaPositionMs)
        assertFalse(handoff.startPaused)
        assertEquals(InternalPlayerEngine.EXOPLAYER, handoff.engineOverride)
        assertFalse(handoff.allowEngineFailover)
        assertSameFileAndOwner(f, handoff)
        assertEquals(1, f.ui.value.selectedAudioTrackIndex)
        assertEquals("tr", f.ui.value.audioTracks[1].language)
        assertEquals("audio-tr", f.ui.value.audioTracks[1].trackId)
        assertEquals(1, f.ui.value.selectedSubtitleTrackIndex)
        assertEquals("en", f.ui.value.subtitleTracks[1].language)
        assertEquals("sub-en", f.ui.value.subtitleTracks[1].trackId)
    }

    @Test fun `manual Retry preserves pause and explicit Off despite an available English text track`() = runTest {
        val f = fixture(paused = true, subtitlesOff = true)

        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        val handoff = f.handoffs.single()
        assertEquals(45_000L, handoff.initialMediaPositionMs)
        assertTrue(handoff.startPaused)
        assertEquals(InternalPlayerEngine.EXOPLAYER, handoff.engineOverride)
        assertFalse(handoff.allowEngineFailover)
        assertSameFileAndOwner(f, handoff)
        assertEquals(1, f.ui.value.selectedAudioTrackIndex)
        assertEquals("audio-tr", f.ui.value.audioTracks[1].trackId)
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
        assertTrue(f.controller.subtitleDisabledByPersistedPreference)
    }

    @Test fun `actual subtitle Off event disables the current player before manual Retry captures it`() = runTest {
        val f = fixture(paused = true, subtitlesOff = false)
        every { f.controller.disableSubtitles() } answers { callOriginal() }
        f.ui.value = f.ui.value.copy(error = null)
        assertFalse(C.TRACK_TYPE_TEXT in f.oldPlayer.trackSelectionParameters.disabledTrackTypes)

        f.controller.onEvent(PlayerEvent.OnDisableSubtitles)
        runCurrent()

        assertTrue(C.TRACK_TYPE_TEXT in f.oldPlayer.trackSelectionParameters.disabledTrackTypes)
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
        assertEquals(PlayerRuntimeController.RememberedSubtitleSelection.Disabled,
            f.controller.rememberedTrackPreference?.subtitle)
        f.ui.value = f.ui.value.copy(error = "Owned fixture interrupted")
        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        assertEquals(45_000L, f.handoffs.single().initialMediaPositionMs)
        assertTrue(f.handoffs.single().startPaused)
        assertSameFileAndOwner(f, f.handoffs.single())
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
        assertTrue(f.controller.subtitleDisabledByPersistedPreference)
    }

    @Test fun `a background Retry cannot release or restart the retained file`() = runTest {
        val f = fixture(paused = true, subtitlesOff = true)
        every { f.controller.isInBackground } returns true

        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        assertTrue(f.handoffs.isEmpty())
        assertEquals("Owned fixture interrupted", f.ui.value.error)
        assertEquals(45_000L, f.controller.currentPlaybackPositionMs())
    }

    @Test fun `a covered controller cannot Retry over the foreground playback visit`() = runTest {
        val f = fixture(paused = false, subtitlesOff = false)
        f.controller.progressWriteCoordinator.deactivateForeground()

        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        assertTrue(f.handoffs.isEmpty())
        assertEquals("Owned fixture interrupted", f.ui.value.error)
        assertEquals(45_000L, f.controller.currentPlaybackPositionMs())
    }

    @Test fun `repeated Retry before an engine is rebuilt retains pause position tracks and engine`() = runTest {
        val f = fixture(paused = true, subtitlesOff = true)

        f.controller.onEvent(PlayerEvent.OnRetry)
        // The initializer received its request, but no replacement engine exists yet.
        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        val handoff = f.handoffs.last()
        assertEquals(45_000L, handoff.initialMediaPositionMs)
        assertTrue(handoff.startPaused)
        assertEquals(InternalPlayerEngine.EXOPLAYER, handoff.engineOverride)
        assertFalse(handoff.allowEngineFailover)
        assertSameFileAndOwner(f, handoff)
        assertEquals(1, f.ui.value.selectedAudioTrackIndex)
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
    }

    @Test fun `a startup Retry retains the existing startup engine choice and failover`() = runTest {
        val f = fixture(paused = false, subtitlesOff = false)
        every { f.controller.hasRenderedFirstFrame } returns false

        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        val handoff = f.handoffs.single()
        assertNull(handoff.engineOverride)
        assertTrue(handoff.allowEngineFailover)
    }

    @Test fun `same video manual Retry preserves the live subtitle calibration`() = runTest {
        val f = fixture(paused = true, subtitlesOff = true)
        f.ui.value = f.ui.value.copy(subtitleDelayMs = 1500)
        f.controller.subtitleDelayUs.set(1_500_000L)

        f.controller.onEvent(PlayerEvent.OnRetry)
        runCurrent()

        val handoff = f.handoffs.single()
        assertSameFileAndOwner(f, handoff)
        assertEquals(1500, handoff.subtitleDelayMs)
        assertEquals(1_500_000L, handoff.subtitleDelayUs)
    }

    @Test fun `same video source selection preserves the live subtitle calibration`() = runTest {
        val f = sourceFixture(paused = true, subtitlesOff = true)
        f.ui.value = f.ui.value.copy(subtitleDelayMs = 1500)
        f.controller.subtitleDelayUs.set(1_500_000L)

        f.controller.onEvent(PlayerEvent.OnSourceStreamSelected(selectedSource()))
        runCurrent()

        val handoff = f.handoffs.single()
        assertSelectedSourceAndOwner(f, handoff)
        assertEquals(1500, handoff.subtitleDelayMs)
        assertEquals(1_500_000L, handoff.subtitleDelayUs)
    }

    private suspend fun assertSameFileAndOwner(f: Fixture, handoff: Handoff) {
        assertEquals("https://playback.example.invalid/granted/selected.mp4", handoff.url)
        assertEquals(mapOf("User-Agent" to "OwnedFixture"), handoff.headers)
        assertEquals("c".repeat(40), handoff.infoHash)
        assertEquals(2, handoff.fileIndex)
        assertEquals(PlayerProgressWriteOwner(7, "tt-retry", "series", "tt-retry:1:2", 1, 2), handoff.owner)
        assertSame(f.origin, handoff.origin)
        assertEquals("fixture-account-A", handoff.origin.await().localPlaybackScope)
        assertEquals(f.leaseBeforeRetry, handoff.lease)
    }

    private fun selectedSource() = Stream(name = "Owned source B", title = null, description = null,
        url = "https://playback.example.invalid/granted/alternative.mp4", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Owned fixture", addonLogo = null)

    private suspend fun assertSelectedSourceAndOwner(f: Fixture, handoff: Handoff) {
        assertEquals("https://playback.example.invalid/granted/alternative.mp4", handoff.url)
        assertEquals(emptyMap<String, String>(), handoff.headers)
        assertEquals(45_000L, handoff.initialMediaPositionMs)
        assertEquals(PlayerProgressWriteOwner(7, "tt-retry", "series", "tt-retry:1:2", 1, 2), handoff.owner)
        assertSame(f.origin, handoff.origin)
        assertEquals("fixture-account-A", handoff.origin.await().localPlaybackScope)
        assertEquals(f.leaseBeforeRetry, handoff.lease)
    }

    private fun TestScope.sourceFixture(paused: Boolean, subtitlesOff: Boolean,
        deferInitialization: Boolean = false): Fixture {
        val f = fixture(paused, subtitlesOff, deferInitialization = deferInitialization,
            replacementAudio = listOf(TrackInfo(0, "English", "en", "audio-tr", isSelected = true),
                TrackInfo(1, "Turkish", "tr", "source-b-tr")),
            replacementText = listOf(TrackInfo(0, "French", "fr", "sub-en", isSelected = true),
                TrackInfo(1, "English", "en", "source-b-en-text")))
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTorrentKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerPlaybackEventsKt")
        every { f.controller.sourceStreamsScope } returns null
        every { f.controller.nextEpisodeAutoPlayJob } returns null
        every { f.controller.stopTorrentStream() } just Runs
        every { f.controller.flushPlaybackSnapshotForSwitchOrExit() } just Runs
        every { f.controller.applyStreamAudioDelay(any(), any()) } just Runs
        every { f.controller.streamReuseLastLinkEnabled } returns false
        every { f.controller.isServerStream } returns false
        every { f.controller.releasePlayer(false, false) } answers {
            f.controller._exoPlayer = null
            f.controller.resetPlaybackTimeline()
        }
        val cacheProfile = mockk<ProfileManager>()
        every { cacheProfile.activeProfileId } returns MutableStateFlow(7)
        val cache = spyk(BingeGroupCacheDataStore(mockk(relaxed = true), cacheProfile))
        coEvery { cache.replace(any(), any(), any(), any(), any(), any()) } just Runs
        every { f.controller.bingeGroupCacheDataStore } returns cache
        every { f.controller.watchProgressRepository.getEpisodeProgress("tt-retry", 1, 2, 7) } returns flowOf(
            WatchProgress("tt-retry", "series", "Owned fixture", null, null, null, "tt-retry:1:2", 1, 2,
                null, 45_000L, 130_000L, 123L))
        f.ui.value = f.ui.value.copy(error = null)
        return f
    }

    private data class Handoff(
        val url: String,
        val headers: Map<String, String>,
        val infoHash: String?,
        val fileIndex: Int?,
        val engineOverride: InternalPlayerEngine?,
        val allowEngineFailover: Boolean,
        val startPaused: Boolean,
        val initialMediaPositionMs: Long,
        val owner: PlayerProgressWriteOwner?,
        val origin: Deferred<TrackingPlaybackOrigin>,
        val lease: PlayerProgressWriteAuthority.Request?,
        val trackPreferenceBeforeDiscovery: PlayerRuntimeController.TrackPreference?,
        val subtitleDelayMs: Int,
        val subtitleDelayUs: Long
    )

    private data class Fixture(
        val controller: PlayerRuntimeController,
        val oldPlayer: ExoPlayer,
        val ui: MutableStateFlow<PlayerUiState>,
        val handoffs: MutableList<Handoff>,
        val origin: Deferred<TrackingPlaybackOrigin>,
        val leaseBeforeRetry: PlayerProgressWriteAuthority.Request?
    )

    private fun TestScope.fixture(paused: Boolean, subtitlesOff: Boolean, deferInitialization: Boolean = false,
        replacementAudio: List<TrackInfo>? = null, replacementText: List<TrackInfo>? = null): Fixture {
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerInitializationKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTrackSelectionKt")
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val oldPlayer = mockk<ExoPlayer>(relaxed = true)
        var activePlayer: ExoPlayer? = oldPlayer
        var userPaused = paused
        var rendered = true
        var pendingResume: WatchProgress? = null
        var pendingTracks: PlayerRuntimeController.TrackPreference? = null
        var pendingSwitch: PlayerRuntimeController.PendingEngineSwitchTrackPreference? = null
        var remembered: PlayerRuntimeController.TrackPreference? = PlayerRuntimeController.TrackPreference(
            audio = PlayerRuntimeController.RememberedTrackSelection("tr", "Turkish", "audio-tr"),
            subtitle = if (subtitlesOff) PlayerRuntimeController.RememberedSubtitleSelection.Disabled else
                PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                    PlayerRuntimeController.RememberedTrackSelection("en", "English", "sub-en")))
        var subtitlesDisabled = subtitlesOff
        var selectionParameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesOff).build()
        var explicitSubtitle: PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch? = null
        var effectiveSubtitle: PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch? = null
        var explicitSubtitleSelection = false
        var streamUrl = "https://playback.example.invalid/granted/selected.mp4"
        var streamHeaders = mapOf("User-Agent" to "OwnedFixture")
        var streamMime: String? = MimeTypes.VIDEO_MP4
        var filename: String? = "Owned.S01E02.mp4"
        var infoHash: String? = "c".repeat(40)
        var fileIndex: Int? = 2
        val subtitleStyle = SubtitleStyleSettings(preferredLanguage = "fr",
            isPreferredLanguageSystemDefault = false, useForcedSubtitles = false)
        val ui = MutableStateFlow(PlayerUiState(
            error = "Owned fixture interrupted", currentVideoId = "tt-retry:1:2", currentSeason = 1,
            currentEpisode = 2, internalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
            subtitleStyle = subtitleStyle, audioTracks = listOf(
                TrackInfo(0, "English", "en", "audio-en"),
                TrackInfo(1, "Turkish", "tr", "audio-tr", isSelected = true)),
            selectedAudioTrackIndex = 1, subtitleTracks = listOf(
                TrackInfo(0, "French", "fr", "sub-fr"),
                TrackInfo(1, "English", "en", "sub-en", isSelected = !subtitlesOff)),
            selectedSubtitleTrackIndex = if (subtitlesOff) -1 else 1))
        val timeline = MutableStateFlow(PlaybackTimelineState(currentPosition = 45_000L, duration = 130_000L))
        // Real resetPlaybackTimeline accesses backing fields, as in PlayerPlaybackActionControllerTest.
        PlayerRuntimeController::class.java.getDeclaredField("_uiState").apply { isAccessible = true }.set(controller, ui)
        PlayerRuntimeController::class.java.getDeclaredField("_playbackTimeline").apply { isAccessible = true }.set(controller, timeline)
        PlayerRuntimeController::class.java.getDeclaredField("liveWatchClock").apply { isAccessible = true }
            .set(controller, LivePlaybackWatchClock())
        every { controller._uiState } returns ui
        every { controller._playbackTimeline } returns timeline
        every { controller.resetPlaybackTimeline() } answers { callOriginal() }
        every { controller.scope } returns backgroundScope
        every { controller._exoPlayer } answers { activePlayer }
        every { controller._exoPlayer = any() } answers { activePlayer = firstArg() }
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.currentStreamUrl } answers { streamUrl }
        every { controller.currentStreamUrl = any() } answers { streamUrl = firstArg() }
        every { controller.currentHeaders } answers { streamHeaders }
        every { controller.currentHeaders = any() } answers { streamHeaders = firstArg() }
        every { controller.currentStreamMimeType } answers { streamMime }
        every { controller.currentStreamMimeType = any() } answers { streamMime = firstArg() }
        every { controller.currentFilename } answers { filename }
        every { controller.currentFilename = any() } answers { filename = firstArg() }
        every { controller.currentInfoHash } answers { infoHash }
        every { controller.currentInfoHash = any() } answers { infoHash = firstArg() }
        every { controller.currentFileIdx } answers { fileIndex }
        every { controller.currentFileIdx = any() } answers { fileIndex = firstArg() }
        every { controller.contentId } returns "tt-retry"
        every { controller.contentType } returns "series"
        every { controller.currentVideoId } returns "tt-retry:1:2"
        every { controller.currentSeason } returns 1
        every { controller.currentEpisode } returns 2
        every { controller.profileId } returns 7
        every { controller.isTorrentStream } returns false
        every { controller.isInBackground } returns false
        every { controller.lastKnownDuration } returns 130_000L
        every { controller.currentStreamHasVideoTrack } returns true
        every { controller.currentVideoTrackMimeType } returns MimeTypes.VIDEO_H264
        every { controller.currentVideoTrackWidth } returns 1920
        every { controller.currentVideoTrackHeight } returns 1080
        every { controller.userPausedManually } answers { userPaused }
        every { controller.userPausedManually = any() } answers { userPaused = firstArg() }
        every { controller.hasRenderedFirstFrame } answers { rendered }
        every { controller.hasRenderedFirstFrame = any() } answers { rendered = firstArg() }
        every { controller.playbackIssueReportRequestVersion } returns AtomicLong()
        every { controller.subtitleDelayUs } returns AtomicLong()
        every { controller.resumeRequestGate } returns PlayerResumeRequestGate()
        every { controller.pendingResumeProgress } answers { pendingResume }
        every { controller.pendingResumeProgress = any() } answers { pendingResume = firstArg() }
        every { controller.persistedTrackPreference } answers { pendingTracks }
        every { controller.persistedTrackPreference = any() } answers { pendingTracks = firstArg() }
        every { controller.pendingEngineSwitchTrackPreference } answers { pendingSwitch }
        every { controller.pendingEngineSwitchTrackPreference = any() } answers { pendingSwitch = firstArg() }
        every { controller.rememberedTrackPreference } answers { remembered }
        every { controller.rememberedTrackPreference = any() } answers { remembered = firstArg() }
        every { controller.subtitleDisabledByPersistedPreference } answers { subtitlesDisabled }
        every { controller.subtitleDisabledByPersistedPreference = any() } answers { subtitlesDisabled = firstArg() }
        every { controller.explicitSubtitleSelectionForEngineSwitch } answers { explicitSubtitle }
        every { controller.explicitSubtitleSelectionForEngineSwitch = any() } answers { explicitSubtitle = firstArg() }
        every { controller.effectiveSubtitleSelectionForEngineSwitch } answers { effectiveSubtitle }
        every { controller.effectiveSubtitleSelectionForEngineSwitch = any() } answers { effectiveSubtitle = firstArg() }
        every { controller.isUserExplicitSubtitleSelection } answers { explicitSubtitleSelection }
        every { controller.isUserExplicitSubtitleSelection = any() } answers { explicitSubtitleSelection = firstArg() }
        every { controller.party.state } returns MutableStateFlow(PartyUiState())
        every { controller.party.playback } returns MutableStateFlow<PartyPlayback?>(null)
        every { controller.playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings(
            preferredAudioLanguage = "en", subtitleStyle = subtitleStyle,
            internalPlayerEngine = InternalPlayerEngine.EXOPLAYER, autoSwitchInternalPlayerOnError = false))
        every { oldPlayer.currentPosition } returns 45_000L
        every { oldPlayer.duration } returns 130_000L
        every { oldPlayer.isCurrentMediaItemSeekable } returns true
        every { oldPlayer.isCurrentMediaItemLive } returns false
        every { oldPlayer.playWhenReady } returns !paused
        every { oldPlayer.currentTracks } returns priorTracks(subtitlesOff)
        every { oldPlayer.trackSelectionParameters } answers { selectionParameters }
        every { oldPlayer.trackSelectionParameters = any() } answers { selectionParameters = firstArg() }
        val origin = CompletableDeferred(TrackingPlaybackOrigin(7, emptyMap(), "fixture-account-A"))
        var activeOrigin: Deferred<TrackingPlaybackOrigin> = origin
        every { controller.playbackOrigin } answers { activeOrigin }
        every { controller.playbackOrigin = any() } answers { activeOrigin = firstArg() }
        val coordinator = PlayerProgressWriteCoordinator(PlayerProgressWriteAuthority())
        every { controller.progressWriteCoordinator } returns coordinator
        val owner = checkNotNull(controller.currentProgressWriteOwner())
        coordinator.activateForeground(owner)
        check(coordinator.onPlaybackOwnerChanged(owner))
        val leaseBeforeRetry = checkNotNull(coordinator.capture(owner, completedAtCapture = false))

        // Native release boundary removes the old engine. Position capture after release cannot pass.
        every { controller.releasePlayer() } answers {
            activePlayer = null
            controller.resetPlaybackTimeline()
        }
        every { controller.selectAudioTrack(any()) } just Runs
        every { controller.selectSubtitleTrack(any()) } just Runs
        every { controller.disableSubtitles() } just Runs
        val requestedUrl = slot<String>()
        val requestedHeaders = slot<Map<String, String>>()
        val requestedEngine = mutableListOf<InternalPlayerEngine?>()
        val requestedFailover = slot<Boolean>()
        val requestedPaused = slot<Boolean>()
        val handoffs = mutableListOf<Handoff>()
        every { controller.initializePlayer(capture(requestedUrl), capture(requestedHeaders),
            captureNullable(requestedEngine), capture(requestedFailover), capture(requestedPaused)) } answers {
            val url = requestedUrl.captured
            val headers = requestedHeaders.captured
            val engine = requestedEngine.last()
            val failover = requestedFailover.captured
            val startPaused = requestedPaused.captured
            fun observeInitialization() {
                val actualOwner = controller.currentProgressWriteOwner()
                handoffs += Handoff(url, headers,
                    controller.currentInfoHash, controller.currentFileIdx, engine,
                    failover, startPaused,
                    controller.resolvePendingInitialResumePosition(), actualOwner, controller.playbackOrigin,
                    actualOwner?.let { coordinator.capture(it, completedAtCapture = false) },
                    pendingSwitch?.preference, ui.value.subtitleDelayMs, controller.subtitleDelayUs.get())
                // Discovery defaults do not choose the prior language/Off; the real restore must.
                val audio = replacementAudio ?: listOf(TrackInfo(0, "English", "en", "audio-en", isSelected = true),
                    TrackInfo(1, "Turkish", "tr", "audio-tr"))
                val text = replacementText ?: listOf(TrackInfo(0, "French", "fr", "sub-fr", isSelected = true),
                    TrackInfo(1, "English", "en", "sub-en"))
                ui.value = ui.value.copy(audioTracks = audio, selectedAudioTrackIndex = 0,
                    subtitleTracks = text, selectedSubtitleTrackIndex = 0)
                controller.applyPersistedTrackPreference(audio, text)
            }
            // Native initialization suspends before media construction; let the real saved read run first.
            if (deferInitialization) backgroundScope.launch { observeInitialization() }
            else observeInitialization()
        }
        return Fixture(controller, oldPlayer, ui, handoffs, origin, leaseBeforeRetry)
    }

    private fun priorTracks(subtitlesOff: Boolean, audioLanguage: String = "tr"): Tracks {
        fun group(id: String, language: String, mime: String, selected: Boolean) =
            Tracks.Group(TrackGroup(id, Format.Builder().setId(id).setLanguage(language)
                .setLabel(if (language == "tr") "Turkish" else "English")
                .setSampleMimeType(mime).build()), false,
                intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
        return Tracks(ImmutableList.of(
            Tracks.Group(TrackGroup("video", Format.Builder().setId("video-avc")
                .setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).build()),
                false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            group("audio-en", "en", MimeTypes.AUDIO_AAC, false),
            group("audio-tr", audioLanguage, MimeTypes.AUDIO_AAC, true),
            group("sub-en", "en", MimeTypes.APPLICATION_TX3G, !subtitlesOff)))
    }
}
