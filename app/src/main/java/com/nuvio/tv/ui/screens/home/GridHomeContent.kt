package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.items
import com.nuvio.tv.LocalContentFocusRequester
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.LocalLandscapePosterMode
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.collectionFolderCardImageUrl
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.components.rememberArtworkBackedCardGlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun GridHomeContent(
    uiState: HomeUiState,
    gridFocusState: HomeScreenFocusState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    onItemFocus: (com.nuvio.tv.domain.model.MetaPreview) -> Unit = {},
    catalogSeeAllLabel: String? = null,
    onSaveGridFocusState: (Int, Int, String?) -> Unit,
    onFocusedRowKeyChanged: (String?) -> Unit = {},
    scrollToTopTrigger: Int = 0,
    limitCatalogPreview: Boolean = true,
    contentEntryRequest: Int? = null,
    onCatalogAction: ((GridItem.SeeAll) -> Unit)? = null,
    catalogActionLabel: ((GridItem.SeeAll) -> String)? = null,
    catalogActionReplacement: ((GridItem.SeeAll) -> GridItem?)? = null
) {
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = gridFocusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = gridFocusState.verticalScrollOffset
    )
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val lastFocusedGridItemKey = remember { mutableStateOf(gridFocusState.focusedItemKey) }
    var restoredSavedGridFocus by remember { mutableStateOf(false) }
    // Saveable so the rows come back where they were left after navigating away and
    // returning. The restorer falls back to the first card when the remembered one is
    // off screen, so the scroll has to survive too, not just the index.
    val lastFocusedCwIndex = rememberSaveable { mutableIntStateOf(-1) }
    val lastFocusedUpcomingIndex = rememberSaveable { mutableIntStateOf(-1) }
    val cwFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val upcomingFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val cwRowFocusRequester = remember { FocusRequester() }
    val upcomingRowFocusRequester = remember { FocusRequester() }
    val cwListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val upcomingListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Improved Back navigation for CW/Upcoming rows: scroll to first item
    val contentHasFocus = remember { mutableStateOf(false) }
    val activeCwRowKey = remember { mutableStateOf<String?>(null) }
    val cwPendingScrollToStart = remember { mutableIntStateOf(0) }
    val upcomingPendingScrollToStart = remember { mutableIntStateOf(0) }
    BackHandler(enabled = contentHasFocus.value && run {
        val key = activeCwRowKey.value ?: return@run false
        val idx = if (key == "continue_watching") lastFocusedCwIndex.intValue else lastFocusedUpcomingIndex.intValue
        idx > 0
    }) {
        val key = activeCwRowKey.value ?: return@BackHandler
        if (key == "continue_watching") {
            lastFocusedCwIndex.intValue = 0
            cwPendingScrollToStart.intValue++
        } else {
            lastFocusedUpcomingIndex.intValue = 0
            upcomingPendingScrollToStart.intValue++
        }
    }

    // Scroll to top when triggered from sidebar Home button.
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            gridState.scrollToItem(0, 0)
        }
    }

    // Save scroll state when leaving
    DisposableEffect(Unit) {
        onDispose {
            onSaveGridFocusState(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                lastFocusedGridItemKey.value
            )
        }
    }

    // Offset for section indices: pre-items + continue watching item (if present)
    val focusedCatalogAction = remember { mutableStateOf<GridItem.SeeAll?>(null) }
    var focusedCatalogActionIndex by remember { mutableIntStateOf(0) }
    val latestGridItems by rememberUpdatedState(uiState.gridItems)
    val latestActionReplacement by rememberUpdatedState(catalogActionReplacement)
    val actionHandoff by remember {
        derivedStateOf {
            val action = focusedCatalogAction.value
            if (latestActionReplacement != null && action != null &&
                lastFocusedGridItemKey.value == action.homeGridKey() &&
                action !in latestGridItems) {
                latestActionReplacement?.invoke(action)?.let { action to it }
            } else null
        }
    }
    val retainedAction = actionHandoff?.first
    // Keep the focused action's lazy node attached until its replacement can receive focus.
    val gridItems = if (retainedAction == null) uiState.gridItems else {
        remember(uiState.gridItems, retainedAction, focusedCatalogActionIndex) {
            uiState.gridItems.toMutableList().apply {
                add(focusedCatalogActionIndex.coerceIn(0, size), retainedAction)
            }
        }
    }
    val continueWatchingItems = remember(
        uiState.continueWatchingEnabled,
        uiState.continueWatchingItems,
        uiState.customPosterUrlPattern,
        uiState.customPosterEnabledScreens,
    ) {
        if (uiState.continueWatchingEnabled) {
            uiState.continueWatchingItems.withCustomPosterUrls(
                com.nuvio.tv.core.poster.patternForScreen(uiState.customPosterUrlPattern, com.nuvio.tv.core.poster.CustomPosterScreen.CONTINUE_WATCHING, uiState.customPosterEnabledScreens)
            )
        } else {
            emptyList()
        }
    }
    val upcomingItems = remember(
        uiState.continueWatchingEnabled, uiState.upcomingItems,
        uiState.customPosterUrlPattern, uiState.customPosterEnabledScreens
    ) {
        if (uiState.continueWatchingEnabled) {
            uiState.upcomingItems.withCustomPosterUrls(
                com.nuvio.tv.core.poster.patternForScreen(uiState.customPosterUrlPattern, com.nuvio.tv.core.poster.CustomPosterScreen.CONTINUE_WATCHING, uiState.customPosterEnabledScreens)
            )
        } else emptyList()
    }

    // Pre-compute whether hero exists to avoid repeated list scan in derivedStateOf
    val hasHero = remember(gridItems) {
        gridItems.any { it is GridItem.Hero }
    }
    val hasCatalogGrid = remember(gridItems) {
        gridItems.any { it is GridItem.Content || it is GridItem.CollectionFolder }
    }
    val shouldRequestInitialFocus = remember(
        gridFocusState.hasSavedFocus,
        gridFocusState.verticalScrollIndex,
        gridFocusState.verticalScrollOffset
    ) {
        !gridFocusState.hasSavedFocus &&
            gridFocusState.verticalScrollIndex == 0 &&
            gridFocusState.verticalScrollOffset == 0
    }
    val heroFocusRequester = remember { FocusRequester() }
    val savedHeroIndex = rememberSaveable { mutableIntStateOf(0) }
    val hasContinueWatching = continueWatchingItems.isNotEmpty()
    val tabFocusRequester = LocalHomeBrowseTabFocusRequester.current
    val enterFromHomeTab = tabFocusRequester != null

    // Once the user scrolls, initial focus must not pull back to the hero.
    var userScrolledGrid by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }.collect { scrolledAway ->
            if (scrolledAway) userScrolledGrid = true
        }
    }

    // Keyed on the "is there something focusable" booleans (not gridItems.size, which
    // churns per catalog and stole focus back to the top); retries until the target attaches.
    val heroExpected = uiState.heroSectionEnabled && uiState.heroCatalogKeys.isNotEmpty()
    val heroResolved = !heroExpected || hasHero
    var heroDeferTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(shouldRequestInitialFocus, heroExpected) {
        if (!shouldRequestInitialFocus || !heroExpected) return@LaunchedEffect
        delay(2000)
        heroDeferTimedOut = true
    }
    val deferGridContent = shouldRequestInitialFocus && !hasCatalogGrid &&
        !enterFromHomeTab && !heroResolved && !heroDeferTimedOut

    LaunchedEffect(
        shouldRequestInitialFocus,
        hasHero,
        hasContinueWatching,
        enterFromHomeTab,
        hasCatalogGrid
    ) {
        if (!shouldRequestInitialFocus || userScrolledGrid || enterFromHomeTab) return@LaunchedEffect
        if (!hasHero || hasCatalogGrid) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            if (userScrolledGrid) return@LaunchedEffect
            if (runCatching { heroFocusRequester.requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
        }
    }

    // Throttle D-pad key repeats to prevent HWUI overload when a key is held down.

    val gridItemsWithKeys = remember(gridItems) {
        val occurrences = mutableMapOf<String, Int>()
        gridItems.map { item ->
            val key = when (item) {
                is GridItem.Hero -> "hero"
                is GridItem.SectionDivider -> "divider_${item.addonBaseUrl.hashCode()}_${item.catalogId}_${item.addonId}_${item.type}"
                is GridItem.Content -> {
                    val base = item.homeGridRowPrefix() + item.item.id
                    val count = occurrences.getOrDefault(base, 0)
                    occurrences[base] = count + 1
                    "${base}_$count"
                }
                is GridItem.SeeAll -> item.homeGridKey()
                is GridItem.CollectionHeader -> "col_header_${item.collectionId}"
                is GridItem.CollectionFolder -> "col_folder_${item.collectionId}_${item.folder.id}"
            }
            item to key
        }
    }

    val (featuredItems, catalogItems) = remember(gridItemsWithKeys) {
        gridItemsWithKeys.partition { (item, _) -> item is GridItem.Hero }
    }
    if (deferGridContent) {
        HomeStartupIndicator()
        return
    }

    val contentFocusRequester = LocalContentFocusRequester.current
    val sidebarExpanded by rememberUpdatedState(LocalSidebarExpanded.current)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        val gridWidth = maxWidth
        val startPadding = NuvioTheme.spacing.xxxl
        val endPadding = NuvioTheme.spacing.xl
        val spacing = NuvioTheme.spacing.md
        val globalLandscape = LocalLandscapePosterMode.current
        val gridColumnMinSize = posterCardStyle.width
        val actualColumnsPerRow = with(LocalDensity.current) {
            val available = constraints.maxWidth - startPadding.roundToPx() - endPadding.roundToPx()
            val gap = spacing.roundToPx()
            ((available + gap) / (gridColumnMinSize.roundToPx() + gap)).coerceAtLeast(1)
        }
        val retainedKey = gridFocusState.focusedItemKey
        val trimmedCatalogs = remember(catalogItems, actualColumnsPerRow, limitCatalogPreview, retainedKey) {
            if (limitCatalogPreview) planHomeGridCatalogs(catalogItems, actualColumnsPerRow, rows = 2, retainedKey = retainedKey)
            else catalogItems
        }
        val openingEnd = remember(trimmedCatalogs) {
            (1 until trimmedCatalogs.size).firstOrNull { index ->
                val item = trimmedCatalogs[index].first
                item is GridItem.SectionDivider || item is GridItem.CollectionHeader
            } ?: trimmedCatalogs.size
        }
        val leading = remember(hasCatalogGrid, featuredItems, trimmedCatalogs, openingEnd) {
            if (hasCatalogGrid) trimmedCatalogs.take(openingEnd) else featuredItems
        }
        val trailing = remember(hasCatalogGrid, featuredItems, trimmedCatalogs, openingEnd) {
            if (hasCatalogGrid) featuredItems + trimmedCatalogs.drop(openingEnd) else trimmedCatalogs
        }
        val firstGridRowKeys = remember(hasCatalogGrid, leading, actualColumnsPerRow) {
            if (hasCatalogGrid) leading.asSequence()
                .filter { (item, _) -> item is GridItem.Content || item is GridItem.CollectionFolder || item is GridItem.SeeAll }
                .map { it.second }
                .take(actualColumnsPerRow).toSet()
            else emptySet()
        }
        val firstGridKey = firstGridRowKeys.firstOrNull()
        val compactCardStyle = remember(posterCardStyle) {
            posterCardStyle.copy(height = posterCardStyle.width, focusedScale = 1f)
        }
        val renderedKeys = remember(leading, trailing, hasContinueWatching, upcomingItems.isNotEmpty()) {
            buildList {
                leading.forEach { add(it.second) }
                if (hasContinueWatching) add("continue_watching")
                if (upcomingItems.isNotEmpty()) add("upcoming_section")
                trailing.forEach { add(it.second) }
            }
        }
        val itemByKey = remember(leading, trailing) {
            (leading + trailing).associate { (item, key) -> key to item }
        }
        val sectionMapping = remember(renderedKeys, itemByKey) {
            buildSectionMapping(renderedKeys, itemByKey)
        }
        val currentRenderedKeys by rememberUpdatedState(renderedKeys)
        BindInitialContinueWatchingEntry(
            isInitial = {
                shouldRequestInitialFocus && lastFocusedGridItemKey.value == null && activeCwRowKey.value == null
            },
            isAvailable = hasContinueWatching && !hasCatalogGrid,
            prepare = {
                if (gridState.layoutInfo.visibleItemsInfo.none { it.key == "continue_watching" }) {
                    gridState.scrollToItem(renderedKeys.indexOf("continue_watching"))
                }
            },
            isVisible = { gridState.layoutInfo.visibleItemsInfo.any { it.key == "continue_watching" } },
            requester = cwRowFocusRequester,
            hasFocus = { contentHasFocus.value && activeCwRowKey.value == "continue_watching" }
        )

        var handledEntryRequest by remember { mutableIntStateOf(0) }
        LaunchedEffect(gridFocusState, renderedKeys, uiState.isLoading, contentEntryRequest, actionHandoff) {
            val explicitEntry = contentEntryRequest != null
            val (oldAction, replacement) = actionHandoff ?: (null to null)
            val replacementKey = replacement?.let { item -> renderedKeys.firstOrNull { itemByKey[it] == item } }
            if (oldAction != null && !contentHasFocus.value) {
                focusedCatalogAction.value = null
                return@LaunchedEffect
            }
            if (explicitEntry) {
                if (contentEntryRequest == handledEntryRequest && replacementKey == null) return@LaunchedEffect
            } else if (restoredSavedGridFocus || !gridFocusState.hasSavedFocus || contentHasFocus.value) {
                return@LaunchedEffect
            }
            val savedKey = if (explicitEntry) lastFocusedGridItemKey.value else gridFocusState.focusedItemKey
            if (!explicitEntry && savedKey == null) return@LaunchedEffect
            val savedWatchRow = savedKey?.substringBefore('|')?.takeIf {
                it == "continue_watching" || it == "upcoming_section"
            }
            val savedGridKey = savedWatchRow ?: savedKey
            val fallbackInCatalog = if (savedGridKey?.startsWith("content|") == true) {
                renderedKeys.withIndex().asSequence().filter { (_, key) ->
                    val item = itemByKey[key] as? GridItem.Content
                    item != null && savedGridKey.startsWith(item.homeGridRowPrefix())
                }.minByOrNull { kotlin.math.abs(it.index - gridFocusState.verticalScrollIndex) }?.value
            } else null
            val targetKey = when {
                replacementKey != null -> replacementKey
                savedGridKey != null && savedGridKey in renderedKeys -> savedGridKey
                uiState.isLoading && savedKey != null -> return@LaunchedEffect
                fallbackInCatalog != null -> fallbackInCatalog
                !explicitEntry && savedGridKey?.startsWith("content|") == true -> return@LaunchedEffect
                else -> firstGridKey ?: renderedKeys.firstOrNull {
                    it == "continue_watching" || it == "upcoming_section" || it == "hero" ||
                        itemByKey[it] is GridItem.SeeAll
                }
            } ?: return@LaunchedEffect
            val watchRow = targetKey
            val watchItems = when (watchRow) {
                "continue_watching" -> continueWatchingItems
                "upcoming_section" -> upcomingItems
                else -> emptyList()
            }
            val watchIndex = watchItems.indexOfFirst { continueWatchingItemKey(it) == savedKey?.substringAfter('|') }
            val hasSavedWatchRow = watchItems.isNotEmpty()
            val targetIndex = renderedKeys.indexOf(targetKey)
            val targetWatchIndex = if (hasSavedWatchRow) watchIndex.coerceAtLeast(0) else -1
            val watchList = if (watchRow == "continue_watching") cwListState else upcomingListState
            val watchRequesters = if (watchRow == "continue_watching") cwFocusRequesters else upcomingFocusRequesters
            val result = HomeBrowseFocusTransfer.transfer(
                isCurrent = {
                    val targetHasFocus = if (hasSavedWatchRow) {
                        activeCwRowKey.value == targetKey
                    } else lastFocusedGridItemKey.value == targetKey
                    if (oldAction != null && replacementKey != null) {
                        !sidebarExpanded && latestActionReplacement?.invoke(oldAction) == replacement &&
                            (targetHasFocus || lastFocusedGridItemKey.value == oldAction.homeGridKey())
                    } else (explicitEntry || !restoredSavedGridFocus) &&
                        !sidebarExpanded && (!contentHasFocus.value || targetHasFocus)
                },
                prepare = {
                    if (gridState.layoutInfo.visibleItemsInfo.none { it.key == targetKey }) {
                        gridState.scrollToItem(targetIndex)
                    }
                    if (targetWatchIndex >= 0 &&
                        watchList.layoutInfo.visibleItemsInfo.none { it.index == targetWatchIndex }
                    ) watchList.scrollToItem(targetWatchIndex)
                },
                awaitFrame = { withFrameNanos { } },
                ready = { gridState.layoutInfo.visibleItemsInfo.any { it.key == targetKey } },
                request = {
                    when {
                        targetWatchIndex >= 0 -> watchRequesters[targetWatchIndex]?.requestFocus() == true
                        targetKey == "hero" -> heroFocusRequester.requestFocus()
                        else -> focusRequesters[targetKey]?.requestFocus() == true
                    }
                },
                hasFocus = {
                    contentHasFocus.value && if (hasSavedWatchRow) {
                        val focusedIndex = if (watchRow == "continue_watching") lastFocusedCwIndex else lastFocusedUpcomingIndex
                        activeCwRowKey.value == watchRow && focusedIndex.intValue == targetWatchIndex
                    } else lastFocusedGridItemKey.value == targetKey
                }
            )
            if (oldAction != null && focusedCatalogAction.value == oldAction) focusedCatalogAction.value = null
            if (contentEntryRequest != null) handledEntryRequest = contentEntryRequest
            else restoredSavedGridFocus = result == HomeBrowseFocusTransfer.Result.FOCUSED || contentHasFocus.value
        }

        fun LazyGridScope.emitGridItems(entries: List<Pair<GridItem, String>>) {
            items(
                items = entries,
                key = { it.second },
                span = { (item, _) ->
                    GridItemSpan(when (item) {
                        is GridItem.Hero, is GridItem.SectionDivider, is GridItem.CollectionHeader -> maxLineSpan
                        else -> 1
                    })
                },
                contentType = { (item, _) ->
                    when (item) {
                        is GridItem.Hero -> "hero"
                        is GridItem.SectionDivider -> "divider"
                        is GridItem.Content -> "content"
                        is GridItem.SeeAll -> "see_all"
                        is GridItem.CollectionHeader -> "collection_header"
                        is GridItem.CollectionFolder -> "collection_folder"
                    }
                }
            ) { (gridItem, itemKey) ->
                CompositionLocalProvider(LocalLandscapePosterMode provides (gridItem !is GridItem.Hero || globalLandscape)) {
                    when (gridItem) {
                        is GridItem.Hero -> {
                            HeroCarousel(
                                items = gridItem.items.asStable(),
                                focusRequester = heroFocusRequester,
                                showImdbRatings = uiState.homeImdbRatingsVisibility.showRatings,
                                mdbListShowOnHero = uiState.mdbListShowOnHero,
                                mdbListRatingOrder = uiState.mdbListRatingOrder,
                                initialActiveIndex = savedHeroIndex.intValue,
                                onItemFocus = {
                                    lastFocusedGridItemKey.value = "hero"
                                    activeCwRowKey.value = null
                                },
                                onActiveItemChanged = { item ->
                                    val idx = gridItem.items.indexOfFirst { it.id == item.id }
                                    if (idx >= 0) savedHeroIndex.intValue = idx
                                },
                                onItemClick = remember(onNavigateToDetail) {
                                    { item ->
                                        onNavigateToDetail(
                                            item.id,
                                            item.apiType,
                                            ""
                                        )
                                    }
                                },
                                fullWidth = gridWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        is GridItem.SectionDivider -> {
                            val strTypeMovie = stringResource(R.string.type_movie)
                            val strTypeSeries = stringResource(R.string.type_series)
                            val typeLabel = when (gridItem.type.lowercase()) {
                                "movie" -> strTypeMovie
                                "series" -> strTypeSeries
                                else -> gridItem.type.replaceFirstChar { it.uppercase() }
                            }
                            val displayName = if (uiState.catalogTypeSuffixEnabled && typeLabel.isNotBlank() &&
                                gridItem.addonId != WATCHED_HISTORY_ADDON_ID) {
                                "${gridItem.catalogName.replaceFirstChar { it.uppercase() }} - $typeLabel"
                            } else {
                                gridItem.catalogName.replaceFirstChar { it.uppercase() }
                            }
                            SectionDivider(
                                catalogName = displayName,
                                compact = hasCatalogGrid
                            )
                        }

                        is GridItem.Content -> {
                            GridContentCard(
                                item = gridItem.item,
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = focusRequesters.getOrPut(itemKey) { /* The remembered map preserves identity. */ @Suppress("RememberInComposition") FocusRequester() },
                                posterCardStyle = compactCardStyle,
                                upFocusRequester = tabFocusRequester.takeIf { itemKey in firstGridRowKeys },
                                imageCrossfade = false,
                                showLabel = uiState.posterLabelsEnabled || gridItem.addonId == WATCHED_HISTORY_ADDON_ID,
                                isWatched = isCatalogItemWatched(gridItem.item),
                                onFocused = remember(itemKey, gridItem.item) {
                                    {
                                        lastFocusedGridItemKey.value = itemKey
                                        activeCwRowKey.value = null
                                        // No rows here, but a card still belongs to one.
                                        onFocusedRowKeyChanged(
                                            catalogRowStableKey(
                                                gridItem.addonId,
                                                gridItem.addonBaseUrl,
                                                gridItem.item.apiType,
                                                gridItem.catalogId
                                            )
                                        )
                                        onItemFocus(gridItem.item)
                                    }
                                },
                                onClick = remember(gridItem.item, gridItem.addonBaseUrl) {
                                    {
                                        onNavigateToDetail(
                                            gridItem.item.id,
                                            gridItem.item.apiType,
                                            gridItem.addonBaseUrl
                                        )
                                    }
                                },
                                onLongPress = remember(gridItem.item, gridItem.addonBaseUrl) {
                                    {
                                        onCatalogItemLongPress(gridItem.item, gridItem.addonBaseUrl)
                                    }
                                }
                            )
                        }

                        is GridItem.SeeAll -> {
                            SeeAllGridCard(
                                posterCardStyle = compactCardStyle,
                                focusRequester = focusRequesters.getOrPut(itemKey) { /* The remembered map preserves identity. */ @Suppress("RememberInComposition") FocusRequester() },
                                onFocused = remember(itemKey) {
                                    {
                                        if (latestActionReplacement != null) {
                                            focusedCatalogAction.value = gridItem
                                            val index = latestGridItems.indexOf(gridItem)
                                            if (index >= 0) focusedCatalogActionIndex = index
                                        }
                                        lastFocusedGridItemKey.value = itemKey
                                        activeCwRowKey.value = null
                                    }
                                },
                                label = catalogActionLabel?.invoke(gridItem) ?: catalogSeeAllLabel,
                                onClick = {
                                    if (onCatalogAction != null) onCatalogAction(gridItem)
                                    else onNavigateToCatalogSeeAll(
                                        gridItem.catalogId,
                                        gridItem.addonId,
                                        gridItem.type
                                    )
                                }
                            )
                        }

                        is GridItem.CollectionHeader -> {
                            SectionDivider(catalogName = gridItem.title, compact = hasCatalogGrid)
                        }

                        is GridItem.CollectionFolder -> {
                            GridCollectionFolderCard(
                                folder = gridItem.folder,
                                collectionTitle = gridItem.collectionTitle,
                                focusGlowEnabled = gridItem.focusGlowEnabled,
                                posterCardStyle = compactCardStyle,
                                focusRequester = focusRequesters.getOrPut(itemKey) { /* The remembered map preserves identity. */ @Suppress("RememberInComposition") FocusRequester() },
                                onFocused = remember(itemKey) { { lastFocusedGridItemKey.value = itemKey; activeCwRowKey.value = null } },
                                onClick = remember(gridItem.collectionId, gridItem.folder.id) {
                                    {
                                        onNavigateToFolderDetail(gridItem.collectionId, gridItem.folder.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        val scope = rememberCoroutineScope()
        var watchJump by remember { mutableStateOf<Job?>(null) }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = gridColumnMinSize),
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged {
                            contentHasFocus.value = it.hasFocus
                        }
                        .focusRequester(contentFocusRequester)
                        .focusRestorer {
                            val cwRow = activeCwRowKey.value
                            val fromCwRow = when (cwRow) {
                                "continue_watching" -> cwRowFocusRequester
                                "upcoming_section" -> upcomingRowFocusRequester
                                else -> null
                            }
                            val lastKey = lastFocusedGridItemKey.value
                            val fromGrid = lastKey?.let { key -> focusRequesters[key] }
                            val fromHero = if (hasHero && !hasCatalogGrid && lastKey == null) heroFocusRequester else null
                            fromCwRow ?: fromGrid ?: fromHero ?: firstGridKey?.let(focusRequesters::get) ?: FocusRequester.Default
                        }
                        .dpadRepeatThrottle(),
                    contentPadding = PaddingValues(
                        start = startPadding,
                        end = endPadding,
                        top = if (hasCatalogGrid) NuvioTheme.spacing.sm else NuvioTheme.spacing.none,
                        bottom = NuvioTheme.spacing.xxl
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(if (hasCatalogGrid) NuvioTheme.spacing.md else NuvioTheme.spacing.lg)
                ) {
                    emitGridItems(leading)

                    // Emit Continue Watching as a dedicated item
                    if (continueWatchingItems.isNotEmpty()) {
                        item(
                            key = "continue_watching",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "continue_watching"
                        ) {
                            LaunchedEffect(cwPendingScrollToStart.intValue) {
                                if (cwPendingScrollToStart.intValue > 0) {
                                    cwListState.scrollToItem(0, 0)
                                    cwRowFocusRequester.let { runCatching { it.requestFocus() } }
                                    cwPendingScrollToStart.intValue = 0
                                }
                            }
                            GridContinueWatchingSection(
                                modifier = Modifier.fillMaxWidth(),
                                fullWidth = gridWidth,
                                items = continueWatchingItems,
                                focusedItemIndex = if (shouldRequestInitialFocus && !hasHero && !hasCatalogGrid && !enterFromHomeTab) 0 else -1,
                                lastFocusedIndex = lastFocusedCwIndex,
                                focusRequesters = cwFocusRequesters,
                                rowFocusRequester = cwRowFocusRequester,
                                listState = cwListState,
                                onItemFocused = { index ->
                                    lastFocusedCwIndex.intValue = index
                                    activeCwRowKey.value = "continue_watching"
                                    lastFocusedGridItemKey.value = "continue_watching|${continueWatchingItemKey(continueWatchingItems[index])}"
                                },
                                onItemClick = onContinueWatchingClick,
                                onStartFromBeginning = onContinueWatchingStartFromBeginning,
                                showManualPlayOption = showContinueWatchingManualPlayOption,
                                onPlayManually = onContinueWatchingPlayManually,
                                onDetailsClick = { item ->
                                    onNavigateToDetail(
                                        when (item) {
                                            is ContinueWatchingItem.InProgress -> item.progress.contentId
                                            is ContinueWatchingItem.NextUp -> item.info.contentId
                                        },
                                        when (item) {
                                            is ContinueWatchingItem.InProgress -> item.progress.contentType
                                            is ContinueWatchingItem.NextUp -> item.info.contentType
                                        },
                                        ""
                                    )
                                },
                                onRemoveItem = { item ->
                                    val contentId = when (item) {
                                        is ContinueWatchingItem.InProgress -> item.progress.contentId
                                        is ContinueWatchingItem.NextUp -> item.info.contentId
                                    }
                                    val season = when (item) {
                                        is ContinueWatchingItem.InProgress -> item.progress.season
                                        is ContinueWatchingItem.NextUp -> item.info.seedSeason
                                    }
                                    val episode = when (item) {
                                        is ContinueWatchingItem.InProgress -> item.progress.episode
                                        is ContinueWatchingItem.NextUp -> item.info.seedEpisode
                                    }
                                    val isNextUp = item is ContinueWatchingItem.NextUp
                                    onRemoveContinueWatching(contentId, season, episode, isNextUp)
                                },
                                blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                                cardStyle = uiState.continueWatchingCardStyle,
                                cornerRadius = posterCardStyle.cornerRadius,
                                posterCardWidth = posterCardStyle.width,
                                posterCardHeight = posterCardStyle.height
                            )
                        }
                    }

                    // Emit Upcoming section if SPLIT_UPCOMING mode has upcoming items
                    if (upcomingItems.isNotEmpty()) {
                        item(
                            key = "upcoming_section",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "upcoming_section"
                        ) {
                            LaunchedEffect(upcomingPendingScrollToStart.intValue) {
                                if (upcomingPendingScrollToStart.intValue > 0) {
                                    upcomingListState.scrollToItem(0, 0)
                                    upcomingRowFocusRequester.let { runCatching { it.requestFocus() } }
                                    upcomingPendingScrollToStart.intValue = 0
                                }
                            }
                            GridContinueWatchingSection(
                                modifier = Modifier.fillMaxWidth(),
                                fullWidth = gridWidth,
                                items = upcomingItems,
                                title = stringResource(R.string.upcoming_section_title),
                                lastFocusedIndex = lastFocusedUpcomingIndex,
                                focusRequesters = upcomingFocusRequesters,
                                rowFocusRequester = upcomingRowFocusRequester,
                                listState = upcomingListState,
                                onItemFocused = { index ->
                                    lastFocusedUpcomingIndex.intValue = index
                                    activeCwRowKey.value = "upcoming_section"
                                    lastFocusedGridItemKey.value = "upcoming_section|${continueWatchingItemKey(upcomingItems[index])}"
                                },
                                onItemClick = onContinueWatchingClick,
                                onStartFromBeginning = onContinueWatchingStartFromBeginning,
                                showManualPlayOption = showContinueWatchingManualPlayOption,
                                onPlayManually = onContinueWatchingPlayManually,
                                onDetailsClick = { item ->
                                    onNavigateToDetail(
                                        when (item) {
                                            is ContinueWatchingItem.InProgress -> item.progress.contentId
                                            is ContinueWatchingItem.NextUp -> item.info.contentId
                                        },
                                        when (item) {
                                            is ContinueWatchingItem.InProgress -> item.progress.contentType
                                            is ContinueWatchingItem.NextUp -> item.info.contentType
                                        },
                                        ""
                                    )
                                },
                                onRemoveItem = { item ->
                                    val contentId = when (item) {
                                        is ContinueWatchingItem.InProgress -> item.progress.contentId
                                        is ContinueWatchingItem.NextUp -> item.info.contentId
                                    }
                                    val season = when (item) {
                                        is ContinueWatchingItem.InProgress -> item.progress.season
                                        is ContinueWatchingItem.NextUp -> item.info.seedSeason
                                    }
                                    val episode = when (item) {
                                        is ContinueWatchingItem.InProgress -> item.progress.episode
                                        is ContinueWatchingItem.NextUp -> item.info.seedEpisode
                                    }
                                    val isNextUp = item is ContinueWatchingItem.NextUp
                                    onRemoveContinueWatching(contentId, season, episode, isNextUp)
                                },
                                blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                                cardStyle = uiState.continueWatchingCardStyle,
                                cornerRadius = posterCardStyle.cornerRadius,
                                posterCardWidth = posterCardStyle.width,
                                posterCardHeight = posterCardStyle.height
                            )
                        }
                    }

                    emitGridItems(trailing)
                }
                GridStickyHeader(gridState, sectionMapping)
            }
            if (hasCatalogGrid) {
                CompactGridHomeSummary(
                    focusedKey = lastFocusedGridItemKey,
                    itemsByKey = itemByKey,
                    firstKey = firstGridKey,
                    onContinueWatching = if (hasContinueWatching) {
                        {
                            if (watchJump?.isActive != true) watchJump = scope.launch {
                                HomeBrowseFocusTransfer.transfer(
                                    isCurrent = {
                                        !sidebarExpanded && "continue_watching" in currentRenderedKeys &&
                                            (!contentHasFocus.value || activeCwRowKey.value == "continue_watching")
                                    },
                                    prepare = {
                                        val index = currentRenderedKeys.indexOf("continue_watching")
                                        if (index >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.key == "continue_watching" }) {
                                            gridState.scrollToItem(index)
                                        }
                                    },
                                    awaitFrame = { withFrameNanos { } },
                                    ready = { gridState.layoutInfo.visibleItemsInfo.any { it.key == "continue_watching" } },
                                    request = { cwRowFocusRequester.requestFocus(FocusDirection.Enter) },
                                    hasFocus = { contentHasFocus.value && activeCwRowKey.value == "continue_watching" }
                                )
                            }
                            Unit
                        }
                    } else null
                )
            }
        }
    }
}

private fun GridItem.Content.homeGridRowPrefix(): String {
    val row = catalogRowStableKey(addonId, addonBaseUrl, item.apiType, catalogId)
    return "content|${row.length}|$row|"
}

@Composable
private fun GridStickyHeader(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    sectionMapping: SectionMapping
) {
    val currentSectionName by remember(gridState, sectionMapping) {
        derivedStateOf {
            gridState.firstVisibleItemIndex.takeIf { it > 0 }?.let(sectionMapping::findSectionForIndex)
        }
    }

    currentSectionName?.let { StickyCategoryHeader(sectionName = it) }
}

@Composable
private fun SectionDivider(
    catalogName: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (compact) NuvioTheme.spacing.sm else NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = catalogName,
            style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).copy(
                textDirection = catalogName.contentTextDirection()
            ),
            color = NuvioTheme.colors.TextPrimary
        )
    }
}

@Composable
private fun StickyCategoryHeader(
    sectionName: String,
    modifier: Modifier = Modifier
) {
    val bgColor = NuvioTheme.colors.Background
    val headerGradient = remember(bgColor) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to bgColor,
                0.7f to bgColor.copy(alpha = 0.95f),
                1.0f to Color.Transparent
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(headerGradient)
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.md)
    ) {
        Text(
            text = sectionName,
            style = MaterialTheme.typography.titleLarge.copy(
                textDirection = sectionName.contentTextDirection()
            ),
            color = NuvioTheme.colors.TextPrimary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeeAllGridCard(
    onClick: () -> Unit,
    posterCardStyle: PosterCardStyle,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    label: String? = null,
    modifier: Modifier = Modifier
) {
    val seeAllCardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val cardDepthStyle = LocalCardDepthStyle.current
    val globalLandscape = LocalLandscapePosterMode.current
    val effectiveCardHeight = if (globalLandscape) {
        posterCardStyle.width / PosterShape.LANDSCAPE.aspectRatio()
    } else {
        posterCardStyle.height
    }
    Column(
        modifier = modifier.then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .then(
                    if (globalLandscape) Modifier.fillMaxWidth().aspectRatio(PosterShape.LANDSCAPE.aspectRatio())
                    else Modifier.width(posterCardStyle.width).height(effectiveCardHeight)
                )
                .onFocusChanged { if (it.isFocused) onFocused() }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            shape = CardDefaults.shape(
                shape = seeAllCardShape
            ),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                    shape = seeAllCardShape
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = posterCardStyle.focusedScale
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(seeAllCardShape)
                    .nuvioCardDepth(
                        shape = seeAllCardShape,
                        surface = CardDepthSurface.POSTERS,
                        style = cardDepthStyle
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = label ?: stringResource(R.string.action_see_all),
                        modifier = Modifier.size(NuvioTheme.spacing.xxl),
                        tint = NuvioTheme.colors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Text(
                        text = label ?: stringResource(R.string.action_see_all),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        // Reserve space for label to match other grid cards
        Spacer(
            modifier = Modifier
                .then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
                .padding(top = NuvioTheme.spacing.sm)
                .height(MaterialTheme.typography.titleMedium.lineHeight.value.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridCollectionFolderCard(
    folder: CollectionFolder,
    collectionTitle: String,
    focusGlowEnabled: Boolean,
    posterCardStyle: PosterCardStyle,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val cardDepthStyle = LocalCardDepthStyle.current
    var isFocused by remember { mutableStateOf(false) }
    val cardGlow = rememberArtworkBackedCardGlow(
        imageUrl = folder.coverImageUrl,
        fallbackSeed = "$collectionTitle:${folder.title}:${folder.coverEmoji.orEmpty()}",
        enabled = focusGlowEnabled
    )
    val folderAspectRatio = when (folder.tileShape) {
        PosterShape.LANDSCAPE -> 16f / 9f
        PosterShape.SQUARE -> 1f
        PosterShape.POSTER -> posterCardStyle.aspectRatio
    }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(folderAspectRatio)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale),
        glow = cardGlow
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
                .nuvioCardDepth(
                    shape = cardShape,
                    surface = CardDepthSurface.POSTERS,
                    style = cardDepthStyle
                )
        ) {
            val activeImageUrl = collectionFolderCardImageUrl(folder, isFocused)
            if (!activeImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = activeImageUrl,
                    contentDescription = folder.title,
                    modifier = Modifier.fillMaxSize().clip(cardShape),
                    contentScale = ContentScale.FillBounds
                )
            } else if (!folder.coverEmoji.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = folder.coverEmoji, fontSize = 36.sp)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = folder.title.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }

            // GIF overlay: show on top of cover image or emoji, visible only once loaded
            val focusGifUrl = if (isFocused && folder.focusGifEnabled) folder.focusGifUrl else null
            if (!focusGifUrl.isNullOrBlank()) {
                var gifLoaded by remember(focusGifUrl) { mutableStateOf(false) }
                val gifAlpha by animateFloatAsState(
                    targetValue = if (gifLoaded) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "gifFadeIn"
                )
                AsyncImage(
                    model = focusGifUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .graphicsLayer { alpha = gifAlpha },
                    contentScale = ContentScale.FillBounds,
                    onSuccess = { gifLoaded = true }
                )
            }

            if (!folder.hideTitle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        .padding(NuvioTheme.spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = folder.title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            textDirection = folder.title.contentTextDirection()
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// Section mapping utilities

private class SectionMapping(private val headings: Map<Int, String?>) {
    private val starts = headings.keys.sorted()

    fun findSectionForIndex(index: Int): String? {
        val found = starts.binarySearch(index)
        val position = if (found >= 0) found else -found - 2
        return starts.getOrNull(position)?.let(headings::get)
    }
}

private fun buildSectionMapping(keys: List<String>, items: Map<String, GridItem>): SectionMapping =
    SectionMapping(buildMap {
        keys.forEachIndexed { index, key ->
            when (val item = items[key]) {
                is GridItem.SectionDivider -> put(index, item.catalogName)
                is GridItem.Hero, is GridItem.CollectionHeader, null -> put(index, null)
                else -> Unit
            }
        }
    })
