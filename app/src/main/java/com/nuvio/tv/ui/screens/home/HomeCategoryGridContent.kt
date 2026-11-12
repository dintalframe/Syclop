package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.PosterCardStyle

@Composable
internal fun HomeCategoryGridContent(
    section: HomeBrowseSection,
    viewModel: HomeBrowseViewModel,
    homeViewModel: HomeViewModel,
    categoryState: HomeUiState,
    entry: HomeBrowseEntry,
    state: HomeBrowseState,
    posterStyle: PosterCardStyle,
    onDetail: (String, String, String) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onLongPress: (MetaPreview, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean
) {
    val revision = state.originRevision
    val requestRevision = state.requestRevision
    val focusState = remember(section, revision) { viewModel.focusState(section, revision) }
    val projection = remember(section, categoryState.catalogRows, state.rows, state.moreCatalogs) {
        buildHomeCategoryGrid(section, categoryState.catalogRows, state.rows, state.moreCatalogs)
    }
    val latestCategoryState by rememberUpdatedState(categoryState)
    val latestProjection by rememberUpdatedState(projection)
    val latestBrowseState by rememberUpdatedState(state)
    fun isCurrent(): Boolean {
        val current = viewModel.state.value
        return viewModel.isCurrent() && current.section == section && current.originRevision == revision &&
            current.requestRevision == requestRevision
    }
    fun returnToCategory() = viewModel.returnToCategory(section, revision)
    fun containsTitle(id: String, type: String, base: String): Boolean =
        latestProjection.items.any {
            it is GridItem.Content && it.addonBaseUrl == base && it.item.id == id && it.item.apiType == type
        }
    fun dispatchProgress(item: ContinueWatchingItem, action: (ContinueWatchingItem) -> Unit) {
        val latest = latestCategoryState
        val original = (latest.continueWatchingItems + latest.upcomingItems).firstOrNull {
            it.contentType() == item.contentType() && continueWatchingItemKey(it) == continueWatchingItemKey(item)
        } ?: return
        dispatchCategoryTrackingAction(isCurrent(), latest, original, ::returnToCategory, action)
    }

    val entryRequest = rememberHomeCategoryEntryRequest(
        entry, ready = true, isCurrent = ::isCurrent,
        consumeReturn = { viewModel.consumeCategoryReturn(section, revision) }
    )
    val more = stringResource(R.string.discover_load_more)
    val loading = stringResource(R.string.discover_loading)
    val retry = stringResource(R.string.action_retry)
    GridHomeContent(
        uiState = categoryState.copy(
            homeLayout = HomeLayout.GRID,
            gridItems = projection.items,
            heroSectionEnabled = false,
            isLoading = state.loading || state.rows.any { it.loading }
        ),
        gridFocusState = focusState,
        posterCardStyle = posterStyle,
        limitCatalogPreview = false,
        contentEntryRequest = entryRequest,
        onNavigateToDetail = { id, type, base ->
            if (isCurrent() && (containsTitle(id, type, base) ||
                    base.isEmpty() && latestCategoryState.containsCategoryTrackingDetail(id, type))) {
                returnToCategory()
                onDetail(id, type, base)
            }
        },
        onContinueWatchingClick = { dispatchProgress(it, onContinueWatchingClick) },
        onContinueWatchingStartFromBeginning = { dispatchProgress(it, onContinueWatchingStartFromBeginning) },
        onContinueWatchingPlayManually = { dispatchProgress(it, onContinueWatchingPlayManually) },
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onRemoveContinueWatching = { id, season, episode, nextUp ->
            if (isCurrent()) homeViewModel.onEvent(HomeEvent.OnRemoveContinueWatching(id, season, episode, nextUp))
        },
        onNavigateToCatalogSeeAll = { _, _, _ -> },
        onCatalogAction = { action ->
            if (isCurrent()) {
                if (action == moreCategoryCatalogsAction && latestBrowseState.moreCatalogs) {
                    viewModel.loadMoreCatalogs()
                } else {
                    latestProjection.pageRows[action]?.let { viewModel.loadMore(it.key) }
                }
            }
        },
        catalogActionLabel = { action ->
            val page = projection.pageRows[action]
            when {
                action == moreCategoryCatalogsAction -> "$more · ${section.label}"
                page?.loading == true || action in projection.retryFocusTargets -> loading
                page?.failed == true -> retry
                else -> more
            }
        },
        catalogActionReplacement = { action ->
            latestProjection.retryFocusTargets[action].takeIf { entry.canEnterContent() && isCurrent() }
        },
        isCatalogItemWatched = isWatched,
        onCatalogItemLongPress = { item, base ->
            if (isCurrent() && containsTitle(item.id, item.apiType, base)) onLongPress(item, base)
        },
        onItemFocus = { if (isCurrent()) homeViewModel.onItemFocus(it) },
        onSaveGridFocusState = { index, offset, key ->
            viewModel.saveFocusState(section, revision, HomeScreenFocusState(
                verticalScrollIndex = index, verticalScrollOffset = offset,
                focusedItemKey = key, hasSavedFocus = true
            ))
        }
    )
}
