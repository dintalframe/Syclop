package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class HomeBrowseRow(val key: String, val title: String, val data: CatalogRow? = null,
    val loading: Boolean = true, val failed: Boolean = false)
internal data class HomeBrowseState(val section: HomeBrowseSection = HomeBrowseSection.HOME,
    val rows: List<HomeBrowseRow> = emptyList(), val loading: Boolean = true,
    val moreCatalogs: Boolean = false, val originRevision: Long = -1L, val failed: Boolean = false,
    val requestRevision: Long = 0L)

/** Read-only category browsing. It never edits Home catalog order/visibility, Library or accounts. */
@HiltViewModel
class HomeBrowseViewModel @Inject constructor(
    private val addons: AddonRepository,
    private val catalogs: CatalogRepository,
    private val profiles: ProfileManager,
    private val preferences: com.nuvio.tv.data.local.AddonPreferences
) : ViewModel() {
    private data class Origin(val profile: Int, val selection: Long, val generation: Long, val section: HomeBrowseSection)
    private data class Cached(val fingerprint: List<String>, val state: HomeBrowseState)
    private data class RequestPlan(val profile: Int, val selection: Long,
        val requests: List<HomeBrowseRequest>, val fingerprint: List<String>, val failed: Boolean = false)
    private val generation = AtomicLong()
    private var section = HomeBrowseSection.HOME
    private var origin: Origin? = null
    private var observer: Job? = null
    private val work = linkedMapOf<String, Job>()
    private val slots = Semaphore(2)
    private val cache = linkedMapOf<HomeBrowseSection, Cached>()
    private data class FocusSnapshot(val revision: Long, val state: HomeScreenFocusState, val returning: Boolean = false)
    private val focusSnapshots = mutableMapOf<HomeBrowseSection, FocusSnapshot>()

    internal fun focusState(section: HomeBrowseSection, revision: Long): HomeScreenFocusState =
        focusSnapshots[section]?.takeIf { it.revision == revision && revision == selectionRevision }?.state
            ?: HomeScreenFocusState()

    internal fun saveFocusState(section: HomeBrowseSection, revision: Long, state: HomeScreenFocusState) {
        if (revision < 0 || revision != selectionRevision) return
        val returning = focusSnapshots[section]?.takeIf { it.revision == revision }?.returning ?: false
        focusSnapshots[section] = FocusSnapshot(revision, state, returning)
    }

    internal fun returnToCategory(section: HomeBrowseSection, revision: Long) {
        if (revision < 0 || revision != selectionRevision) return
        val previous = focusSnapshots[section]?.takeIf { it.revision == revision }
        focusSnapshots[section] = FocusSnapshot(revision, previous?.state ?: HomeScreenFocusState(), true)
    }

    internal fun consumeCategoryReturn(section: HomeBrowseSection, revision: Long): Boolean {
        val previous = focusSnapshots[section]?.takeIf { it.revision == revision && revision == selectionRevision } ?: return false
        focusSnapshots[section] = previous.copy(returning = false)
        return previous.returning
    }

    /** Modern rows expose a catalog identity, never guess when two enabled sources share it. */
    internal fun loadMoreCatalog(catalogId: String, addonId: String, apiType: String) {
        val matching = _state.value.rows.filter { row -> row.data?.let {
            it.catalogId == catalogId && it.addonId == addonId && it.apiType == apiType
        } == true }
        matching.singleOrNull()?.let { loadMore(it.key) }
    }

    internal fun retryFailedCatalogs() {
        if (!isCurrent()) return
        val failed = _state.value.rows.filter { it.failed }
        if (failed.isEmpty()) reload() else failed.forEach { loadMore(it.key) }
    }

    private var fingerprint = emptyList<String>()
    private var requestRevision = 0L
    private var requests = emptyList<HomeBrowseRequest>()
    private val _state = MutableStateFlow(HomeBrowseState())
    internal val state: StateFlow<HomeBrowseState> = _state.asStateFlow()
    internal val selectionRevision: Long get() = profiles.activeProfileSelectionRevision
    internal fun isCurrent() = origin?.let(::current) == true
    private fun current(value: Origin) = origin == value && generation.get() == value.generation &&
        section == value.section && profiles.activeProfileId.value == value.profile &&
        profiles.activeProfileSelectionRevision == value.selection

    @OptIn(ExperimentalCoroutinesApi::class)
    fun select(selected: HomeBrowseSection) {
        if (section == selected && (selected == HomeBrowseSection.HOME || observer?.isActive == true && origin?.let(::current) == true)) return
        observer?.cancel(); work.values.forEach { it.cancel() }; work.clear()
        generation.incrementAndGet(); origin = null; section = selected
        if (selected == HomeBrowseSection.HOME) return
        _state.value = HomeBrowseState(section = selected)
        observer = viewModelScope.launch {
            combine(profiles.activeProfileId, addons.getInstalledAddons()) { profile, installed -> profile to installed }
                // collectLatest cancels its previous block before invoking the next one. Prepare
                // and compare relevant requests upstream so an unrelated addon emission cannot
                // cancel an initial batch, clear partial rows and remount the focused renderer.
                .mapLatest { (profile, installed) -> preparePlan(profile, installed, selected) }
                .filterNotNull()
                .distinctUntilChangedBy { it.fingerprint }
                .catch {
                    val captured = Origin(profiles.activeProfileId.value, profiles.activeProfileSelectionRevision,
                        generation.incrementAndGet(), selected)
                    origin = captured
                    if (current(captured)) _state.value = HomeBrowseState(selected, loading = false,
                        originRevision = captured.selection, failed = true)
                }
                .collectLatest { plan ->
                    work.values.forEach { it.cancel() }; work.clear()
                    val captured = Origin(plan.profile, plan.selection, generation.incrementAndGet(), selected)
                    origin = captured
                    if (!current(captured)) return@collectLatest
                    if (plan.failed) {
                        _state.value = HomeBrowseState(selected, loading = false,
                            originRevision = captured.selection, failed = true)
                        return@collectLatest
                    }
                    requests = plan.requests
                    if (fingerprint != plan.fingerprint) requestRevision++
                    fingerprint = plan.fingerprint
                    val previous = cache[selected]?.takeIf { it.fingerprint == fingerprint }
                    if (previous != null) {
                        _state.value = previous.state.copy(originRevision = captured.selection, requestRevision = requestRevision)
                    } else {
                        _state.value = HomeBrowseState(selected, loading = false,
                            moreCatalogs = requests.isNotEmpty(), originRevision = captured.selection,
                            requestRevision = requestRevision)
                        loadCatalogBatch(captured)
                    }
                }
        }
    }

    private suspend fun preparePlan(profile: Int, installed: List<Addon>, selected: HomeBrowseSection): RequestPlan? {
        val selection = profiles.activeProfileSelectionRevision
        fun selectedOrigin() = profiles.activeProfileId.value == profile &&
            profiles.activeProfileSelectionRevision == selection && section == selected
        if (!selectedOrigin()) return null
        val effectiveProfile = if (profiles.activeProfile?.usesPrimaryAddons == true) 1 else profile
        val enabledUrls = try { preferences.enabledUrlsForProfile(effectiveProfile) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return if (selectedOrigin()) RequestPlan(profile, selection, emptyList(),
                listOf(profile.toString(), selection.toString(), selected.name, "unavailable"), failed = true) else null
        }
        if (!selectedOrigin()) return null
        val planned = installed.filter { HomeBrowseRules.allowed(it, enabledUrls) }.flatMap { addon -> addon.catalogs.mapNotNull {
            HomeBrowseRules.request(selected, addon, it)
        } }.distinctBy { it.key }.sortedBy { request ->
            if (selected == HomeBrowseSection.KIDS &&
                request.addon.id == com.nuvio.tv.data.catalog.RatedKidsCatalog.ADDON_ID &&
                com.nuvio.tv.data.catalog.RatedKidsCatalog.isNativeAddon(request.addon.baseUrl)) 0 else 1
        }.take(MAX_CATALOGS)
        val identity = listOf(profile.toString(), selection.toString(), selected.name) + planned.map { request ->
            // Include real catalog/query semantics; a same-version descriptor change must still
            // invalidate the request. Hash private source identity rather than retaining its text.
            java.security.MessageDigest.getInstance("SHA-256").digest(listOf(request.key, request.addon.version,
                request.addon.configVersion, request.addon.timestamp, request.addon.displayName,
                request.catalog, request.extraArgs.toSortedMap()).joinToString("\u0000").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return RequestPlan(profile, selection, planned, identity)
    }

    fun reload() {
        val selected = section
        cache.remove(selected)
        select(HomeBrowseSection.HOME)
        select(selected)
    }

    fun loadMoreCatalogs() {
        val captured = origin?.takeIf(::current) ?: return
        if (work["catalog-batch"]?.isActive == true) return
        work["catalog-batch"] = viewModelScope.launch { loadCatalogBatch(captured) }
    }

    private suspend fun loadCatalogBatch(captured: Origin) = coroutineScope {
        if (!current(captured)) return@coroutineScope
        val seen = _state.value.rows.mapTo(hashSetOf()) { it.key }
        val batch = requests.filterNot { it.key in seen }.take(BATCH_SIZE)
        publish(captured) { it.copy(rows = it.rows + batch.map { request ->
            HomeBrowseRow(request.key, request.catalog.name)
        }, loading = false, moreCatalogs = requests.any { request -> request.key !in seen && request !in batch }) }
        batch.map { request -> async { fetch(captured, request, 0) } }.awaitAll()
    }

    fun loadMore(rowKey: String) {
        val captured = origin?.takeIf(::current) ?: return
        val request = requests.firstOrNull { it.key == rowKey } ?: return
        val row = _state.value.rows.firstOrNull { it.key == rowKey } ?: return
        if (row.loading || work[rowKey]?.isActive == true) return
        val data = row.data
        val skip = if (row.failed) data?.nextSkip ?: 0 else data?.nextSkip?.takeIf { data.hasMore } ?: return
        work[rowKey] = viewModelScope.launch { fetch(captured, request, skip) }
    }

    private suspend fun fetch(captured: Origin, request: HomeBrowseRequest, skip: Int) = slots.withPermit {
        if (!current(captured)) return@withPermit
        publish(captured) { state -> state.copy(rows = state.rows.map { if (it.key == request.key) it.copy(loading = true, failed = false) else it }) }
        try {
            val result = withTimeout(20_000L) {
                catalogs.getCatalog(request.addon.baseUrl, request.addon.id, request.addon.displayName,
                    request.catalog.id, request.catalog.name, request.catalog.apiType, skip,
                    request.catalog.skipStep(), request.extraArgs, request.catalog.supportsExtra("skip"))
                    .first { it !is NetworkResult.Loading }
            }
            if (!current(captured)) return@withPermit
            if (result !is NetworkResult.Success) throw BrowseUnavailable()
            val page = HomeBrowseRules.scoped(captured.section, result.data.copy(extraArgs = request.extraArgs))
            publish(captured) { state -> state.copy(rows = state.rows.map { row ->
                if (row.key != request.key) row else {
                    val old = row.data?.items.orEmpty().takeIf { skip > 0 }.orEmpty()
                    val items = (old + page.items).distinctBy { "${it.apiType}:${it.id}" }.take(MAX_ITEMS_PER_ROW)
                    row.copy(data = page.copy(items = items,
                        hasMore = page.hasMore && page.supportsSkip && page.nextSkip > skip && items.size < MAX_ITEMS_PER_ROW),
                        loading = false, failed = false)
                }
            }) }
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            fail(captured, request.key)
        } catch (_: Exception) { fail(captured, request.key) }
    }

    private class BrowseUnavailable : Exception()
    private fun fail(captured: Origin, key: String) = publish(captured) { state ->
        state.copy(rows = state.rows.map { if (it.key == key) it.copy(loading = false, failed = true) else it })
    }
    private fun publish(captured: Origin, change: (HomeBrowseState) -> HomeBrowseState) {
        if (!current(captured)) return
        _state.update { state -> if (current(captured)) change(state) else state }
        if (current(captured) && _state.value.rows.none { it.loading }) cache[captured.section] = Cached(fingerprint, _state.value)
    }
    override fun onCleared() { generation.incrementAndGet(); observer?.cancel(); work.values.forEach { it.cancel() }; super.onCleared() }
    companion object { private const val BATCH_SIZE = 4; private const val MAX_CATALOGS = 24; private const val MAX_ITEMS_PER_ROW = 300 }
}
