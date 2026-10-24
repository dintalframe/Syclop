package com.nuvio.tv.data.mal

import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import org.junit.Assert.*
import org.junit.Test

class MalLibraryTest {
    @Test fun `MAL library mapping preserves entry identity category and status`() {
        val movie = MalAnime(123, "Known film", totalEpisodes = 1, mediaType = "movie",
            status = MalListStatus.COMPLETED, watchedEpisodes = 1, onList = true, startDate = "2020-01-02")
        val entry = MalLibraryMapping.entry(movie, 3)
        assertEquals("mal:123", entry.id)
        assertEquals("movie", entry.type)
        assertEquals("anime", entry.mediaCategory)
        assertEquals(setOf("mal:status:completed"), entry.listKeys)
        assertEquals(3, entry.listRanks["mal:status:completed"])
        assertEquals(MalCatalog.BASE_URL, entry.addonBaseUrl)
        assertNull(entry.imdbId)
        assertNull(entry.tmdbId)
        assertEquals("mal:123", entry.toMetaPreview().id)
        assertEquals("series", MalLibraryMapping.entry(movie.copy(mediaType = "tv"), 0).type)
    }

    @Test fun `five MAL status tabs are exclusive and provider scoped`() {
        val tabs = MalLibraryMapping.tabs()
        assertEquals(5, tabs.size)
        assertEquals(MalListStatus.entries.map { MalLibraryMapping.key(it) }, tabs.map { it.key })
        assertTrue(tabs.all { it.selectionGroup == "mal:status" && it.trackingProviderId == "mal" })
        val before = mapOf("mal:status:watching" to true, "local" to true, "trakt:watchlist" to true)
        val selected = toggleTrackingMembershipSelection(tabs, before, "mal:status:completed", "series")!!
        assertEquals(false, selected["mal:status:watching"])
        assertEquals(true, selected["mal:status:completed"])
        assertEquals(true, selected["local"])
        assertEquals(true, selected["trakt:watchlist"])
    }

    @Test fun `MAL is a library source without replacing local timestamp resume`() {
        val connected = setOf(TrackingProviderId.MAL)
        assertEquals(listOf(LibrarySourceMode.LOCAL, LibrarySourceMode.MAL), availableLibrarySourceModes(connected))
        assertEquals(listOf(WatchProgressSource.NUVIO_SYNC), availableWatchProgressSources(connected))
        val selection = TrackingSourceSelection(WatchProgressSource.NUVIO_SYNC, LibrarySourceMode.MAL)
        assertEquals(selection, effectiveTrackingSourceSelection(selection, connected))
        assertEquals(selection.copy(librarySourceMode = LibrarySourceMode.LOCAL),
            effectiveTrackingSourceSelection(selection, emptySet()))
        assertEquals(TrackingProviderId.MAL, TrackingProviderId.fromStorage("mal"))
        assertEquals(LibrarySourceMode.MAL, LibrarySourceMode.valueOf("MAL"))
    }

    @Test fun `rating only PATCH cannot overwrite progress or rewatch fields`() {
        val item = MalAnime(1, "Anime", status = MalListStatus.COMPLETED, watchedEpisodes = 12,
            score = 7, onList = true, isRewatching = true, timesRewatched = 2, rewatchValue = 4)
        val edit = MalListEdit.changes(item, item.status, item.watchedEpisodes, 9, true, 2, 4)
        assertEquals(mapOf("score" to "9"), edit.fields())
        assertTrue(MalListEdit.changes(item, item.status, 12, 7, true, 2, 4).fields().isEmpty())
    }

    @Test fun `rewatch edits and new list entries use documented independent fields`() {
        assertEquals(mapOf("is_rewatching" to "true", "num_times_rewatched" to "3", "rewatch_value" to "5"),
            MalListEdit(isRewatching = true, timesRewatched = 3, rewatchValue = 5).fields())
        val item = MalAnime(2, "New anime")
        assertEquals(mapOf("status" to "plan_to_watch", "num_watched_episodes" to "0"),
            MalListEdit.changes(item, item.status, 0, 0, false, 0, 0).fields())
        listOf(MalListEdit(score = -1), MalListEdit(score = 11), MalListEdit(watched = -1),
            MalListEdit(timesRewatched = -1), MalListEdit(rewatchValue = 6))
            .forEach { assertTrue(runCatching { it.fields() }.isFailure) }
    }

    @Test fun `automatic rewatch completion preserves explicit rewatch status and counters`() {
        val item = MalAnime(1, "Anime", totalEpisodes = 12, status = MalListStatus.COMPLETED,
            watchedEpisodes = 1, onList = true, isRewatching = true, timesRewatched = 3)
        assertEquals(MalListStatus.COMPLETED to 2, MalRules.progressUpdate(item, 2))
        assertEquals(3, item.timesRewatched)
        assertTrue(item.isRewatching)
    }
}
