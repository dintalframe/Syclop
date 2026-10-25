@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.SyclopBrandAnimation
import com.nuvio.tv.ui.components.SyclopBrandState
import com.nuvio.tv.ui.components.PosterCardStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Category data is separate; presentation uses the same hero, cards and rows as Modern Home. */
@Composable
internal fun HomeCategoryContent(
    section: HomeBrowseSection,
    viewModel: HomeBrowseViewModel,
    homeViewModel: HomeViewModel,
    homeUiState: HomeUiState,
    tabFocus: FocusRequester,
    entry: HomeBrowseEntry,
    posterStyle: PosterCardStyle,
    onDetail: (String, String, String) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onLongPress: (MetaPreview, String) -> Unit
) {
    val observed by viewModel.state.collectAsStateWithLifecycle()
    val state = observed.takeIf { it.section == section && viewModel.isCurrent() }
    val rows = remember(state?.rows) { state?.rows.orEmpty().mapNotNull { it.data?.takeIf { data -> data.items.isNotEmpty() } } }
    // Retain the established, focusable loading/empty/retry destination when no card can render.
    if (rows.isEmpty()) {
        HomeBrowseContent(section, viewModel, tabFocus, entry, posterStyle, onDetail, isWatched, onLongPress)
        return
    }

    val currentState = state ?: return
    key(section, currentState.originRevision) {
        HomeCategoryModernContent(section, viewModel, homeViewModel, homeUiState,
            entry, currentState, rows, onDetail, isWatched, onLongPress)
    }
}

private data class CategoryPresentationResult(
    val input: ModernHomePresentationInput, val presentation: ModernHomePresentationState
)

@Composable
private fun HomeCategoryModernContent(
    section: HomeBrowseSection, viewModel: HomeBrowseViewModel, homeViewModel: HomeViewModel,
    homeUiState: HomeUiState, entry: HomeBrowseEntry, state: HomeBrowseState,
    rows: List<CatalogRow>, onDetail: (String, String, String) -> Unit,
    isWatched: (MetaPreview) -> Boolean, onLongPress: (MetaPreview, String) -> Unit
) {
    val revision = state.originRevision
    val focusState = remember(section, revision) { viewModel.focusState(section, revision) }
    var entryRequest by remember(section, revision) { mutableIntStateOf(0) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var lastFocusedRow by remember(section, revision) { mutableStateOf<String?>(null) }
    val cache = remember(section, revision) { ModernCarouselRowBuildCache() }
    val buildLock = remember { Mutex() }
    val context = LocalContext.current
    val input = remember(rows, homeUiState.modernLandscapePostersEnabled,
        homeUiState.showFullReleaseDate, homeUiState.homeImdbRatingsVisibility,
        homeUiState.detailImdbRatingsVisibility, LocaleCache.localeTag) {
        ModernHomePresentationInput(
            homeRows = rows.map(HomeRow::Catalog), catalogRows = rows,
            continueWatchingItems = emptyList(), upcomingItems = emptyList(),
            useLandscapePosters = homeUiState.modernLandscapePostersEnabled,
            showCatalogTypeSuffix = false,
            showFullReleaseDate = homeUiState.showFullReleaseDate,
            showImdbRatings = homeUiState.homeImdbRatingsVisibility.showRatings,
            detailImdbRatingsVisibility = homeUiState.detailImdbRatingsVisibility,
            localeTag = LocaleCache.localeTag
        )
    }
    val produced by produceState<CategoryPresentationResult?>(null, input, context) {
        val built = buildLock.withLock {
            withContext(Dispatchers.Default) {
                buildModernHomePresentation(input, cache, context).let { base ->
                    if (section == HomeBrowseSection.KIDS) HomeCategoryPresentation.kidsRows(base, rows) else base
                }
            }
        }
        value = CategoryPresentationResult(input, built)
    }
    // Keep current-profile cards composed during page append. The enclosing revision key
    // recreates this state on a profile change, so another profile's artwork cannot persist.
    val presentation = produced?.presentation ?: ModernHomePresentationState()

    val uiState = remember(homeUiState, rows, contentHasFocus) {
        homeUiState.copy(
            catalogRows = rows, homeRows = rows.map(HomeRow::Catalog),
            continueWatchingItems = emptyList(), upcomingItems = emptyList(),
            heroItems = emptyList(), isLoading = false, error = null,
            homeLayout = HomeLayout.MODERN, continueWatchingEnabled = false,
            catalogTypeSuffixEnabled = false,
            // A highlighted category already has catalog artwork and metadata. Do not wait
            // for focus-triggered enrichment until a card actually receives focus.
            heroEnrichmentEnabled = homeUiState.heroEnrichmentEnabled && contentHasFocus
        )
    }
    val enrichingItemId by homeViewModel.enrichingItemId.collectAsStateWithLifecycle()
    val lastEnrichedPreview by homeViewModel.lastEnrichedPreview.collectAsStateWithLifecycle()
    val enrichedPreviews by homeViewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds by homeViewModel.failedEnrichmentIds.collectAsStateWithLifecycle()

    val enterLatest by rememberUpdatedState<() -> Unit> {
        if (presentation.rows.list.isNotEmpty() && viewModel.isCurrent()) {
            entry.pending = false
            entryRequest++
        } else entry.pending = true
    }
    DisposableEffect(entry) {
        entry.enter = { enterLatest() }
        onDispose { entry.enter = {} }
    }
    LaunchedEffect(section, revision) {
        if (viewModel.consumeCategoryReturn(section, revision)) entry.pending = true
    }
    LaunchedEffect(presentation.rows.list.isNotEmpty()) {
        if (entry.pending) enterLatest()
    }
    // A last-row focus opts into the next small batch. Empty later batches do not strand the
    // user, and all existing ViewModel concurrency, catalog and card limits still apply.
    LaunchedEffect(lastFocusedRow, state.rows, state.moreCatalogs, presentation) {
        if (produced?.input === input && lastFocusedRow != null && lastFocusedRow == presentation.rows.list.lastOrNull()?.key &&
            state.moreCatalogs && state.rows.none { it.loading } && viewModel.isCurrent()) {
            viewModel.loadMoreCatalogs()
        }
    }

    Box(Modifier.fillMaxSize().onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup()) {
        if (presentation.rows.list.isEmpty()) {
            SyclopBrandAnimation(SyclopBrandState.Loading, Modifier.align(Alignment.Center))
        } else {
            ModernHomeContent(
                uiState = uiState, modernPresentation = presentation,
                focusState = focusState, autoFocusOnEntry = false,
                contentEntryRequest = entryRequest, inheritPreviousHeroBackdrop = false,
                enrichingItemId = enrichingItemId.takeIf { contentHasFocus },
                lastEnrichedPreview = lastEnrichedPreview.takeIf { contentHasFocus },
                enrichedPreviews = if (contentHasFocus) enrichedPreviews else emptyMap(),
                failedEnrichmentIds = failedEnrichmentIds,
                trailerPreviewUrls = homeViewModel.trailerPreviewUrls,
                trailerPreviewAudioUrls = homeViewModel.trailerPreviewAudioUrls,
                onNavigateToDetail = { id, type, base ->
                    if (viewModel.isCurrent() && rows.any { row -> row.addonBaseUrl == base && row.items.any {
                        it.id == id && it.apiType == type && HomeBrowseRules.matches(section, it, row)
                    } }) {
                        viewModel.returnToCategory(section, revision)
                        onDetail(id, type, base)
                    }
                },
                onContinueWatchingClick = {},
                onRequestTrailerPreview = { id, title, release, type ->
                    if (viewModel.isCurrent()) homeViewModel.requestTrailerPreview(id, title, release, type)
                },
                onLoadMoreCatalog = viewModel::loadMoreCatalog,
                onRemoveContinueWatching = { _, _, _, _ -> },
                isCatalogItemWatched = isWatched,
                onCatalogItemLongPress = { item, base ->
                    if (viewModel.isCurrent() && rows.any { row -> row.addonBaseUrl == base && row.items.any {
                        it.id == item.id && it.apiType == item.apiType && HomeBrowseRules.matches(section, it, row)
                    } }) onLongPress(item, base)
                },
                onItemFocus = { if (viewModel.isCurrent()) homeViewModel.onItemFocus(it) },
                onPreloadAdjacentItem = { if (viewModel.isCurrent()) homeViewModel.preloadAdjacentItem(it) },
                onSaveFocusState = { vi, vo, key, items, scrolls, anchors, ri, ii ->
                    viewModel.saveFocusState(section, revision, HomeScreenFocusState(
                        verticalScrollIndex = vi, verticalScrollOffset = vo, focusedRowKey = key,
                        focusedItemKeyByRow = items, catalogRowScrollStates = scrolls,
                        catalogRowScrollAnchors = anchors, focusedRowIndex = ri,
                        focusedItemIndex = ii, hasSavedFocus = true
                    ))
                },
                onRowItemFocusedCallback = { key, _, _ -> lastFocusedRow = key }
            )
        }
        if (state.rows.any { it.failed }) {
            Text("Some catalogs could not load. Select ${section.label} again to retry.",
                style = MaterialTheme.typography.bodySmall, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.8f)).padding(8.dp))
        }
    }
}
