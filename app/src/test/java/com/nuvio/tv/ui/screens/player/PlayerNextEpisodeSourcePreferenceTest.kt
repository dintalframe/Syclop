package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.Video
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real next-episode selection and presentation; source fetching, account storage and resolution are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerNextEpisodeSourcePreferenceTest {
    @After
    fun cleanup() {
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
    }

    @Test
    fun `first stream next episode uses the same global resolution preference as ordinary Play`() = runTest {
        val f = fixture()
        // Hand-picked fixture: 1080p B outranks 2160p A even though provider A is installed first.
        assertEquals(HASH_B, f.presentation.rankAll(f.groups.flatMap { it.streams }, f.settings,
            enforceLimits = false, episodeRequest = true).first().getEffectiveInfoHash())

        f.controller.playNextEpisode(userInitiated = true)
        runCurrent()

        assertEquals(listOf(HASH_B), f.resolved.map { it.getEffectiveInfoHash() })
        assertEquals("tt-fixture:1:1", f.ui.value.currentVideoId)
        assertEquals(3, (f.ui.value.postPlayMode as? PostPlayMode.AutoPlay)?.countdownSec)
    }

    @Test
    fun `Original source order remains provider order instead of forcing a quality preference`() = runTest {
        val f = fixture(preferences = preferences.copy(sortCriteria = emptyList()))

        f.controller.playNextEpisode(userInitiated = true)
        runCurrent()

        assertEquals(listOf(HASH_A), f.resolved.map { it.getEffectiveInfoHash() })
    }

    @Test
    fun `equal resolution rank keeps stable provider order`() = runTest {
        val f = fixture(streamB = source("B", HASH_B, "2160p"))

        f.controller.playNextEpisode(userInitiated = true)
        runCurrent()

        assertEquals(listOf(HASH_A), f.resolved.map { it.getEffectiveInfoHash() })
    }

    @Test
    fun `exact binge filename wins over better unrelated quality even with a global result cap`() = runTest {
        val f = fixture(
            preferences = preferences.copy(maxResults = 1),
            playerSettings = nextSettings.copy(streamAutoPlayPreferBingeGroupForNextEpisode = true),
            currentBingeGroup = "same-release",
            currentFilename = "Fixture.S01E01.2160p.mp4",
            streamA = source("A", HASH_A, "2160p", "same-release", "Fixture.S01E02.2160p.mp4"),
            streamB = source("B", HASH_B, "1080p", "other-release", "Fixture.S01E02.1080p.mp4")
        )

        f.controller.playNextEpisode(userInitiated = true)
        runCurrent()

        assertEquals(listOf(HASH_A), f.resolved.map { it.getEffectiveInfoHash() })
    }

    @Test
    fun `two sources in the same exact binge filename tier use shared resolution preference`() = runTest {
        val f = fixture(
            playerSettings = nextSettings.copy(streamAutoPlayPreferBingeGroupForNextEpisode = true),
            currentBingeGroup = "same-release",
            currentFilename = "Fixture.S01E01.mp4",
            streamA = source("A", HASH_A, "2160p", "same-release", "Fixture.S01E02.mp4"),
            streamB = source("B", HASH_B, "1080p", "same-release", "Fixture.S01E02.mp4")
        )

        f.controller.playNextEpisode(userInitiated = true)
        runCurrent()

        assertEquals(listOf(HASH_B), f.resolved.map { it.getEffectiveInfoHash() })
    }

    @Test
    fun `manual next with autoplay and binge reuse disabled opens picker without resolving`() = runTest {
        val f = fixture(playerSettings = nextSettings.copy(streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = false))
        stubPickerFetches(f.controller)

        f.controller.playNextEpisode(userInitiated = true)
        runCurrent()

        assertTrue(f.ui.value.showEpisodesPanel)
        assertTrue(f.ui.value.showEpisodeStreams)
        assertNull(f.ui.value.postPlayMode)
        assertTrue(f.resolved.isEmpty())
    }

    @Test
    fun `binge only manual next does not replace a missing release with better quality`() = runTest {
        val f = fixture(
            playerSettings = nextSettings.copy(streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
                streamAutoPlayNextEpisodeEnabled = false,
                streamAutoPlayNextEpisodeFallbackEnabled = false,
                streamAutoPlayPreferBingeGroupForNextEpisode = true),
            currentBingeGroup = "missing-release"
        )
        stubPickerFetches(f.controller)

        f.controller.playNextEpisode(userInitiated = true)
        runCurrent()

        assertTrue(f.ui.value.showEpisodeStreams)
        assertNull(f.ui.value.postPlayMode)
        assertTrue(f.resolved.isEmpty())
    }

    private fun stubPickerFetches(controller: PlayerRuntimeController) {
        // Keep the real picker state changes; source/metadata loading is outside these manual-mode assertions.
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
        every { controller.loadEpisodesIfNeeded() } just Runs
        every { controller.loadStreamsForEpisode(any(), any()) } just Runs
    }

    private data class Fixture(
        val controller: PlayerRuntimeController,
        val ui: MutableStateFlow<PlayerUiState>,
        val resolved: MutableList<Stream>,
        val presentation: DebridStreamPresentation,
        val settings: DebridSettings,
        val groups: List<AddonStreams>
    )

    private fun TestScope.fixture(
        preferences: DebridStreamPreferences = Companion.preferences,
        playerSettings: PlayerSettings = nextSettings,
        currentBingeGroup: String? = null,
        currentFilename: String? = null,
        streamA: Stream = source("A", HASH_A, "2160p"),
        streamB: Stream = source("B", HASH_B, "1080p")
    ): Fixture {
        val settings = DebridSettings(torboxApiKey = "owned-test-fixture", streamPreferences = preferences)
        val settingsStore = mockk<DebridSettingsDataStore>(relaxed = true)
        every { settingsStore.settings } returns flowOf(settings)
        val presentation = DebridStreamPresentation(settingsStore,
            mockk<StreamBadgeSettingsDataStore>(relaxed = true),
            DebridStreamFormatter(DebridStreamTemplateEngine()), nativeGrant = { false })
        // Reproduce the repository boundary: real presentation filters/ranks each provider group separately.
        val groups = presentation.apply(listOf(AddonStreams("A", null, listOf(streamA)),
            AddonStreams("B", null, listOf(streamB))), settings, includeBadgeMatches = false,
            episodeRequest = true)
        assertEquals(listOf(HASH_A, HASH_B), groups.flatMap { it.streams }.map { it.getEffectiveInfoHash() })
        val next = Video(id = "tt-fixture:1:2", title = "Episode 2", released = null,
            thumbnail = null, season = 1, episode = 2, overview = null)
        val ui = MutableStateFlow(PlayerUiState(currentVideoId = "tt-fixture:1:1", currentSeason = 1,
            currentEpisode = 1, nextEpisode = NextEpisodeInfo(next.id, 1, 2, next.title,
                null, null, null, true, unairedMessage = null)))
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        every { controller._uiState } returns ui
        every { controller.scope } returns backgroundScope
        every { controller.contentId } returns "tt-fixture"
        every { controller.contentType } returns "series"
        every { controller.currentVideoId } returns "tt-fixture:1:1"
        every { controller.currentSeason } returns 1
        every { controller.currentEpisode } returns 1
        every { controller.nextEpisodeVideo } returns next
        every { controller.currentStreamBingeGroup } returns currentBingeGroup
        every { controller.currentFilename } returns currentFilename
        every { controller.currentStreamUrl } returns "https://playback.example.invalid/current.mp4"
        every { controller.party.state } returns MutableStateFlow(PartyUiState())
        every { controller.playerSettingsDataStore.playerSettings } returns flowOf(playerSettings)
        every { controller.debridStreamPresentation } returns presentation
        every { controller.debridSettingsDataStore } returns settingsStore
        every { controller.serverStreams.isNativeRequest(next.id) } returns false
        every { controller.serverStreams.preferredSourceNames("series", next.id) } returns emptySet()
        every { controller.addonRepository.getInstalledAddons() } returns flowOf(listOf(addon("A"), addon("B")))
        every { controller.streamRepository.getStreamsFromAllAddons("series", next.id, 1, 2, false) } returns
            flowOf(NetworkResult.Success(groups))
        every { controller.context.getString(any()) } returns "Fixture"
        every { controller.loadingDiagnosticEvents } returns ArrayDeque()
        every { controller.pendingPlaybackRawEventLines } returns ArrayDeque()
        var nextJob: Job? = null
        every { controller.nextEpisodeAutoPlayJob } answers { nextJob }
        every { controller.nextEpisodeAutoPlayJob = any() } answers { nextJob = firstArg() }
        val resolved = mutableListOf<Stream>()
        val resolver = controller.directDebridResolver
        coEvery { resolver.resolveToPlayableStream(any(), 1, 2, any(), next.id, any(), "series", any()) } coAnswers {
            val selected = firstArg<Stream>()
            resolved += selected
            DirectDebridPlayableResult.Success(selected.copy(url = "https://playback.example.invalid/next.mp4"))
        }
        return Fixture(controller, ui, resolved, presentation, settings, groups)
    }

    companion object {
        private val HASH_A = "a".repeat(40)
        private val HASH_B = "b".repeat(40)
        private val preferences = DebridStreamPreferences(
            preferredResolutions = listOf(DebridStreamResolution.P1080, DebridStreamResolution.P2160),
            sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC))
        )
        private val nextSettings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlayPreferBingeGroupForNextEpisode = false)

        private fun addon(name: String) = Addon(id = name, name = name, version = "1",
            description = null, logo = null, baseUrl = "https://$name.example.invalid",
            catalogs = emptyList(), types = listOf(ContentType.SERIES), resources = emptyList())

        private fun source(addon: String, hash: String, resolution: String,
            bingeGroup: String? = null, filename: String = "Fixture.S01E02.$resolution.mp4") = Stream(
            name = "$addon $resolution", title = filename, description = filename, url = null,
            ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(null, bingeGroup, null, null, filename = filename),
            addonName = addon, addonLogo = null, quality = resolution,
            clientResolve = StreamClientResolve(type = "debrid", infoHash = hash, fileIdx = 0,
                magnetUri = null, sources = null, torrentName = filename, filename = filename,
                mediaType = "series", mediaId = "tt-fixture:1:2", mediaOnlyId = "tt-fixture",
                title = "Fixture", season = 1, episode = 2, service = "torbox", serviceIndex = 0,
                serviceExtension = null, isCached = true)
        )
    }
}
