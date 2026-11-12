package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeCategoryGridTest {
    @Test fun `Kids grid groups age seven titles and excludes unknown and mature ratings`() {
        val row = catalog("https://kids.test", listOf(
            title("science", "TV-Y", listOf("Science", "Educational")),
            title("movie", "G"),
            title("unknown", null),
            title("mature", "TV-MA")
        ))
        val grid = buildHomeCategoryGrid(HomeBrowseSection.KIDS, listOf(row), emptyList())

        assertEquals(listOf("Science · Catalog", "Family movies · Catalog"),
            grid.items.filterIsInstance<GridItem.SectionDivider>().map { it.catalogName })
        assertEquals(listOf("science", "movie"), grid.items.filterIsInstance<GridItem.Content>().map { it.item.id })
        assertFalse(grid.items.any { it is GridItem.SeeAll })
    }

    @Test fun `terminal category keeps all loaded titles and exact source owns the page action`() {
        val terminal = catalog("https://first.test", List(30) { title("movie$it", "G") })
        val nextPage = catalog("https://second.test", listOf(title("more", "G"))).copy(hasMore = true)
        val grid = buildHomeCategoryGrid(HomeBrowseSection.MOVIES, listOf(terminal, nextPage),
            listOf(HomeBrowseRow("first", "First", terminal, loading = false),
                HomeBrowseRow("second", "Second", nextPage, loading = false)))

        assertEquals(31, grid.items.filterIsInstance<GridItem.Content>().size)
        val action = grid.items.filterIsInstance<GridItem.SeeAll>().single()
        assertEquals("https://second.test", action.addonBaseUrl)
        assertEquals("second", grid.pageRows.getValue(action).key)
    }

    @Test fun `an empty scoped page and a failed catalog stay reachable`() {
        val emptyPage = catalog("https://empty.test", emptyList()).copy(hasMore = true)
        val grid = buildHomeCategoryGrid(HomeBrowseSection.MOVIES, emptyList(), listOf(
            HomeBrowseRow("empty", "Empty", emptyPage, loading = false),
            HomeBrowseRow("failed", "Failed", loading = false, failed = true),
            HomeBrowseRow("terminal", "Terminal", emptyPage.copy(hasMore = false), loading = false)
        ))

        assertEquals(listOf("empty", "failed"), grid.items.filterIsInstance<GridItem.SeeAll>()
            .map { grid.pageRows.getValue(it).key })
    }

    @Test fun `retry keeps its focus key while the first page loads`() {
        val populated = catalog("https://populated.test", listOf(title("existing", "G")))
        val failed = HomeBrowseRow("retry-key", "Retry catalog", loading = false, failed = true)
        val loading = failed.copy(loading = true, failed = false)
        val failedGrid = buildHomeCategoryGrid(HomeBrowseSection.MOVIES, listOf(populated), listOf(failed))
        val loadingGrid = buildHomeCategoryGrid(HomeBrowseSection.MOVIES, listOf(populated), listOf(loading))

        assertEquals(failedGrid.pageRows.keys.single(), loadingGrid.pageRows.keys.singleOrNull())
        assertEquals("retry-key", loadingGrid.pageRows.values.single().key)

        val loaded = catalog("https://recovered.test", listOf(title("recovered", "G")))
        val recoveredGrid = buildHomeCategoryGrid(HomeBrowseSection.MOVIES, listOf(populated, loaded),
            listOf(loading.copy(data = loaded, loading = false)))
        val replacement = recoveredGrid.retryFocusTargets[failedGrid.pageRows.keys.single()] as GridItem.Content
        assertEquals("recovered", replacement.item.id)
        assertEquals("https://recovered.test", replacement.addonBaseUrl)

        val emptyPage = loaded.copy(items = emptyList(), hasMore = true)
        val emptyGrid = buildHomeCategoryGrid(HomeBrowseSection.MOVIES, listOf(populated),
            listOf(loading.copy(data = emptyPage, loading = false)))
        val moreAction = emptyGrid.retryFocusTargets[failedGrid.pageRows.keys.single()] as GridItem.SeeAll
        assertEquals("retry-key", emptyGrid.pageRows.getValue(moreAction).key)
    }

    private fun catalog(url: String, items: List<MetaPreview>) =
        CatalogRow("same-provider", "Provider", url, "same-id", "Catalog", ContentType.MOVIE,
            items = items, hasMore = false)

    private fun title(id: String, rating: String?, genres: List<String> = emptyList()) =
        MetaPreview(id, ContentType.MOVIE, name = id, poster = null, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
            genres = genres, ageRating = rating)
}
