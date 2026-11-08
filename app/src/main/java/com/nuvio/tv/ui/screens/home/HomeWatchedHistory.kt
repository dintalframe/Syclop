package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchedHistoryItem

internal const val WATCHED_HISTORY_ADDON_ID = "syclop-watched-history"

/** Completed observations are detail cards, never manufactured resume/Next Up positions. */
internal fun watchedHistoryCatalogRow(history: List<WatchedHistoryItem>): CatalogRow? {
    val items = history.asSequence().filter(WatchedHistoryItem::isValidHistoryObservation)
        .sortedByDescending { it.watchedAt }
        .distinctBy { if (isSeriesType(it.contentType)) "series" to it.contentId else it.contentType to it.videoId }
        .map(WatchedHistoryItem::historyPreview)
        .toList()
    if (items.isEmpty()) return null
    return CatalogRow(addonId = WATCHED_HISTORY_ADDON_ID, addonName = "", addonBaseUrl = "",
        catalogId = "watched-history", catalogName = "Watched History", type = ContentType.UNKNOWN,
        rawType = "history", items = items, hasMore = false, supportsSkip = false)
}

private fun WatchedHistoryItem.isValidHistoryObservation(): Boolean = contentId.isNotBlank() && watchedAt > 0

private fun WatchedHistoryItem.historyPreview(): MetaPreview {
    val episodeLabel = if (isSeriesType(contentType) && season != null && episode != null) {
        "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    } else null
    return MetaPreview(id = contentId, type = ContentType.fromString(contentType),
        rawType = contentType, name = title, poster = poster, posterShape = PosterShape.POSTER,
        background = backdrop, logo = logo, description = episodeTitle, releaseInfo = releaseInfo,
        imdbRating = null, genres = genres, ageRating = ageRating, country = country, imdbId = imdbId,
        language = languages.firstOrNull(), sourceAddonBaseUrl = sourceAddonBaseUrl,
        watchedEpisodeLabel = episodeLabel)
}

internal fun projectHomeWatchedHistory(state: HomeUiState, history: List<WatchedHistoryItem>): HomeUiState {
    val base = if (state.catalogRows.any { it.addonId == WATCHED_HISTORY_ADDON_ID }) state.copy(
        catalogRows = state.catalogRows.filterNot { it.addonId == WATCHED_HISTORY_ADDON_ID },
        homeRows = state.homeRows.filterNot { it is HomeRow.Catalog && it.row.addonId == WATCHED_HISTORY_ADDON_ID },
        gridItems = state.gridItems.filterNot { it is GridItem.SectionDivider && it.addonId == WATCHED_HISTORY_ADDON_ID ||
            it is GridItem.Content && it.addonId == WATCHED_HISTORY_ADDON_ID }
    ) else state
    val row = watchedHistoryCatalogRow(history) ?: return base.copy(watchedHistoryItems = history)
    val gridRow = listOf(GridItem.SectionDivider(row.catalogName, row.catalogId, row.addonBaseUrl,
        row.addonId, row.apiType)) + row.items.map { item ->
        GridItem.Content(item, row.addonBaseUrl, row.catalogId, row.catalogName, row.addonId)
    }
    val heroCount = base.gridItems.takeWhile { it is GridItem.Hero }.size
    return base.copy(watchedHistoryItems = history,
        catalogRows = listOf(row) + base.catalogRows,
        homeRows = listOf(HomeRow.Catalog(row)) + base.homeRows.ifEmpty { base.catalogRows.map(HomeRow::Catalog) },
        gridItems = base.gridItems.take(heroCount) + gridRow + base.gridItems.drop(heroCount))
}

/** A single category projection serves the renderer and deterministic regression tests. */
internal fun projectCategoryTrackingRows(section: HomeBrowseSection, state: HomeUiState,
    catalogs: List<CatalogRow>, metadata: List<MetaPreview> = emptyList()): HomeUiState {
    val known = metadata + catalogs.flatMap { it.items } +
        state.catalogRows.filterNot { it.addonId == WATCHED_HISTORY_ADDON_ID }.flatMap { it.items } +
        state.heroItems + state.watchedHistoryItems.filter(WatchedHistoryItem::isValidHistoryObservation)
            .map(WatchedHistoryItem::historyPreview)
    val history = HomeTrackingRowRules.history(section, state.watchedHistoryItems, known)
    val rows = listOfNotNull(watchedHistoryCatalogRow(history)) + catalogs
    return state.copy(catalogRows = rows, homeRows = rows.map(HomeRow::Catalog),
        watchedHistoryItems = history,
        continueWatchingItems = HomeTrackingRowRules.continueWatching(section, state.continueWatchingItems, known),
        upcomingItems = HomeTrackingRowRules.continueWatching(section, state.upcomingItems, known),
        heroItems = emptyList(), isLoading = false, error = null,
        homeLayout = com.nuvio.tv.domain.model.HomeLayout.MODERN, catalogTypeSuffixEnabled = false)
}

internal fun Meta.trackingRowPreview() = MetaPreview(id, type, rawType, name, poster, posterShape,
    background, logo, description, releaseInfo, imdbRating, genres, runtime = runtime,
    ageRating = ageRating, language = language, country = country, imdbId = imdbId)

/** Dialogs may outlive a row update; only the active category's visible progress can dispatch. */
internal fun dispatchCategoryTrackingAction(isCurrent: Boolean, state: HomeUiState,
    item: ContinueWatchingItem, returnToCategory: () -> Unit, action: (ContinueWatchingItem) -> Unit) {
    if (!isCurrent || !state.continueWatchingEnabled ||
        (item !in state.continueWatchingItems && item !in state.upcomingItems)) return
    returnToCategory()
    action(item)
}

internal fun HomeUiState.containsCategoryTrackingDetail(id: String, type: String): Boolean =
    continueWatchingEnabled && (continueWatchingItems + upcomingItems).any {
        it.contentId() == id && it.contentType() == type
    }
