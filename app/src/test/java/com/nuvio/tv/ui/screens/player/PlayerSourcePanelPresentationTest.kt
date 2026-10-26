package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.domain.model.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class PlayerSourcePanelPresentationTest {
    private val settings = DebridSettings(enabled = true, allDebridApiKey = "fixture-key",
        preferredResolverProviderId = "alldebrid", streamPreferences = DebridStreamPreferences(
            sortCriteria = DebridStreamSortCriterion.bitrateFirstOrder()))
    private val ranking = DebridStreamPresentation(mockk(), mockk(),
        DebridStreamFormatter(DebridStreamTemplateEngine())) { false }
    private val subject = PlayerSourcePanelPresentation { streams, configured -> ranking.rankAll(streams, configured) }

    @Test fun `global confidence ranking precedes addon filter and source pagination`() {
        val unknown = source("A", "a")
        val reported = source("B", "b").copy(title = "Movie 1080p 80 Mbps")
        val native = source("A", "c").copy(nativeFileMetadata = StreamFileMetadata(
            selectedFileBytes = 100_000_000, durationMillis = 100_000,
            durationOrigin = StreamDurationOrigin.NATIVE_CONTAINER))
        val input = state(listOf(unknown, reported, native), pageSize = 2)
        val all = subject.apply(input, settings) { true }
        assertEquals(listOf(native, reported, unknown), all.sourceAllStreams)
        assertEquals(listOf(native, reported), all.sourceFilteredStreams)
        val filtered = subject.apply(input.copy(sourceSelectedAddonFilter = "A"), settings) { true }
        assertEquals(listOf(native, unknown), filtered.sourceFilteredStreams)
    }

    @Test fun `cached raw direct and invalid hash cannot consume visible page or episode list`() {
        val hidden = source("Raw", "a").copy(infoHash = null, url = "https://provider.test/private-file.mkv")
        val invalid = source("P2P", "b").copy(infoHash = "invalid")
        val eligible = source("Native candidates", "c")
        val input = state(listOf(hidden, invalid, eligible), pageSize = 1).copy(
            episodeAllStreams = listOf(hidden, eligible), episodeFilteredStreams = listOf(hidden),
            episodeAvailableAddons = listOf("Raw", "Native candidates"))
        val output = subject.apply(input, settings) { true }
        assertEquals(listOf(eligible), output.sourceFilteredStreams)
        assertEquals(listOf("Native candidates"), output.sourceAvailableAddons)
        assertEquals(listOf(eligible), output.episodeFilteredStreams)
        assertEquals(listOf("Native candidates"), output.episodeAvailableAddons)
    }

    @Test fun `Original order and exact native episode context survive projection`() {
        val first = source("B", "b").copy(description = "Seeds: 0", clientResolve = StreamClientResolve(
            type = "debrid", infoHash = "b".repeat(40), fileIdx = 2, season = 1, episode = 1,
            filename = "Frieren.S02E01.mkv", torrentName = "Frieren.S02E01.mkv", mediaType = "series",
            mediaId = "mal:59978:1", mediaOnlyId = "mal:59978", title = "Frieren", magnetUri = null,
            sources = null, service = "alldebrid", serviceIndex = null, serviceExtension = null, isCached = true))
        val second = source("A", "a")
        val input = state(listOf(first, second), 2).copy(currentVideoId = "mal:59978:1",
            currentSeason = 1, currentEpisode = 1, episodeStreamsForVideoId = "mal:59978:2",
            episodeStreamsSeason = 1, episodeStreamsEpisode = 2)
        val output = subject.apply(input, settings.copy(streamPreferences = DebridStreamPreferences(
            sortCriteria = DebridStreamSortCriterion.originalOrder))) { true }
        assertEquals(listOf(first, second), output.sourceFilteredStreams)
        assertSame(first, output.sourceFilteredStreams.first())
        assertEquals("Seeds: 0", first.description)
        assertEquals(input.currentVideoId, output.currentVideoId)
        assertEquals(input.currentSeason, output.currentSeason)
        assertEquals(input.currentEpisode, output.currentEpisode)
        assertEquals(input.episodeStreamsForVideoId, output.episodeStreamsForVideoId)
        assertEquals(input.episodeStreamsSeason, output.episodeStreamsSeason)
        assertEquals(input.episodeStreamsEpisode, output.episodeStreamsEpisode)
    }

    @Test fun `stale scope before or during ranking hides only source panels`() {
        val input = state(listOf(source("A", "a")), 1).copy(title = "Still playing", currentVideoId = "mal:59978:1")
        var calls = 0
        val preStale = PlayerSourcePanelPresentation { streams, _ -> calls++; streams }
        val before = preStale.apply(input, settings) { false }
        assertEquals(0, calls)
        assertTrue(before.sourceFilteredStreams.isEmpty())
        var current = true
        val during = PlayerSourcePanelPresentation { streams, _ -> current = false; streams }
            .apply(input, settings) { current }
        assertTrue(during.sourceAllStreams.isEmpty())
        assertTrue(during.sourceFilteredStreams.isEmpty())
        assertEquals(input.currentVideoId, during.currentVideoId)
        assertEquals("Still playing", during.title)
    }

    @Test fun `episode packs cannot occupy All provider page or pending episode picker`() {
        val pack = source("Pack provider", "a").copy(title = "Show.S01-S12.2160p",
            fileIdx = 4, nativeFileMetadata = StreamFileMetadata(selectedFileBytes = 123L))
        val episode = source("Episode provider", "b").copy(title = "Show.S01E04.1080p")
        val unknown = source("Episode provider", "c").copy(title = "Unknown release")
        val input = state(listOf(pack, episode, unknown), pageSize = 1).copy(contentType = "series",
            currentSeason = 1, currentEpisode = 4, episodeStreamsEpisode = 5,
            episodeAllStreams = listOf(pack, episode), episodeFilteredStreams = listOf(pack),
            episodeAvailableAddons = listOf("Pack provider", "Episode provider"))
        val output = subject.apply(input, settings) { true }
        assertEquals(listOf(episode), output.sourceFilteredStreams)
        assertEquals(listOf(episode, unknown), output.sourceAllStreams)
        assertEquals(listOf("Episode provider"), output.sourceAvailableAddons)
        assertEquals(listOf(episode), output.episodeFilteredStreams)
        assertTrue(subject.apply(input.copy(sourceSelectedAddonFilter = "Pack provider"), settings) { true }
            .sourceFilteredStreams.isEmpty())
        // This is a display projection; the controller's playing source remains untouched.
        assertSame(pack, input.sourceAllStreams.first())
        assertEquals(123L, pack.nativeFileMetadata?.selectedFileBytes)
    }

    @Test fun `movie panel keeps collection results while pack only episode panel is empty`() {
        val pack = source("Provider", "a").copy(title = "Show.Complete.Series")
        val input = state(listOf(pack), 1)
        val movie = subject.apply(input.copy(contentType = "movie", currentEpisode = 4), settings) { true }
        assertEquals(listOf(pack), movie.sourceFilteredStreams)
        val episode = subject.apply(input.copy(contentType = "series", currentEpisode = 4), settings) { true }
        assertTrue(episode.sourceAllStreams.isEmpty())
        assertTrue(episode.sourceFilteredStreams.isEmpty())
    }

    private fun source(addon: String, hash: String) = Stream(null, "Movie", null, null, null,
        hash.repeat(40), 0, null, null, addon, null)
    private fun state(streams: List<Stream>, pageSize: Int) = PlayerUiState(
        sourceAllStreams = streams, sourceFilteredStreams = streams.take(pageSize),
        sourceAvailableAddons = streams.map { it.addonName }.distinct())
}
