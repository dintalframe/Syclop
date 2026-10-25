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

    @Test fun `late anime catalog stays below the focused entry row with matching navigation lookups`() {
        val ready = carousel(catalog(listOf(item("mal:6001", ContentType.SERIES, null, listOf("Anime"))))
            .copy(catalogId = "simkl-week", addonBaseUrl = "syclop-simkl://catalog"))
        val late = carousel(catalog(listOf(item("mal:52991", ContentType.SERIES, null, listOf("Anime"))))
            .copy(catalogId = "mal-ranking", addonBaseUrl = "syclop-mal://catalog"))
        val initial = HomeCategoryPresentation.preserveRowOrder(presentation(listOf(ready)), emptyList())
        val focusedKey = initial.rows.list.first().key
        val focusedCard = ready.items.list.single()

        // Request order puts MAL first, but its response arrives after Simkl has taken focus.
        val result = HomeCategoryPresentation.preserveRowOrder(presentation(listOf(late, ready)), listOf(focusedKey))

        assertEquals(listOf(ready.key, late.key), result.rows.list.map { it.key })
        assertEquals(focusedKey, result.rows.list.first().key)
        assertSame(focusedCard, result.rows.list.first().items.list.single())
        assertEquals(0, result.lookups.rowIndexByKey.map[focusedKey])
        assertEquals(focusedKey, result.lookups.rowKeyByGlobalRowIndex.map[0])
        assertEquals(late.key, result.lookups.rowKeyByGlobalRowIndex.map[1])
        assertEquals(listOf(0, 1), result.rows.list.map { it.globalRowIndex })
    }

    @Test fun `removed or empty catalog rows are discarded and later ready rows remain reachable`() {
        val removed = carousel(catalog(listOf(item("removed", ContentType.MOVIE, "G", emptyList()))).copy(catalogId = "removed"))
        val kept = carousel(catalog(listOf(item("kept", ContentType.MOVIE, "G", emptyList()))).copy(catalogId = "kept"))
        val added = carousel(catalog(listOf(item("added", ContentType.MOVIE, "G", emptyList()))).copy(catalogId = "added"))
        val result = HomeCategoryPresentation.preserveRowOrder(presentation(listOf(added, kept)), listOf(removed.key, kept.key))
        assertEquals(listOf(kept.key, added.key), result.rows.list.map { it.key })
        assertFalse(result.lookups.activeRowKeys.set.contains(removed.key))
        assertFalse(result.lookups.activeCatalogItemIds.set.contains("removed"))
        assertSame(kept.items, result.rows.list.first().items)
        assertEquals(0, result.lookups.rowIndexByKey.map[kept.key])
        val empty = HomeCategoryPresentation.preserveRowOrder(presentation(emptyList()), result.rows.list.map { it.key })
        assertTrue(empty.rows.list.isEmpty())
        assertTrue(empty.lookups.activeRowKeys.set.isEmpty())
    }

    @Test fun `late Kids group cannot appear above an already focused safe family row`() {
        val family = item("family", ContentType.SERIES, "TV-Y", emptyList())
        val science = item("science", ContentType.SERIES, "TV-G", listOf("Science", "Documentary"))
        val originalCatalog = catalog(listOf(family))
        val original = HomeCategoryPresentation.kidsRows(presentation(listOf(carousel(originalCatalog))), listOf(originalCatalog))
        val updatedCatalog = originalCatalog.copy(items = listOf(science, family))
        val updated = HomeCategoryPresentation.kidsRows(presentation(listOf(carousel(updatedCatalog))), listOf(updatedCatalog))
        assertEquals("Science", updated.rows.list.first().title.substringBefore(" · "))
        val result = HomeCategoryPresentation.preserveRowOrder(updated, original.rows.list.map { it.key })
        assertEquals(original.rows.list.single().key, result.rows.list.first().key)
        assertEquals(listOf("family", "science"), result.rows.list.flatMap { it.items.list }.map { it.metaPreview!!.id })
        assertEquals(0, result.lookups.rowIndexByKey.map[result.rows.list.first().key])
        assertTrue(result.rows.list.all { row -> row.items.list.all { it.metaPreview!!.ageRating in setOf("TV-Y", "TV-G") } })
    }

    @Test fun `duplicate grouped keys render once without merging different payloads`() {
        val first = carousel(catalog(listOf(item("first", ContentType.MOVIE, "G", emptyList()))))
            .copy(key = "group::kids:FAMILY_MOVIES")
        val duplicate = first.copy(items = listOf(buildCatalogItem(item("duplicate", ContentType.MOVIE, "G", emptyList()),
            catalog(emptyList()), false, 0)).asStable())
        val result = HomeCategoryPresentation.preserveRowOrder(presentation(listOf(first, duplicate)), listOf(first.key, first.key))
        assertEquals(1, result.rows.list.size)
        assertSame(first.items, result.rows.list.single().items)
        assertEquals(setOf("first"), result.lookups.activeCatalogItemIds.set)
        assertEquals(1, result.lookups.rowByKey.map.size)
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
