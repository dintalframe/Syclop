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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import com.nuvio.tv.core.network.NetworkResult

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
    onLongPress: (MetaPreview, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    showContinueWatchingManualPlayOption: Boolean = false
) {
    val observed by viewModel.state.collectAsStateWithLifecycle()
    val state = observed.takeIf { it.section == section && viewModel.isCurrent() }
    val catalogs = remember(state?.rows) { state?.rows.orEmpty().mapNotNull { it.data?.takeIf { data -> data.items.isNotEmpty() } } }
    val metadata = rememberTrackingRowMetadata(section, viewModel, homeViewModel, homeUiState)
    val categoryState = remember(section, homeUiState, catalogs, metadata) {
        projectCategoryTrackingRows(section, homeUiState, catalogs, metadata)
    }
    val rows = categoryState.catalogRows
    val hasVisibleProgress = categoryState.continueWatchingEnabled &&
        (categoryState.continueWatchingItems.isNotEmpty() || categoryState.upcomingItems.isNotEmpty())
    // Retain the established, focusable loading/empty/retry destination when no card can render.
    if (rows.isEmpty() && !hasVisibleProgress) {
        HomeBrowseContent(section, viewModel, tabFocus, entry, posterStyle, onDetail, isWatched, onLongPress)
        return
    }

    val currentState = state ?: return
    key(section, currentState.originRevision, currentState.requestRevision) {
        HomeCategoryModernContent(section, viewModel, homeViewModel, categoryState,
            entry, currentState, rows, onDetail, isWatched, onLongPress,
            onContinueWatchingClick, onContinueWatchingStartFromBeginning,
            onContinueWatchingPlayManually, showContinueWatchingManualPlayOption)
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
    isWatched: (MetaPreview) -> Boolean, onLongPress: (MetaPreview, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean
) {
    val revision = state.originRevision
    val focusState = remember(section, revision) { viewModel.focusState(section, revision) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var lastFocusedRow by remember(section, revision) { mutableStateOf<String?>(null) }
    val prepared = rememberHomeCategoryPresentation(section, revision, state.requestRevision, rows, homeUiState)
    val presentation = prepared.presentation

    val uiState = remember(homeUiState, rows, contentHasFocus) {
        homeUiState.copy(
            catalogRows = rows, homeRows = rows.map(HomeRow::Catalog),
            heroItems = emptyList(), isLoading = false, error = null,
            homeLayout = HomeLayout.MODERN,
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

    val entryRequest = rememberHomeCategoryEntryRequest(
        entry = entry, ready = presentation.rows.list.isNotEmpty(),
        isCurrent = viewModel::isCurrent,
        consumeReturn = { viewModel.consumeCategoryReturn(section, revision) }
    )
    // A last-row focus opts into the next small batch. Empty later batches do not strand the
    // user, and all existing ViewModel concurrency, catalog and card limits still apply.
    LaunchedEffect(lastFocusedRow, state.rows, state.moreCatalogs, presentation) {
        if (prepared.current && lastFocusedRow != null && lastFocusedRow == presentation.rows.list.lastOrNull()?.key &&
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
                    val catalogMember = rows.any { row -> row.addonBaseUrl == base && row.items.any {
                        it.id == id && it.apiType == type && (row.addonId == WATCHED_HISTORY_ADDON_ID || HomeBrowseRules.matches(section, it, row))
                    } }
                    val progressMember = base.isEmpty() && uiState.containsCategoryTrackingDetail(id, type)
                    if (viewModel.isCurrent() && (catalogMember || progressMember)) {
                        viewModel.returnToCategory(section, revision)
                        onDetail(id, type, base)
                    }
                },
                onContinueWatchingClick = { item ->
                    dispatchCategoryTrackingAction(viewModel.isCurrent(), uiState, item,
                        { viewModel.returnToCategory(section, revision) }, onContinueWatchingClick)
                },
                onContinueWatchingStartFromBeginning = { item ->
                    dispatchCategoryTrackingAction(viewModel.isCurrent(), uiState, item,
                        { viewModel.returnToCategory(section, revision) }, onContinueWatchingStartFromBeginning)
                },
                onContinueWatchingPlayManually = { item ->
                    dispatchCategoryTrackingAction(viewModel.isCurrent(), uiState, item,
                        { viewModel.returnToCategory(section, revision) }, onContinueWatchingPlayManually)
                },
                showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
                onRequestTrailerPreview = { id, title, release, type ->
                    if (viewModel.isCurrent()) homeViewModel.requestTrailerPreview(id, title, release, type)
                },
                onLoadMoreCatalog = viewModel::loadMoreCatalog,
                onRemoveContinueWatching = { id, season, episode, nextUp ->
                    if (viewModel.isCurrent()) homeViewModel.onEvent(HomeEvent.OnRemoveContinueWatching(id, season, episode, nextUp))
                },
                isCatalogItemWatched = isWatched,
                onCatalogItemLongPress = { item, base ->
                    if (viewModel.isCurrent() && rows.any { row -> row.addonBaseUrl == base && row.items.any {
                        it.id == item.id && it.apiType == item.apiType && (row.addonId == WATCHED_HISTORY_ADDON_ID || HomeBrowseRules.matches(section, it, row))
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

internal data class HomeCategoryPreparedPresentation(
    val presentation: ModernHomePresentationState,
    val current: Boolean
)

/** The category renderer and deterministic TV fixtures share the same asynchronous preparation. */
@Composable
internal fun rememberHomeCategoryPresentation(
    section: HomeBrowseSection,
    originRevision: Long,
    requestRevision: Long,
    rows: List<CatalogRow>,
    homeUiState: HomeUiState
): HomeCategoryPreparedPresentation = key(section, originRevision, requestRevision) {
    val cache = remember { ModernCarouselRowBuildCache() }
    val displayedRowKeys = remember { mutableStateOf(emptyList<String>()) }
    val buildLock = remember { Mutex() }
    val context = LocalContext.current
    val input = remember(rows, homeUiState.continueWatchingItems, homeUiState.upcomingItems,
        homeUiState.continueWatchingEnabled, homeUiState.modernLandscapePostersEnabled,
        homeUiState.showFullReleaseDate, homeUiState.homeImdbRatingsVisibility,
        homeUiState.detailImdbRatingsVisibility, LocaleCache.localeTag) {
        ModernHomePresentationInput(
            homeRows = rows.map(HomeRow::Catalog), catalogRows = rows,
            continueWatchingItems = homeUiState.continueWatchingItems.takeIf { homeUiState.continueWatchingEnabled }.orEmpty(),
            upcomingItems = homeUiState.upcomingItems.takeIf { homeUiState.continueWatchingEnabled }.orEmpty(),
            useLandscapePosters = homeUiState.modernLandscapePostersEnabled,
            showCatalogTypeSuffix = false,
            showFullReleaseDate = homeUiState.showFullReleaseDate,
            showImdbRatings = homeUiState.homeImdbRatingsVisibility.showRatings,
            detailImdbRatingsVisibility = homeUiState.detailImdbRatingsVisibility,
            localeTag = LocaleCache.localeTag
        )
    }
    val produced by produceState<CategoryPresentationResult?>(null, input, context) {
        val previousRowKeys = displayedRowKeys.value
        val built = buildLock.withLock {
            withContext(Dispatchers.Default) {
                val base = buildModernHomePresentation(input, cache, context)
                val grouped = if (section == HomeBrowseSection.KIDS) HomeCategoryPresentation.kidsRows(base, rows) else base
                HomeCategoryPresentation.preserveRowOrder(grouped, previousRowKeys)
            }
        }
        displayedRowKeys.value = built.rows.list.map { it.key }
        value = CategoryPresentationResult(input, built)
    }
    // Keep current-profile cards composed during page append. The enclosing revision key
    // recreates this state on a profile or request change, so prior artwork cannot persist.
    HomeCategoryPreparedPresentation(produced?.presentation ?: ModernHomePresentationState(), produced?.input === input)
}

/** Resolve existing media IDs for category facts; names/languages never establish membership. */
@Composable
private fun rememberTrackingRowMetadata(section: HomeBrowseSection, browse: HomeBrowseViewModel,
    home: HomeViewModel, state: HomeUiState): List<MetaPreview> {
    val revision = browse.selectionRevision
    val requests = remember(state.continueWatchingItems, state.upcomingItems, state.watchedHistoryItems) {
        (state.continueWatchingItems + state.upcomingItems).map { item -> when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.contentType to item.progress.contentId
            is ContinueWatchingItem.NextUp -> item.info.contentType to item.info.contentId
        } }.plus(state.watchedHistoryItems.map { it.contentType to it.contentId }).distinct().take(48)
    }
    val metadata by produceState(revision to emptyList<MetaPreview>(), section, revision, requests) {
        val permits = Semaphore(4)
        val resolved = coroutineScope {
            requests.map { (type, id) -> async {
                permits.withPermit {
                    try {
                        val cached = home.metaRepository.getCachedMeta(type, id)
                        val meta = cached ?: withTimeoutOrNull(5_000L) {
                            (home.metaRepository.getMetaFromAllAddons(type, id)
                                .first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
                        }
                        meta?.trackingRowPreview()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
            } }.awaitAll().filterNotNull()
        }
        if (browse.selectionRevision == revision && browse.isCurrent()) value = revision to resolved
    }
    return metadata.takeIf { it.first == revision }?.second.orEmpty()
}

/** Down and a guarded detail return use one entry path; a catalog update alone never enters it. */
@Composable
internal fun rememberHomeCategoryEntryRequest(
    entry: HomeBrowseEntry,
    ready: Boolean,
    isCurrent: () -> Boolean,
    consumeReturn: () -> Boolean
): Int {
    var request by remember(entry) { mutableIntStateOf(0) }
    val enterLatest by rememberUpdatedState<() -> Unit> {
        if (ready && isCurrent()) {
            entry.pending = false
            request++
        } else entry.pending = true
    }
    DisposableEffect(entry) {
        entry.enter = { enterLatest() }
        onDispose { entry.enter = {} }
    }
    LaunchedEffect(entry) {
        if (consumeReturn()) entry.pending = true
    }
    LaunchedEffect(ready) {
        if (entry.pending) enterLatest()
    }
    return request
}
