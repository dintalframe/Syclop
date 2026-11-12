package com.nuvio.tv.ui.screens.home

internal fun GridItem.SeeAll.homeGridKey(): String =
    "see_all_${addonBaseUrl.hashCode()}_${catalogId}_${addonId}_$type"

internal fun planHomeGridCatalogs(
    items: List<Pair<GridItem, String>>,
    columns: Int,
    rows: Int,
    retainedKey: String? = null
): List<Pair<GridItem, String>> = buildList {
    val capacity = columns * rows
    var index = 0
    while (index < items.size) {
        val entry = items[index++]
        add(entry)
        val section = entry.first as? GridItem.SectionDivider ?: continue

        val firstTitle = index
        while (items.getOrNull(index)?.first is GridItem.Content) index++
        val titleCount = index - firstTitle
        val existingSeeAll = items.getOrNull(index)?.takeIf { it.first is GridItem.SeeAll }
        if (existingSeeAll != null) index++

        if (existingSeeAll == null && titleCount <= capacity) {
            addAll(items.subList(firstTitle, firstTitle + titleCount))
            continue
        }

        // In a one-slot grid, keep the first title and put See All on the next row.
        var previewCount = minOf(titleCount, (capacity - 1).coerceAtLeast(1))
        if (columns > 1 && previewCount > 0 && previewCount % columns == 0) {
            previewCount--
        }
        val previewEnd = firstTitle + previewCount
        val retained = if (retainedKey == null) null else items.subList(previewEnd, firstTitle + titleCount)
            .firstOrNull { it.second == retainedKey }
        addAll(items.subList(firstTitle, if (retained != null && previewCount > 0) previewEnd - 1 else previewEnd))
        if (retained != null) add(retained)
        add(existingSeeAll ?: GridItem.SeeAll(
            section.catalogId, section.addonId, section.addonBaseUrl, section.type
        ).let { it to it.homeGridKey() })
    }
}
