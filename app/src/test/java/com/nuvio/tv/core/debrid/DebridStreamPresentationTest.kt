package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.SourceSearchDiagnostics
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridStreamPresentationTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val presentation = DebridStreamPresentation(
        dataStore = mockk<DebridSettingsDataStore>(relaxed = true),
        streamBadgeSettingsDataStore = mockk<StreamBadgeSettingsDataStore>(relaxed = true),
        formatter = formatter,
        nativeGrant = { false },
        currentScope = { StreamAvailabilityScope(1, 2L, 3L) },
        clock = { 1_000L }
    )
    private val settings = DebridSettings(
        enabled = true,
        torboxApiKey = "key",
        streamPreferences = DebridStreamPreferences(excludedEncodes = listOf(DebridStreamEncode.AVC)),
        streamNameTemplate = "{stream.resolution} {stream.quality} {stream.encode}",
        streamDescriptionTemplate = "{stream.filename}"
    )

    @Test
    fun `formats eligible debrid streams and excludes unsupported direct URLs`() {
        val passthrough = Stream(
            name = "Regular",
            title = "Movie",
            description = null,
            url = "https://example.com/movie.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon",
            addonLogo = null
        )
        val debridStreams = listOf(
            debridStream("Movie.2023.1080p.WEB-DL.DDP5.1.x265-GRP.mkv"),
            debridStream("Movie.2023.2160p.BluRay.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv"),
            debridStream("Movie.2023.720p.WEBRip.AAC.x264-GRP.mkv"),
            debridStream("Movie.2023.1440p.HDTV.HDR.AV1.mkv")
        )

        val result = presentation.apply(
            groups = listOf(AddonStreams(addonName = "Addon", addonLogo = null, streams = debridStreams + passthrough)),
            settings = settings,
            includeBadgeMatches = false
        ).single().streams

        val expectedDebrid = DirectDebridStreamFilter.applyPreferences(debridStreams, settings)
            .map { formatter.format(it, settings) }
        assertEquals(expectedDebrid, result.map { it.copy(badges = emptyList()) })
        assertEquals(
            setOf("1080p WEB-DL HEVC", "2160p BluRay REMUX HEVC", "1440p HDTV AV1"),
            result.map { it.name }.toSet()
        )
    }

    @Test fun `episode packs are removed before global limit and preserve eligible source order`() {
        val pack = debridStream("The.Big.Bang.Theory.S01-S12.2160p.mkv")
        val episode = debridStream("The.Big.Bang.Theory.S01E04.1080p.mkv")
        val unknown = debridStream("Unknown.release.mkv")
        val configured = settings.copy(streamPreferences = DebridStreamPreferences(maxResults = 1))
        assertEquals(listOf(episode), presentation.rankAll(listOf(pack, episode, unknown), configured,
            episodeRequest = true).map { it.copy(badges = emptyList()) })
        assertEquals(listOf(episode, unknown), presentation.rankAll(listOf(pack, episode, unknown), configured,
            enforceLimits = false, episodeRequest = true).map { it.copy(badges = emptyList()) })
        assertEquals(listOf(pack), presentation.rankAll(listOf(pack, episode), configured).map { it.copy(badges = emptyList()) })
    }

    @Test fun `per provider episode groups contain no pack even with selected leaf`() {
        val pack = debridStream("Show.Season1Complete.1080p.mkv").let {
            it.copy(clientResolve = it.clientResolve!!.copy(filename = "Show.S01E04.mkv"))
        }
        val episode = debridStream("Show.S01E04.1080p.mkv")
        val groups = listOf(AddonStreams("Packs", null, listOf(pack)), AddonStreams("Episodes", null, listOf(episode)))
        val output = presentation.apply(groups, settings, includeBadgeMatches = false, episodeRequest = true)
        assertEquals(listOf("Packs", "Episodes"), output.map { it.addonName })
        assertTrue(output.first().streams.isEmpty())
        assertEquals(1, output.last().streams.size)
        assertEquals(episode.title, output.last().streams.single().title)
    }

    @Test
    fun `valid mapped anime candidate hidden by unknown metadata filter retains precise recovery count`() {
        val candidate = debridStream("Selected.Show.S02E01.1080p.WEB-DL.mkv").copy(
            title = "Selected.Show.S02E01.1080p.WEB-DL.mkv", description = null,
            infoHash = "b".repeat(40), clientResolve = null)
        val diagnostics = SourceSearchDiagnostics(providers = 3, completed = 3, sourceCount = 1, complete = true)
        val configured = settings.copy(streamPreferences = DebridStreamPreferences(
            excludedVisualTags = listOf(DebridStreamVisualTag.UNKNOWN)))
        val result = presentation.apply(listOf(AddonStreams("Anime", null, listOf(candidate), diagnostics)),
            configured, episodeRequest = true).single()
        assertTrue(result.streams.isEmpty())
        assertEquals(1, result.hiddenByPreferencesCount)
        assertEquals(diagnostics, result.searchDiagnostics)
        // Re-presenting an already filtered group cannot count the same source twice.
        assertEquals(1, presentation.apply(listOf(result), configured, episodeRequest = true).single().hiddenByPreferencesCount)
        val relaxed = presentation.apply(listOf(AddonStreams("Anime", null, listOf(candidate))),
            settings.copy(streamPreferences = DebridStreamPreferences()), episodeRequest = true).single()
        assertEquals(1, relaxed.streams.size)
        assertEquals(0, relaxed.hiddenByPreferencesCount)
    }

    @Test
    fun `preference hidden counts exclude rejected direct URLs packs and result caps`() {
        val hidden = debridStream("Show.S02E01.1080p.WEB-DL.mkv")
        val visible = debridStream("Show.S02E01.1080p.WEB-DL.SDR.mkv")
        val direct = hidden.copy(clientResolve = null, infoHash = null, url = "https://ungranted.test/file.mkv")
        val pack = debridStream("Show.Season2Complete.1080p.WEB-DL.mkv")
        val configured = settings.copy(streamPreferences = DebridStreamPreferences(
            excludedVisualTags = listOf(DebridStreamVisualTag.UNKNOWN), maxResults = 1))
        val result = presentation.apply(listOf(AddonStreams("Hidden", null, listOf(hidden, direct, pack)),
            AddonStreams("Visible", null, listOf(visible, visible.copy(name = "Alternative")))),
            configured, episodeRequest = true)
        assertEquals(listOf(1, 0), result.map { it.hiddenByPreferencesCount })
        assertTrue(result.first().streams.isEmpty())
        assertEquals(2, result.last().streams.size)
        assertEquals(1, presentation.rankAll(result.last().streams, configured, episodeRequest = true).size)
    }

    private fun debridStream(filename: String): Stream = Stream(
        name = "TB cached",
        title = "Movie",
        description = filename,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "a".repeat(40),
            fileIdx = 0,
            magnetUri = null,
            sources = null,
            torrentName = filename,
            filename = filename,
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Movie",
            season = null,
            episode = null,
            service = "torbox",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )
}
