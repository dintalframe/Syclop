package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class EpisodeSourcePolicyTest {
    @Test fun `explicit season collections and complete season claims are hidden`() {
        for (title in listOf("The.Big.Bang.Theory.S01-S12.1080p", "Show.S01-12",
            "Show.Seasons.1-12", "Show.CompleteSeason", "Show.Complete.Series",
            "Show.Season1Complete", "Show.S01.COMPLETE", "Show.All.Seasons",
            "The.Big.Bang.Theory.S01.1080p.BluRay", "The Big Bang Theory Season 1 1080p")) {
            assertTrue(title, EpisodeSourcePolicy.isReportedPack(source(title)))
        }
    }

    @Test fun `anime batches and multi episode releases are hidden without guessing offsets`() {
        for (title in listOf("[Group] Frieren [Batch]", "Frieren [01-12] 1080p",
            "Frieren (01-12)", "Show Episodes 01-12", "Show.S01E01-E12.mkv",
            "Show.S01E01E02.mkv", "Show.S01E01.S01E02.mkv", "Show.E04E05.mkv",
            "Show.S01E04.1080p.E05E06.mkv")) {
            assertTrue(title, EpisodeSourcePolicy.isReportedPack(source(title)))
        }
        assertFalse(EpisodeSourcePolicy.isReportedPack(source("[Group] Frieren - 29 (S02E01).mkv")))
        assertFalse(EpisodeSourcePolicy.isReportedPack(source("[Group] Frieren - 01.mkv")))
    }

    @Test fun `single episode titles unknown releases and title words stay available`() {
        val titles = listOf("The.Big.Bang.Theory.S01E04.1080p.mkv", "The Bad Batch S01E04.mkv",
            "Season of the Witch S01E04", "Unknown release", "Show [2020-2024]",
            "Show.S01E01E01", "Show [01-01]")
        assertEquals(titles, EpisodeSourcePolicy.filter(titles.map(::source), true).map { it.title })
    }

    @Test fun `single season with explicit episode leaf or anime absolute clue remains available`() {
        for (leaf in listOf("Show.S01E04.mkv", "Show.1x04.mkv", "Show.EP04.mkv",
            "Show.Episode04.mkv", "[Group] Frieren - 29.mkv")) {
            assertFalse(leaf, EpisodeSourcePolicy.hasReportedPackText("Show.S01.1080p", leaf))
        }
        assertFalse(EpisodeSourcePolicy.hasReportedPackText("Sousou no Frieren 2nd Season - 29.mkv"))
        assertFalse(EpisodeSourcePolicy.hasReportedPackText("Sousou no Frieren 2nd Season"))
        assertTrue(EpisodeSourcePolicy.hasReportedPackText("Show.S01.COMPLETE", "Show.S01E04.mkv"))
    }

    @Test fun `native selected leaf and file index cannot override an explicit release pack`() {
        val pack = source("Show.S01-S12").copy(fileIdx = 4,
            behaviorHints = StreamBehaviorHints(null, null, null, null, filename = "Show.S01E04.mkv", videoSize = 123L),
            nativeFileMetadata = StreamFileMetadata(selectedFileBytes = 123L))
        assertTrue(EpisodeSourcePolicy.filter(listOf(pack), true).isEmpty())
        assertEquals("Show.S01E04.mkv", pack.behaviorHints?.filename)
    }

    @Test fun `requested coordinates do not become pack evidence or change native MAL identity`() {
        val resolve = resolve("Frieren - 29.mkv").copy(mediaId = "mal:59978:1", mediaOnlyId = "mal:59978",
            season = 1, episode = 1)
        val candidate = source("Frieren - 29.mkv").copy(clientResolve = resolve)
        assertSame(candidate, EpisodeSourcePolicy.filter(listOf(candidate), true).single())
        assertEquals("mal:59978:1", candidate.clientResolve?.mediaId)
        assertEquals(1, candidate.clientResolve?.episode)
    }

    @Test fun `structured parsed multiple episodes or seasons override single leaf hint`() {
        val leaf = source("Show.S01E04.mkv").copy(clientResolve = resolve("Show.S01E04.mkv"))
        assertFalse(EpisodeSourcePolicy.isReportedPack(leaf))
        for (parsed in listOf(parsed(seasons = listOf(1, 2)), parsed(episodes = listOf(4, 5)))) {
            val pack = leaf.copy(clientResolve = leaf.clientResolve!!.copy(stream = StreamClientResolveStream(
                StreamClientResolveRaw(null, "Show.S01E04.mkv", 123, null, null, null, null, parsed))))
            assertTrue(EpisodeSourcePolicy.isReportedPack(pack))
        }
        val duplicates = leaf.copy(clientResolve = leaf.clientResolve!!.copy(stream = StreamClientResolveStream(
            StreamClientResolveRaw(null, null, null, null, null, null, null, parsed(episodes = listOf(4, 4))))))
        assertFalse(EpisodeSourcePolicy.isReportedPack(duplicates))
        val seasonOnly = source("Unknown release").copy(clientResolve = resolve("Unknown release").copy(
            stream = StreamClientResolveStream(StreamClientResolveRaw(null, null, null, null, null, null,
                null, parsed(seasons = listOf(1))))))
        assertTrue(EpisodeSourcePolicy.isReportedPack(seasonOnly))
        assertFalse(EpisodeSourcePolicy.isReportedPack(seasonOnly.copy(behaviorHints =
            StreamBehaviorHints(null, null, null, null, filename = "Show.S01E04.mkv"))))
    }

    @Test fun `explicit pack in primary source name is not concealed by an episode leaf`() {
        val episode = source("Show.S01E04.mkv")
        for (name in listOf("Complete Season 1", "Complete Series", "Show.S01-S12")) {
            assertTrue(name, EpisodeSourcePolicy.isReportedPack(episode.copy(name = name)))
        }
        assertFalse(EpisodeSourcePolicy.isReportedPack(episode.copy(name = "Provider S01")))
    }

    @Test fun `movie collection and unknown request contexts are not globally filtered`() {
        val collection = listOf(source("Movie.Complete.Series.Collection"))
        assertFalse(EpisodeSourcePolicy.isEpisodeRequest("movie", 1))
        assertFalse(EpisodeSourcePolicy.isEpisodeRequest("series", null))
        assertFalse(EpisodeSourcePolicy.isEpisodeRequest(null, 1))
        assertTrue(EpisodeSourcePolicy.isEpisodeRequest("anime", 1))
        assertTrue(EpisodeSourcePolicy.isEpisodeRequest("series", 4))
        assertSame(collection, EpisodeSourcePolicy.filter(collection, false))
    }

    private fun source(title: String) = Stream(null, title, null, null, null, "a".repeat(40),
        null, null, null, "Synthetic", null)

    private fun resolve(filename: String) = StreamClientResolve("debrid", "a".repeat(40), 4,
        null, null, filename, filename, "series", "tt1:1:4", "tt1", "Show", 1, 4,
        "alldebrid", null, null, true)

    private fun parsed(seasons: List<Int>? = null, episodes: List<Int>? = null) = StreamClientResolveParsed(
        rawTitle = null, parsedTitle = null, year = null, resolution = null, seasons = seasons,
        episodes = episodes, quality = null, hdr = null, codec = null, audio = null, channels = null,
        languages = null, group = null, network = null, edition = null, duration = null,
        bitDepth = null, extended = null, theatrical = null, remastered = null, unrated = null)
}
