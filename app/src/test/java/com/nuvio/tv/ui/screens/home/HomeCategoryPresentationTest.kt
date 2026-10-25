package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.*
import org.junit.Test

class HomeCategoryPresentationTest {
    @Test fun `Kids splits safe content into distinct rows without rewriting original playback payloads`() {
        val cards = listOf(
            item("science", ContentType.SERIES, "TV-G", listOf("Science", "Documentary")),
            item("nature", ContentType.SERIES, "G", listOf("Nature", "Documentary")),
            item("lesson", ContentType.SERIES, "TV-Y7", listOf("Educational")),
            item("doc", ContentType.MOVIE, "G", listOf("Documentary")),
            item("movie", ContentType.MOVIE, "G", emptyList()),
            item("show", ContentType.SERIES, "TV-Y", emptyList()),
            item("adult", ContentType.SERIES, "TV-14", listOf("Science")),
            item("unknown", ContentType.MOVIE, null, listOf("Family"))
        )
        val row = catalog(cards)
        val original = carousel(row)
        val result = HomeCategoryPresentation.kidsRows(presentation(listOf(original)), listOf(row))
        assertEquals(6, result.rows.list.size)
        assertEquals(6, result.lookups.activeRowKeys.set.size)
        val groupedCards = result.rows.list.flatMap { it.items.list }
        assertEquals(listOf("science", "nature", "lesson", "doc", "movie", "show"), groupedCards.map { it.metaPreview!!.id })
        groupedCards.forEach { card ->
            assertEquals(original.items.list.single { it.metaPreview!!.id == card.metaPreview!!.id }, card)
            assertEquals("https://catalog.test", (card.payload as ModernPayload.Catalog).addonBaseUrl)
        }
        result.rows.list.forEach { split ->
            assertEquals(row.catalogId, split.catalogId)
            assertEquals(row.addonId, split.addonId)
            assertTrue(split.supportsSkip)
            assertTrue(split.hasMore)
        }
    }
    @Test fun `Kids source attribution and pagination identity remain distinct for duplicate title IDs from different sources`() {
        val first = catalog(listOf(item("same", ContentType.MOVIE, "G", emptyList())))
        val second = first.copy(addonBaseUrl = "https://other.test", addonName = "Other", catalogName = "Simkl Trending")
        val result = HomeCategoryPresentation.kidsRows(presentation(listOf(carousel(first), carousel(second))), listOf(first, second))
        assertEquals(2, result.rows.list.size)
        assertNotEquals(result.rows.list[0].key, result.rows.list[1].key)
        assertTrue(result.rows.list[1].title.contains("Simkl Trending"))
        assertNotEquals(result.rows.list[0].items.list[0].payload, result.rows.list[1].items.list[0].payload)
    }
    @Test fun `Kids removes only the repeated trailing group label and preserves source attribution`() {
        val fixtures = listOf(
            Triple("Family movies", item("movie", ContentType.MOVIE, "G", emptyList()), "Family movies · Trakt"),
            Triple("Family shows", item("show", ContentType.SERIES, "TV-Y", emptyList()), "Family shows · Trakt"),
            Triple("Education", item("lesson", ContentType.SERIES, "TV-Y", listOf("Educational")), "Education · Trakt")
        )
        for ((catalogLabel, card, expected) in fixtures) {
            val row = catalog(listOf(card)).copy(catalogName = "Trakt · $catalogLabel", addonName = "Trakt")
            val original = carousel(row)
            val grouped = HomeCategoryPresentation.kidsRows(presentation(listOf(original)), listOf(row)).rows.list.single()
            assertEquals(expected, grouped.title)
            assertEquals(original.items, grouped.items)
            assertEquals(original.catalogId, grouped.catalogId)
            assertEquals(original.addonId, grouped.addonId)
        }
    }

    @Test fun `Kids keeps different catalog context beside its group and source`() {
        val row = catalog(listOf(item("lesson", ContentType.SERIES, "TV-Y", listOf("Educational"))))
            .copy(catalogName = "Trakt · Family shows", addonName = "Trakt")
        val grouped = HomeCategoryPresentation.kidsRows(presentation(listOf(carousel(row))), listOf(row)).rows.list.single()
        assertEquals("Education · Trakt · Family shows", grouped.title)
    }

    private fun item(id: String, type: ContentType, rating: String?, genres: List<String>) = MetaPreview(
        id, type, name = "Fixture", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = genres, ageRating = rating)
    private fun catalog(items: List<MetaPreview>) = CatalogRow("fixture", "Fixture", "https://catalog.test",
        "top", "Top", ContentType.MOVIE, items = items, supportsSkip = true, hasMore = true)
    private fun carousel(row: CatalogRow) = HeroCarouselRow(catalogRowKey(row), row.catalogName, 0,
        row.items.map { buildCatalogItem(it, row, false, 0) }.asStable(),
        row.catalogId, row.addonId, row.apiType, row.supportsSkip, row.hasMore)
    private fun presentation(rows: List<HeroCarouselRow>) = ModernHomePresentationState(rows.asStable(), buildCarouselRowLookups(rows))
}
