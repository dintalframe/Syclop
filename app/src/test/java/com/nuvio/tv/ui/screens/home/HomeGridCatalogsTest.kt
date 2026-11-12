package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGridCatalogsTest {
    private val divider = GridItem.SectionDivider("Top", "top", "", "provider", "movie") to "divider"

    @Test fun `terminal catalog overflow reserves a preview slot for See All`() {
        val items = listOf(divider) + tiles(8)
        val result = planHomeGridCatalogs(items, columns = 3, rows = 2)

        assertEquals(items.take(6) + seeAll, result)
    }

    @Test fun `terminal catalog that fits keeps every tile`() {
        val items = listOf(divider) + tiles(18)

        assertEquals(items, planHomeGridCatalogs(items, columns = 9, rows = 2))
    }

    @Test fun `a returned title stays reachable after it moves beyond the preview cutoff`() {
        val titles = tiles(8)
        val items = listOf(divider) + titles

        assertEquals(listOf(divider) + titles.take(4) + titles.last() + seeAll,
            planHomeGridCatalogs(items, columns = 3, rows = 2, retainedKey = titles.last().second))
    }

    @Test fun `paginated catalog keeps See All within its last title row`() {
        val items = listOf(divider) + tiles(3)

        assertEquals(items.take(3) + seeAll, planHomeGridCatalogs(items + seeAll, columns = 3, rows = 2))
    }

    @Test fun `preamble and collection folders stay in order around catalogs`() {
        val hero = GridItem.Hero(emptyList()) to "hero"
        val collection = listOf(GridItem.CollectionHeader("favorites", "Favorites") to "collection") +
            List(5) { index ->
                GridItem.CollectionFolder(
                    "favorites", "Favorites", true, CollectionFolder("folder$index", "Folder $index")
                ) to "folder_$index"
            }
        val titles = tiles(3)
        val items = listOf(hero, divider) + titles + collection

        assertEquals(
            listOf(hero, divider, titles.first(), seeAll) + collection,
            planHomeGridCatalogs(items, columns = 2, rows = 1)
        )
    }

    @Test fun `duplicate title ids keep their incoming focus keys across providers`() {
        val first = tiles(1).single()
        val second = first.first.copy(addonId = "other", addonBaseUrl = "https://other.test") to "other_title"
        val secondDivider = divider.first.copy(addonId = "other", addonBaseUrl = "https://other.test")
        val items = listOf(divider, first, secondDivider to "other_divider", second)

        assertEquals(items, planHomeGridCatalogs(items, columns = 2, rows = 1))
    }

    @Test fun `single column retains a title and a way to open the catalog`() {
        val titles = tiles(3)

        assertEquals(
            listOf(divider, titles.first(), seeAll),
            planHomeGridCatalogs(listOf(divider) + titles, columns = 1, rows = 1)
        )
    }

    private val seeAll = GridItem.SeeAll("top", "provider", "", "movie") to "see_all_0_top_provider_movie"

    private fun tiles(count: Int) = List(count) { index ->
        GridItem.Content(
            item = MetaPreview(
                id = "tt$index",
                type = ContentType.MOVIE,
                name = "Title $index",
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            ),
            addonBaseUrl = "",
            catalogId = "top",
            catalogName = "Top",
            addonId = "provider"
        ) to "title_$index"
    }
}
