package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.ui.util.asStable

/** UI-only grouping: original provider identities and detail/playback payloads remain intact. */
internal object HomeCategoryPresentation {
    /** Late catalogs must not insert a row above the card that already received focus. */
    fun preserveRowOrder(base: ModernHomePresentationState, previousKeys: List<String>): ModernHomePresentationState {
        val remaining = base.rows.list.distinctBy { it.key }.associateByTo(linkedMapOf<String, HeroCarouselRow>()) { it.key }
        val ordered = buildList<HeroCarouselRow> {
            previousKeys.forEach { key -> remaining.remove(key)?.let(::add) }
            addAll(remaining.values)
        }.mapIndexed { index, row ->
            if (row.globalRowIndex == index) row else row.copy(globalRowIndex = index)
        }
        if (ordered.size == base.rows.list.size && ordered.indices.all { ordered[it] === base.rows.list[it] }) return base
        return ModernHomePresentationState(ordered.asStable(), buildCarouselRowLookups(ordered))
    }

    fun kidsRows(base: ModernHomePresentationState, catalogs: List<CatalogRow>): ModernHomePresentationState {
        val sourceByKey = catalogs.associateBy(::catalogRowKey)
        var rowIndex = 0
        val grouped = base.rows.list.flatMap { row ->
            val source = sourceByKey[row.key] ?: return@flatMap emptyList()
            val byGroup = row.items.list.groupBy { card -> card.metaPreview?.let { HomeBrowseRules.kidsGroup(it, source) } }
            KidsBrowseGroup.entries.mapNotNull { group ->
                val items = byGroup[group].orEmpty()
                if (items.isEmpty()) null else row.copy(
                    key = "${row.key}::kids:${group.name}",
                    title = kidsRowTitle(group, row.title), globalRowIndex = rowIndex++, items = items.asStable()
                )
            }
        }
        return ModernHomePresentationState(grouped.asStable(), buildCarouselRowLookups(grouped))
    }

    private fun kidsRowTitle(group: KidsBrowseGroup, catalogTitle: String): String {
        val parts = catalogTitle.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        // Only the final catalog label repeats the group; keep the source and other context.
        val context = if (parts.lastOrNull()?.equals(group.label, ignoreCase = true) == true)
            parts.dropLast(1) else parts
        return (listOf(group.label) + context).joinToString(" · ")
    }
}
