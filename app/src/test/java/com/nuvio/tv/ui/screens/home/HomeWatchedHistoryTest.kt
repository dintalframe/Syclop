package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.*
import org.junit.Test

class HomeWatchedHistoryTest {
    @Test fun `history has one show card for the most recently watched episode`() {
        val movie = history("tt100", "movie", 200)
        val episode = history("tt200", "series", 300).copy(videoId = "tt200:2:3", season = 2, episode = 3,
            episodeTitle = "Third episode")
        val previous = episode.copy(videoId = "tt200:2:9", episode = 9, episodeTitle = "Ninth episode", watchedAt = 100)
        val observations = listOf(previous, movie, episode, episode.copy(watchedAt = 50))
        val projected = projectHomeWatchedHistory(HomeUiState(), observations)
        val row = projected.catalogRows.single()
        assertEquals(listOf("tt200", "tt100"), row.items.map { it.id })
        assertEquals(listOf("Title tt200", "Title tt100"), row.items.map { it.name })
        assertEquals(listOf("S02E03", null), row.items.map { it.watchedEpisodeLabel })
        assertEquals("Third episode", row.items.first().description)
        assertEquals(observations, projected.watchedHistoryItems)
        assertTrue(projected.continueWatchingItems.isEmpty())
        assertEquals(2, row.stableItemKeys().toSet().size)
        assertFalse(row.hasMore)
        assertFalse(row.supportsSkip)
        assertEquals("Watched History", catalogRowTitle(row, true))
        val occurrences = mutableMapOf<String, Int>()
        val focusKeys = row.items.map { item ->
            val occurrence = occurrences.getOrDefault(item.id, 0)
            occurrences[item.id] = occurrence + 1
            val card = buildCatalogItem(item, row, false, occurrence)
            assertTrue(card.payload is ModernPayload.Catalog)
            val payload = card.payload as ModernPayload.Catalog
            assertEquals(item.id, payload.itemId)
            assertEquals(item.apiType, payload.itemType)
            payload.focusKey
        }
        assertEquals(2, focusKeys.toSet().size)
        val afterRewatch = watchedHistoryCatalogRow(observations + previous.copy(watchedAt = 400))!!
        val afterRewatchCard = buildCatalogItem(afterRewatch.items.first(), afterRewatch, false, 0)
        assertEquals(focusKeys.first(), (afterRewatchCard.payload as ModernPayload.Catalog).focusKey)
        assertEquals("Ninth episode", afterRewatch.items.first().description)
        assertEquals("S02E09", afterRewatchCard.metaPreview?.watchedEpisodeLabel)
    }

    @Test fun `movie and show sharing a TMDB number retain separate identities`() {
        val movie = history("tmdb:123", "movie", 400)
        val special = history("tmdb:123", "series", 300).copy(videoId = "tmdb:123:0:2", season = 0, episode = 2)
        val row = watchedHistoryCatalogRow(listOf(special, movie))!!
        assertEquals(listOf("movie", "series"), row.items.map { it.apiType })
        assertEquals(listOf(null, "S00E02"), row.items.map { it.watchedEpisodeLabel })
        val focusKeys = row.items.map { (buildCatalogItem(it, row, false, 0).payload as ModernPayload.Catalog).focusKey }
        assertEquals(2, focusKeys.toSet().size)
    }

    @Test fun `compacted history keeps older rating evidence for Kids admission`() {
        val older = history("tt500", "series", 100).copy(videoId = "tt500:1:1", season = 1, episode = 1, ageRating = "TV-MA")
        val newer = older.copy(videoId = "tt500:1:2", episode = 2, watchedAt = 200, ageRating = "TV-Y")
        val home = projectHomeWatchedHistory(HomeUiState(), listOf(older, newer))
        assertEquals("TV-Y", home.catalogRows.single().items.single().ageRating)
        assertTrue(projectCategoryTrackingRows(HomeBrowseSection.KIDS, home, emptyList()).catalogRows.isEmpty())
    }

    @Test fun `history appears in Modern Classic and Grid projections while unfinished progress stays untouched`() {
        val progress = resume("tt300", "movie")
        val catalog = catalog(meta("tt900", ContentType.MOVIE))
        val hero = GridItem.Hero(catalog.items)
        val state = HomeUiState(catalogRows = listOf(catalog), homeRows = listOf(HomeRow.Catalog(catalog)),
            gridItems = listOf(hero), continueWatchingItems = listOf(progress))
        val result = projectHomeWatchedHistory(state, listOf(history("tt100", "movie", 100)))
        assertEquals(WATCHED_HISTORY_ADDON_ID, result.catalogRows.first().addonId)
        assertEquals(WATCHED_HISTORY_ADDON_ID, (result.homeRows.first() as HomeRow.Catalog).row.addonId)
        assertSame(hero, result.gridItems.first())
        assertEquals("Watched History", (result.gridItems[1] as GridItem.SectionDivider).catalogName)
        assertEquals("tt100", (result.gridItems[2] as GridItem.Content).item.id)
        assertSame(progress, result.continueWatchingItems.single())
        assertEquals(20L, progress.progress.position)
    }

    @Test fun `source logout or empty history clears every prior history projection without deleting progress`() {
        val progress = resume("tt300", "movie")
        val projected = projectHomeWatchedHistory(HomeUiState(continueWatchingItems = listOf(progress)),
            listOf(history("tt100", "movie", 100)))
        val cleared = projectHomeWatchedHistory(projected, emptyList())
        assertTrue(cleared.watchedHistoryItems.isEmpty())
        assertTrue(cleared.catalogRows.isEmpty())
        assertTrue(cleared.homeRows.isEmpty())
        assertTrue(cleared.gridItems.isEmpty())
        assertSame(progress, cleared.continueWatchingItems.single())
    }

    @Test fun `every category projects its own history and original resume cards even without catalogs`() {
        val types = listOf(
            Triple(HomeBrowseSection.MOVIES, history("tt100", "movie", 100), meta("tt100", ContentType.MOVIE)),
            Triple(HomeBrowseSection.TV, history("tt200", "series", 200), meta("tt200", ContentType.SERIES)),
            Triple(HomeBrowseSection.ANIME, history("mal:3", "series", 300), meta("mal:3", ContentType.SERIES)),
            Triple(HomeBrowseSection.DOCUMENTARY, history("tt400", "movie", 400).copy(genres = listOf("Documentary")),
                meta("tt400", ContentType.MOVIE).copy(genres = listOf("Documentary"))),
            Triple(HomeBrowseSection.KIDS, history("tt500", "series", 500).copy(ageRating = "TV-Y"),
                meta("tt500", ContentType.SERIES).copy(ageRating = "TV-Y")))
        for ((section, history, metadata) in types) {
            val progress = resume(history.contentId, history.contentType)
            val result = projectCategoryTrackingRows(section,
                HomeUiState(watchedHistoryItems = listOf(history), continueWatchingItems = listOf(progress)),
                emptyList(), listOf(metadata))
            assertEquals(section.name, listOf(history), result.watchedHistoryItems)
            assertSame(section.name, progress, result.continueWatchingItems.single())
            assertEquals(section.name, "Watched History", result.catalogRows.single().catalogName)
            assertEquals(section.name, HomeLayout.MODERN, result.homeLayout)
            assertTrue(result.continueWatchingEnabled)
            assertNull(result.error)
        }
    }

    @Test fun `Kids grouping retains already classified history and Continue Watching identities`() {
        val history = watchedHistoryCatalogRow(listOf(history("tt500", "series", 500).copy(ageRating = "TV-Y")))!!
        val catalogRow = HeroCarouselRow(catalogRowKey(history), history.catalogName, 0,
            history.items.map { buildCatalogItem(it, history, false, 0) }.asStable(),
            history.catalogId, history.addonId, history.apiType, false, false)
        val progressRow = catalogRow.copy(key = MODERN_CONTINUE_WATCHING_ROW_KEY, title = "Continue Watching")
        val baseRows = listOf(progressRow, catalogRow)
        val result = HomeCategoryPresentation.kidsRows(
            ModernHomePresentationState(baseRows.asStable(), buildCarouselRowLookups(baseRows)), listOf(history))
        assertEquals(baseRows.map { it.key }, result.rows.list.map { it.key })
        assertEquals(listOf("Continue Watching", "Watched History"), result.rows.list.map { it.title })
        assertEquals(2, result.lookups.activeRowKeys.set.size)
    }

    @Test fun `invalid observations do not create a history row or resume card`() {
        assertNull(watchedHistoryCatalogRow(emptyList()))
        assertNull(watchedHistoryCatalogRow(listOf(history("", "movie", 100), history("tt100", "movie", 0))))
        assertTrue(projectHomeWatchedHistory(HomeUiState(), listOf(history("tt100", "movie", 100)))
            .continueWatchingItems.isEmpty())
    }

    @Test fun `current category resume and upcoming actions mark return before dispatch`() {
        val progress = resume("tt300", "movie")
        val upcoming = upcoming("tt400")
        val state = HomeUiState(continueWatchingItems = listOf(progress), upcomingItems = listOf(upcoming))
        for (item in listOf(progress, upcoming)) {
            val events = mutableListOf<String>()
            dispatchCategoryTrackingAction(true, state, item, { events += "return" }) {
                assertSame(item, it)
                events += "play"
            }
            assertEquals(listOf("return", "play"), events)
        }
    }

    @Test fun `category actions reject old profile removed cards and disabled progress without side effects`() {
        val progress = resume("tt300", "movie")
        val state = HomeUiState(continueWatchingItems = listOf(progress))
        val events = mutableListOf<String>()
        for ((current, candidateState, item) in listOf(
            Triple(false, state, progress),
            Triple(true, state, upcoming("tt400")),
            Triple(true, state.copy(continueWatchingItems = emptyList()), progress),
            Triple(true, state.copy(continueWatchingEnabled = false), progress)
        )) {
            dispatchCategoryTrackingAction(current, candidateState, item, { events += "return" }) { events += "play" }
        }
        assertTrue(events.isEmpty())
    }

    @Test fun `progress only category details accept exact current resume and upcoming media`() {
        val state = HomeUiState(continueWatchingItems = listOf(resume("tt300", "movie")),
            upcomingItems = listOf(upcoming("tt400")))
        assertTrue(state.catalogRows.isEmpty())
        assertTrue(state.containsCategoryTrackingDetail("tt300", "movie"))
        assertTrue(state.containsCategoryTrackingDetail("tt400", "series"))
        assertFalse(state.containsCategoryTrackingDetail("tt300", "series"))
        assertFalse(state.containsCategoryTrackingDetail("tt999", "movie"))
        assertFalse(state.copy(continueWatchingEnabled = false).containsCategoryTrackingDetail("tt300", "movie"))
        assertFalse(state.copy(upcomingItems = emptyList()).containsCategoryTrackingDetail("tt400", "series"))
    }

    private fun history(id: String, type: String, time: Long) = WatchedHistoryItem(id, type, "Title $id", time, id)
    private fun resume(id: String, type: String) = ContinueWatchingItem.InProgress(
        WatchProgress(id, type, "Title $id", null, null, null, id, null, null, null, 20, 100, 50))
    private fun upcoming(id: String) = ContinueWatchingItem.NextUp(NextUpInfo(contentId = id,
        contentType = "series", name = "Title $id", poster = null, backdrop = null, logo = null,
        videoId = "$id:1:2", season = 1, episode = 2, episodeTitle = null, thumbnail = null,
        hasAired = false, lastWatched = 1, sortTimestamp = 2))
    private fun meta(id: String, type: ContentType) = MetaPreview(id, type, name = "Title $id", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList())
    private fun catalog(item: MetaPreview) = CatalogRow("source", "Source", "https://catalog.test", "top", "Top",
        item.type, items = listOf(item), hasMore = false)
}
