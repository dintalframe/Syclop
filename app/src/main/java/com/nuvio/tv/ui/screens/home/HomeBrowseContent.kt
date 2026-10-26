@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalOpenSidebar
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.SyclopBrandAnimation
import com.nuvio.tv.ui.components.SyclopBrandState
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

internal val LocalHomeBrowseTabFocusRequester = compositionLocalOf<FocusRequester?> { null }

internal class HomeBrowseEntry { var enter: () -> Unit = {}; var pending = false }

/** The sidebar enters the selected tab; Down enters content, Up never falls through to a tile. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeBrowseScaffold(section: HomeBrowseSection, onSelect: (HomeBrowseSection) -> Unit,
    content: @Composable (FocusRequester, HomeBrowseEntry) -> Unit) {
    val sidebarEntry = LocalContentFocusRequester.current
    val openSidebar = LocalOpenSidebar.current
    val tabs = remember { HomeBrowseSection.entries.associateWith { FocusRequester() } }
    // One owner survives tab changes. Late Home/layout requests cannot leave a header
    // that has actually received focus; Down explicitly grants entry before requesting it.
    val headerOwnsFocus = remember { mutableStateOf(false) }
    val bodyEntry = remember(section) { FocusRequester() }
    val entry = remember(section) { HomeBrowseEntry().apply { enter = { runCatching { bodyEntry.requestFocus() }; Unit } } }
    val saved = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeBrowseSection.entries.forEachIndexed { index, tab ->
                Button(onClick = { onSelect(tab) }, modifier = Modifier
                    .focusRequester(tabs.getValue(tab))
                    .onFocusChanged {
                        if (it.isFocused) {
                            headerOwnsFocus.value = true
                            if (section != tab) onSelect(tab)
                        }
                    }
                    .then(if (section == tab && sidebarEntry != FocusRequester.Default) Modifier.focusRequester(sidebarEntry) else Modifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionUp -> true
                            Key.DirectionDown -> { headerOwnsFocus.value = false; entry.enter(); true }
                            Key.DirectionLeft -> {
                                if (index > 0) tabs.getValue(HomeBrowseSection.entries[index - 1]).requestFocus()
                                else openSidebar?.invoke()
                                // Home is the left boundary: never let geometric search land
                                // in a scrolled body row instead of opening root navigation.
                                true
                            }
                            Key.DirectionRight -> { if (index < HomeBrowseSection.entries.lastIndex) tabs.getValue(HomeBrowseSection.entries[index + 1]).requestFocus(); true }
                            else -> false
                        }
                    }, colors = ButtonDefaults.colors(
                    containerColor = if (section == tab) Color(0xFF303033) else Color(0xFF101012),
                    contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
                    Text(tab.label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()
            .focusProperties { onEnter = { if (headerOwnsFocus.value) cancelFocusChange() } }
            .focusGroup()) {
            saved.SaveableStateProvider(section.name) {
                CompositionLocalProvider(
                    LocalContentFocusRequester provides bodyEntry,
                    LocalHomeBrowseTabFocusRequester provides tabs.getValue(section)
                ) { content(tabs.getValue(section), entry) }
            }
        }
    }
}

/** Separate category body: no mixed hero, folders or Continue Watching/See All destination. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeBrowseContent(section: HomeBrowseSection, viewModel: HomeBrowseViewModel,
    tabFocus: FocusRequester, entry: HomeBrowseEntry, posterStyle: PosterCardStyle,
    onDetail: (String, String, String) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onLongPress: (MetaPreview, String) -> Unit) {
    val observed by viewModel.state.collectAsStateWithLifecycle()
    val state = observed.takeIf { it.section == section && viewModel.isCurrent() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowEntries = remember { mutableMapOf<String, FocusRequester>() }
    val horizontalStates = remember { mutableMapOf<String, LazyListState>() }
    val footer = remember { FocusRequester() }
    var focusedIndices by rememberSaveable { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var restoreRow by rememberSaveable { mutableStateOf<String?>(null) }
    var returningFromDetail by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var activeCard by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var activeAction by remember { mutableStateOf<String?>(null) }
    val rows = state?.rows.orEmpty().filter { row -> row.failed || row.data?.let { it.items.isNotEmpty() || it.hasMore } == true }
    rows.forEach { row ->
        rowEntries.getOrPut(row.key) { FocusRequester() }
        horizontalStates.getOrPut(row.key) { LazyListState() }
    }
    val latestRows by rememberUpdatedState(rows)
    val latestState by rememberUpdatedState(state)
    fun move(index: Int) {
        if (moving) return
        if (index < 0) { tabFocus.requestFocus(); return }
        val row = rows.getOrNull(index)
        val emptyAction = rows.isEmpty() && state != null && !state.loading && state.rows.none { it.loading }
        val target = row?.let { rowEntries[it.key] }
            ?: footer.takeIf { state?.moreCatalogs == true || emptyAction } ?: return
        val cardIndex = row?.let { candidate -> candidate.data?.items?.takeIf { it.isNotEmpty() }?.let {
            HomeBrowseFocusTransfer.cardIndex(focusedIndices[candidate.key], it.size)
        } }
        val horizontal = row?.let { horizontalStates.getValue(it.key) }
        val origin = state?.originRevision
        moving = true
        scope.launch {
            try {
                val result = HomeBrowseFocusTransfer.transfer(
                    isCurrent = {
                        viewModel.isCurrent() && latestState?.originRevision == origin &&
                            (row == null || latestRows.any { it.key == row.key })
                    },
                    prepare = {
                        if (rows.isNotEmpty()) list.scrollToItem(index.coerceAtMost(rows.size))
                        // The leaf requester cannot attach until both lazy axes have composed it.
                        if (cardIndex != null) horizontal!!.scrollToItem(cardIndex)
                    },
                    awaitFrame = { withFrameNanos { } },
                    ready = {
                        (rows.isEmpty() || list.layoutInfo.visibleItemsInfo.any { it.index == index }) &&
                            (cardIndex == null || horizontal!!.layoutInfo.visibleItemsInfo.any { it.index == cardIndex })
                    },
                    request = { target.requestFocus(FocusDirection.Enter) },
                    hasFocus = {
                        if (cardIndex != null) activeCard == (row!!.key to cardIndex)
                        else activeAction == (row?.key ?: FOOTER_FOCUS_KEY)
                    }
                )
                // Fixed stages only: never catalog names, media identifiers, URLs or exceptions.
                Log.d("HomeBrowseFocus", "transfer_result=${result.name}")
            } finally { moving = false }
        }
    }
    val enterLatest by rememberUpdatedState<() -> Unit> {
        if (rows.isNotEmpty() || state != null && !state.loading && state.rows.none { it.loading }) {
            entry.pending = false
            move(0)
        } else entry.pending = true
    }
    DisposableEffect(entry) {
        entry.enter = { enterLatest() }
        onDispose { entry.enter = {} }
    }
    LaunchedEffect(entry, rows.map { it.key }, state?.loading, state?.rows?.any { it.loading }) {
        // A Down pressed during loading also enters a terminal empty-state action.
        if (entry.pending && state != null && (!state.loading && state.rows.none { it.loading } || rows.isNotEmpty())) {
            enterLatest()
        }
    }
    LaunchedEffect(state?.originRevision, rows.map { it.key }) {
        if (returningFromDetail && rows.isNotEmpty()) {
            returningFromDetail = false
            move(rows.indexOfFirst { it.key == restoreRow }.coerceAtLeast(0))
        }
    }
    if (state == null || (rows.isEmpty() && (state.loading || state.rows.any { it.loading }))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SyclopBrandAnimation(SyclopBrandState.Loading) }
        return
    }
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state.failed) "Catalog settings could not be read. Retry after checking this profile."
            else if (state.moreCatalogs) "No matching ${section.label} in these catalogs. More enabled catalogs are available."
            else if (section == HomeBrowseSection.KIDS)
                "No titles with a known rating suitable through age 7 are available. Missing, unknown and advisory ratings are excluded."
            else if (section == HomeBrowseSection.DOCUMENTARY)
                "No explicitly tagged documentaries are available. Enable a catalog with Documentary genres in Add-ons."
            else if (section == HomeBrowseSection.ANIME)
                "No anime catalogs are available. Enable an anime catalog in Add-ons or configure MyAnimeList in Tracking settings."
            else "No ${section.label} catalogs are available. Enable a matching catalog in Add-ons.",
                style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Spacer(Modifier.height(18.dp))
            Button(onClick = { if (state.moreCatalogs) viewModel.loadMoreCatalogs() else viewModel.reload() },
                modifier = Modifier.focusRequester(footer).onFocusChanged {
                    if (it.isFocused) activeAction = FOOTER_FOCUS_KEY
                    else if (activeAction == FOOTER_FOCUS_KEY) activeAction = null
                }.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { tabFocus.requestFocus(); true } else false
            }) { Text(if (state.moreCatalogs) "More ${section.label} catalogs" else "Retry") }
        }
        return
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().focusGroup(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val navigation = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { move(index - 1); true }
                    Key.DirectionDown -> { move(index + 1); true }
                    else -> false
                }
            }
            val data = row.data
            if (data != null && data.items.isNotEmpty()) {
                CatalogRowSection(catalogRow = data, onItemClick = { id, type, base ->
                    if (viewModel.isCurrent() && data.items.any { it.id == id && it.apiType == type && HomeBrowseRules.matches(section, it, data) }) {
                        restoreRow = row.key; returningFromDetail = true; onDetail(id, type, base)
                    }
                }, onSeeAll = { viewModel.loadMore(row.key) }, showSeeAll = !row.loading && (data.hasMore || row.failed),
                    seeAllLabel = if (row.failed) "Retry" else "More ${section.label}",
                    posterCardStyle = posterStyle, showCatalogTypeSuffix = false,
                    enableRowFocusRestorer = false, // Parent owns exact per-row entry and both lazy-axis positions.
                    modifier = navigation.onFocusChanged {
                        if (!it.hasFocus && activeCard?.first == row.key) activeCard = null
                    }, entryFocusRequester = rowEntries.getValue(row.key),
                    entryFocusItemIndex = HomeBrowseFocusTransfer.cardIndex(focusedIndices[row.key], data.items.size),
                    listState = horizontalStates.getValue(row.key),
                    restorerFocusedIndex = focusedIndices[row.key] ?: 0,
                    onItemFocused = { focusedIndices = focusedIndices + (row.key to it); activeCard = row.key to it },
                    isItemWatched = isWatched,
                    onItemLongPress = { item, base -> if (viewModel.isCurrent()) onLongPress(item, base) })
            } else {
                Column(navigation) {
                    Text(row.title, style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
                    Spacer(Modifier.height(12.dp))
                    run {
                        Text(if (row.loading) "Loading this catalog…" else if (row.failed) "This catalog could not be loaded." else "No matching ${section.label} on this page.",
                            style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { viewModel.loadMore(row.key) }, modifier = Modifier.focusRequester(rowEntries.getValue(row.key)).onFocusChanged {
                                if (it.isFocused) activeAction = row.key
                                else if (activeAction == row.key) activeAction = null
                            },
                            enabled = true) { Text(if (row.loading) "Loading…" else if (row.failed) "Retry" else "More ${section.label}") }
                    }
                }
            }
        }
        if (state.moreCatalogs) item(key = "more-catalogs") {
            Button(onClick = viewModel::loadMoreCatalogs, modifier = Modifier.focusRequester(footer).onFocusChanged {
                    if (it.isFocused) activeAction = FOOTER_FOCUS_KEY
                    else if (activeAction == FOOTER_FOCUS_KEY) activeAction = null
                }.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { move(rows.lastIndex); true } else false
            }) { Text("More ${section.label} catalogs") }
        }
    }
}

private const val FOOTER_FOCUS_KEY = "home-browse-footer"
