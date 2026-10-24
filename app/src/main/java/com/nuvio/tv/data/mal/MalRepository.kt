package com.nuvio.tv.data.mal

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Event-driven list reads/edits. No periodic refresh, cloud account, or third-party ID guesses. */
@Singleton
class MalRepository @Inject constructor(
    private val auth: MalAuthRepository,
    private val api: MalApiClient
) {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(MalLibraryState(scope = auth.state.value.scope))
    val state = mutableState.asStateFlow()
    private val mutableCatalogRevision = MutableStateFlow(0L)
    val catalogRevision = mutableCatalogRevision.asStateFlow()

    init {
        worker.launch {
            auth.state.map { it.scope }.distinctUntilChanged().collect { current ->
                mutableState.value = MalLibraryState(scope = current)
            }
        }
    }

    suspend fun refresh() {
        val expected = auth.state.value.scope
        if (!auth.state.value.connected) return
        mutableState.value = state.value.copy(scope = expected, loading = true, message = null)
        try {
            val library = auth.authorized(expected) { api.library(it) }
            if (auth.store.isCurrent(expected)) {
                mutableState.value = MalLibraryState(scope = expected, items = library.items, truncated = library.truncated,
                    message = if (library.truncated) "Showing the first 2,000 MyAnimeList entries." else "MyAnimeList list updated.")
                mutableCatalogRevision.value += 1
            }
        } catch (_: Exception) {
            if (auth.store.isCurrent(expected)) mutableState.value = state.value.copy(loading = false,
                message = "Could not load MyAnimeList. Your local playback history is unchanged.")
        }
    }

    suspend fun search(query: String): List<MalAnime> {
        val expected = auth.state.value.scope
        val result = auth.authorized(expected) { api.search(query, it) }
        require(auth.store.isCurrent(expected)) { "The active profile changed." }
        return result
    }

    suspend fun detail(id: Long, expected: MalScope = auth.state.value.scope): MalAnime {
        val item = auth.authorized(expected) { api.detail(id, it) }
        require(auth.store.isCurrent(expected)) { "The active profile changed." }
        return item
    }

    suspend fun update(item: MalAnime, edit: MalListEdit, expected: MalScope = auth.state.value.scope) = mutations.withLock {
        require(auth.store.isCurrent(expected)) { "The active profile changed." }
        require(edit.watched == null || edit.watched >= 0 && (item.totalEpisodes <= 0 || edit.watched <= item.totalEpisodes)) {
            "The watched count is outside this anime's episode range."
        }
        auth.authorized(expected) { api.edit(item.id, edit, it) }
        val refreshed = auth.authorized(expected) { api.detail(item.id, it) }
        if (auth.store.isCurrent(expected)) replace(refreshed, "MyAnimeList progress saved.")
    }

    suspend fun remove(id: Long, expected: MalScope = auth.state.value.scope) = mutations.withLock {
        require(auth.store.isCurrent(expected)) { "The active profile changed." }
        auth.authorized(expected) { api.remove(id, it) }
        if (auth.store.isCurrent(expected)) {
            mutableState.value = state.value.copy(items = state.value.items.filterNot { it.id == id },
                message = "Anime removed from MyAnimeList.")
            mutableCatalogRevision.value += 1
        }
    }

    suspend fun syncCompletedEpisode(expected: MalScope, id: Long, progress: Double, season: Int?, episode: Int?) = mutations.withLock {
        if (!auth.store.isCurrent(expected) || !auth.state.value.connected || !auth.state.value.automaticProgress) return@withLock
        try {
            val current = auth.authorized(expected) { api.detail(id, it) }
            val completed = MalRules.completedEpisode(progress, season, episode,
                isMovie = current.mediaType == "movie", total = current.totalEpisodes) ?: return@withLock
            val update = MalRules.progressUpdate(current, completed) ?: return@withLock
            if (!auth.store.isCurrent(expected) || !auth.state.value.automaticProgress) return@withLock
            auth.authorized(expected) { api.update(id, update.first, update.second, it) }
            if (auth.store.isCurrent(expected)) replace(current.copy(status = update.first,
                watchedEpisodes = update.second, onList = true), "Completed episode synced to MyAnimeList.")
        } catch (_: Exception) {
            if (auth.store.isCurrent(expected)) mutableState.value = state.value.copy(
                message = "MyAnimeList progress could not sync. Open MyAnimeList settings to review it.")
        }
    }

    fun unmappedPlayback(expected: MalScope) {
        if (auth.store.isCurrent(expected)) mutableState.value = state.value.copy(
            message = "Automatic tracking needs a known MAL ID and a season-specific episode number. No guessed match was written.")
    }

    private fun replace(item: MalAnime, message: String) {
        val items = state.value.items.filterNot { it.id == item.id } + item
        mutableState.value = state.value.copy(items = items.sortedBy { it.title.lowercase() }, message = message)
        mutableCatalogRevision.value += 1
    }
}
