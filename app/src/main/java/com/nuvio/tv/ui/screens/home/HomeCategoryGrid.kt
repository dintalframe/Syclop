package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview

internal data class HomeCategoryGridProjection(
    val items: List<GridItem>,
    val pageRows: Map<GridItem.SeeAll, HomeBrowseRow>,
    val retryFocusTargets: Map<GridItem.SeeAll, GridItem>
)

internal val moreCategoryCatalogsAction = GridItem.SeeAll(
    catalogId = "category-catalogs", addonId = "", addonBaseUrl = "", type = "more-catalogs"
)

internal fun buildHomeCategoryGrid(
    section: HomeBrowseSection,
    rows: List<CatalogRow>,
    scopedRows: List<HomeBrowseRow>,
    moreCatalogs: Boolean = false
): HomeCategoryGridProjection {
    val rowsByAction = scopedRows.mapNotNull { row -> row.data?.categoryPageAction()?.let { it to row } }.toMap()
    val pendingPages = scopedRows.filter { it.failed || it.data?.hasMore == true || (it.loading && it.data == null) }
        .associateByTo(linkedMapOf()) { row ->
            row.data?.categoryPageAction() ?: row.categoryRetryAction()
        }
    val pageRows = mutableMapOf<GridItem.SeeAll, HomeBrowseRow>()
    val retryFocusTargets = mutableMapOf<GridItem.SeeAll, GridItem>()
    val items = buildList {
        fun addSection(row: CatalogRow, titles: List<MetaPreview>, name: String, suffix: String = "") {
            if (titles.isEmpty()) return
            add(GridItem.SectionDivider(name, row.catalogId + suffix, row.addonBaseUrl, row.addonId, row.apiType))
            titles.forEach { add(GridItem.Content(it, row.addonBaseUrl, row.catalogId, name, row.addonId)) }
        }

        rows.forEach { row ->
            val sectionStart = size
            if (row.addonId == WATCHED_HISTORY_ADDON_ID) {
                addSection(row, row.items, row.catalogName)
            } else if (section == HomeBrowseSection.KIDS) {
                val groups = row.items.groupBy { HomeBrowseRules.kidsGroup(it, row) }
                KidsBrowseGroup.entries.forEach { group ->
                    addSection(row, groups[group].orEmpty(),
                        HomeCategoryPresentation.kidsRowTitle(group, row.catalogName), "::kids:${group.name}")
                }
            } else {
                addSection(row, HomeBrowseRules.scoped(section, row).items, row.catalogName)
            }
            val action = row.categoryPageAction()
            val firstTitle = getOrNull(sectionStart + 1) as? GridItem.Content
            val source = rowsByAction[action]
            if (firstTitle != null && source != null) {
                retryFocusTargets[source.categoryRetryAction()] = firstTitle
            }
            pendingPages.remove(action)?.let { page ->
                pageRows[action] = page
                add(action)
            }
        }

        pendingPages.forEach { (action, page) ->
            add(GridItem.CollectionHeader("category-page-${page.key}", page.title))
            pageRows[action] = page
            add(action)
            if (page.data != null) retryFocusTargets[page.categoryRetryAction()] = action
        }
        if (moreCatalogs) add(moreCategoryCatalogsAction)
    }
    return HomeCategoryGridProjection(items, pageRows, retryFocusTargets)
}

private fun CatalogRow.categoryPageAction() = GridItem.SeeAll(catalogId, addonId, addonBaseUrl, apiType)
private fun HomeBrowseRow.categoryRetryAction() = GridItem.SeeAll(key, "", "", "retry")
