package com.nuvio.tv.data.mal

import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.domain.model.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@Singleton
class MalTrackingProvider @Inject constructor(auth: MalAuthRepository) : TrackingProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val descriptor = TrackingProviderDescriptor(TrackingProviderId.MAL, "MyAnimeList", setOf(
        TrackingCapability.AUTHENTICATION, TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE))
    override val isAuthenticated = auth.state.map { it.connected }
        .stateIn(scope, SharingStarted.Eagerly, auth.state.value.connected)
    // Completed-episode writes already use MalPlaybackSync. MAL has no timestamp resume provider.
}

object MalLibraryMapping {
    const val STATUS_PREFIX = "mal:status:"
    fun key(status: MalListStatus) = "$STATUS_PREFIX${status.wireValue}"
    fun tabs() = MalListStatus.entries.map { status -> LibraryListTab(key = key(status), title = status.label,
        type = LibraryListTab.Type.STATUS, trackingProviderId = TrackingProviderId.MAL.storageId,
        selectionGroup = "mal:status", supportedContentTypes = setOf("movie", "series", "anime")) }

    fun entry(anime: MalAnime, rank: Int): LibraryEntry = LibraryEntry(
        id = "mal:${anime.id}", type = if (anime.mediaType == "movie") "movie" else "series",
        name = anime.title, poster = anime.pictureUrl, background = null, logo = null,
        description = anime.synopsis, releaseInfo = MalIdentity.year(anime.startDate)?.toString(),
        imdbRating = null, genres = anime.genres, addonBaseUrl = MalCatalog.BASE_URL,
        listKeys = setOf(key(anime.status)), listRanks = mapOf(key(anime.status) to rank),
        listedAt = anime.listUpdatedAtMs, mediaCategory = "anime",
        trackingProviderId = TrackingProviderId.MAL.storageId, trackingProviderItemId = "mal:${anime.id}",
        trackingSourceUrl = "https://myanimelist.net/anime/${anime.id}"
    )
}

@Singleton
class MalTrackingLibraryProvider @Inject constructor(
    private val auth: MalAuthRepository,
    private val repository: MalRepository,
    tracking: MalTrackingProvider
) : TrackingLibraryProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val providerId = TrackingProviderId.MAL
    override val isAuthenticated = tracking.isAuthenticated
    override val isRefreshing = combine(auth.state, repository.state) { account, library ->
        account.connected && account.scope == library.scope && library.loading
    }
    override val items = combine(auth.state, repository.state) { account, library ->
        if (!account.connected || account.scope != library.scope) emptyList() else
            library.items.filter { it.onList }.mapIndexed { rank, item -> MalLibraryMapping.entry(item, rank) }
    }
    override val tabs = auth.state.map { if (it.connected) MalLibraryMapping.tabs() else emptyList() }

    init {
        scope.launch {
            auth.state.map { it.scope to it.connected }.distinctUntilChanged().collect { (_, connected) ->
                if (connected) repository.refresh()
            }
        }
    }

    override fun recognizesListKey(key: String) = MalListStatus.entries.any { MalLibraryMapping.key(it) == key }

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
        combine(auth.state, repository.state) { account, library ->
            if (!account.connected || account.scope != library.scope) emptySet() else library.items
                .firstOrNull { it.id == MalIdentity.id(itemId) && it.onList }
                ?.let { setOf(MalLibraryMapping.key(it.status)) }.orEmpty()
        }

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> {
        val hadMembership = MalListStatus.entries.any { currentMembership[MalLibraryMapping.key(it)] == true }
        return currentMembership.toMutableMap().apply {
            MalListStatus.entries.forEach { put(MalLibraryMapping.key(it), false) }
            if (!hadMembership) put(MalLibraryMapping.key(MalListStatus.PLAN_TO_WATCH), true)
        }
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        val expected = auth.state.value.scope
        val id = MalIdentity.id(item.itemId) ?: return ListMembershipSnapshot()
        val anime = repository.detail(id, expected)
        return ListMembershipSnapshot(MalListStatus.entries.associate { status ->
            MalLibraryMapping.key(status) to (anime.onList && anime.status == status)
        })
    }

    override suspend fun membershipRemovalConfirmation(item: LibraryEntryInput,
        changes: ListMembershipChanges): TrackingMembershipRemovalConfirmation? {
        if (changes.desiredMembership.any { recognizesListKey(it.key) && it.value }) return null
        val expected = auth.state.value.scope
        val id = MalIdentity.id(item.itemId) ?: return null
        if (!repository.detail(id, expected).onList) return null
        return TrackingMembershipRemovalConfirmation(providerId,
            setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY, TrackingMembershipRemovalImpact.RATING))
    }

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean) {
        val expected = auth.state.value.scope
        val id = requireNotNull(MalIdentity.id(item.itemId)) { "MyAnimeList list edits require an exact MAL title ID. No title match was guessed." }
        val selected = MalListStatus.entries.filter { changes.desiredMembership[MalLibraryMapping.key(it)] == true }
        require(selected.size <= 1) { "Choose one MyAnimeList status." }
        val current = repository.detail(id, expected)
        if (selected.isEmpty()) {
            if (current.onList) {
                require(destructiveRemovalConfirmed) { "Confirm removal of this anime and its MyAnimeList progress/rating first." }
                repository.remove(id, expected)
            }
        } else if (!current.onList || current.status != selected.single()) {
            // PATCH status alone. Do not reset watched count, score, rewatch flags, tags or comments.
            repository.update(current, MalListEdit(status = selected.single()), expected)
        }
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = repository.refresh()
}
