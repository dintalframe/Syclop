package com.nuvio.tv.data.mal

import com.nuvio.tv.core.initialization.InitializationFailure
import com.nuvio.tv.core.initialization.InitializationProvider
import com.nuvio.tv.core.initialization.InitializationReadException
import com.nuvio.tv.core.initialization.InitializationReadGate
import com.nuvio.tv.core.initialization.InitializationReadResult
import com.nuvio.tv.core.initialization.InitializationReadScope
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    private val reads = InitializationReadGate()
    private val mutableState = MutableStateFlow(MalLibraryState(scope = auth.state.value.scope))
    val state = mutableState.asStateFlow()
    private val mutableCatalogRevision = MutableStateFlow(0L)
    val catalogRevision = mutableCatalogRevision.asStateFlow()

    init {
        worker.launch {
            auth.state.map { it.scope }.distinctUntilChanged().collect { current ->
                mutableState.update { previous ->
                    // A delayed initial emission must not erase a completed read for this same grant.
                    if (!auth.store.isCurrent(current) || previous.scope == current) previous
                    else MalLibraryState(scope = current)
                }
            }
        }
    }

    suspend fun captureInitializationReadScope(): InitializationReadScope {
        val captured = auth.state.value.scope
        if (!auth.state.value.connected) throw InitializationReadException(InitializationFailure.UNAVAILABLE)
        return InitializationReadScope(InitializationProvider.MAL, readKey(captured)) {
            auth.store.isCurrent(captured) && auth.state.value.connected
        }.also { it.checkpoint() }
    }

    /** The same full list read serves settings refresh and first-use initialization. */
    suspend fun awaitInitializationImport(expected: InitializationReadScope): InitializationReadResult {
        require(expected.provider == InitializationProvider.MAL)
        reads.recentComplete(expected)?.let { return it }
        return readLibrary(expected)
    }

    private suspend fun readLibrary(expected: InitializationReadScope): InitializationReadResult {
        require(expected.provider == InitializationProvider.MAL)
        val captured = auth.state.value.scope
        if (expected.operationKey != readKey(captured)) {
            throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        }
        return reads.run(expected) {
            mutations.withLock {
                expected.checkpoint()
                checkReadOwner(captured)
                val previous = state.value.takeIf { it.scope == captured } ?: MalLibraryState(scope = captured)
                mutableState.value = previous.copy(loading = true, message = null)
                try {
                    val library = auth.authorized(captured) { api.library(it) }
                    expected.checkpoint()
                    checkReadOwner(captured)
                    mutableState.value = MalLibraryState(scope = captured, items = library.items,
                        truncated = library.truncated,
                        message = if (library.truncated) "Showing the first 2,000 MyAnimeList entries."
                            else "MyAnimeList list updated.")
                    mutableCatalogRevision.value += 1
                    InitializationReadResult(count = library.items.size, truncated = library.truncated)
                } catch (cancelled: CancellationException) {
                    if (expected.isCurrent() && auth.store.isCurrent(captured)) {
                        mutableState.value = state.value.copy(loading = false)
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    expected.checkpoint()
                    checkReadOwner(captured)
                    mutableState.value = state.value.copy(loading = false,
                        message = "Could not load MyAnimeList. Your local playback history is unchanged.")
                    if (failure is InitializationReadException) throw failure
                    throw InitializationReadException(InitializationFailure.NETWORK)
                }
            }
        }
    }

    suspend fun refresh() {
        if (!auth.state.value.connected) return
        try {
            readLibrary(captureInitializationReadScope())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The awaited read publishes a fixed, account-scoped failure message; existing callers keep Unit.
        }
    }

    private fun readKey(scope: MalScope): String = "mal:${scope.profileId}:${scope.generation}"

    private fun checkReadOwner(expected: MalScope) {
        if (!auth.store.isCurrent(expected) || !auth.state.value.connected) {
            throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
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
