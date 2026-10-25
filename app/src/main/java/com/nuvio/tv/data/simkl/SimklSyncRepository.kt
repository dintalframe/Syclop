package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.initialization.InitializationMarkerKey
import com.nuvio.tv.core.initialization.InitializationProvider
import com.nuvio.tv.core.initialization.InitializationReadGate
import com.nuvio.tv.core.initialization.InitializationReadScope
import com.nuvio.tv.core.initialization.InitializationReadResult
import com.nuvio.tv.core.initialization.InitializationReadException
import com.nuvio.tv.core.initialization.InitializationFailure

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class SimklSyncRepository internal constructor(
    private val engine: SimklSyncEngine,
    private val storage: SimklSyncStorage,
    private val authRepository: SimklAuthRepository,
    private val authStorage: SimklAuthStorage,
    private val profileManager: ProfileManager,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope
) {
    @Inject constructor(engine: SimklSyncEngine, storage: SimklSyncStorage,
        authRepository: SimklAuthRepository, authStorage: SimklAuthStorage,
        profileManager: ProfileManager) : this(engine, storage, authRepository, authStorage,
        profileManager, Dispatchers.IO, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val loadMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val initializationReads = InitializationReadGate()
    private val _state = MutableStateFlow(SimklSyncState())
    private val _projection = MutableStateFlow(SimklSnapshotProjection.Empty)
    private var loadedProfileId: Int? = null
    private var profileGeneration = 0L

    val state: StateFlow<SimklSyncState> = _state.asStateFlow()
    internal val projection: StateFlow<SimklSnapshotProjection> = _projection.asStateFlow()

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (loadedProfileId != profileId) {
                    profileGeneration += 1L
                    loadedProfileId = null
                    _state.value = SimklSyncState()
                    _projection.value = SimklSnapshotProjection.Empty
                    loadProfile(profileId)
                }
            }
        }
    }

    suspend fun ensureLoaded() = withContext(ioDispatcher) {
        val profileId = profileManager.activeProfileId.value
        if (loadedProfileId != profileId) loadProfile(profileId)
    }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        scope.launch { refresh(intent) }
    }

    fun captureInitializationReadScope(): InitializationReadScope {
        val auth = authStorage.currentScope()
        val profile = profileManager.activeProfileId.value
        val selection = profileManager.activeProfileSelectionRevision
        return InitializationReadScope(InitializationProvider.SIMKL, "$profile:$selection:${auth.generation}") {
            profileManager.activeProfileId.value == profile && profileManager.activeProfileSelectionRevision == selection &&
                authStorage.isCurrent(auth) && authStorage.state.value.isAuthenticated
        }
    }

    /** Account replacement never exposes a former login's saved projection; its disk file is retained. */
    fun hideForeignInitializationProjection(expected: InitializationReadScope) {
        if (!expected.isCurrent()) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        val identity = accountIdentityHash(profileManager.activeProfileId.value)
        if (_state.value.snapshot.accountIdentityHash != identity || identity == null) {
            val previous = _state.value.snapshot
            if (previous.accountIdentityHash != null || previous.isInitialized || previous.entries.isNotEmpty()) profileGeneration += 1L
            _projection.value = SimklSnapshotProjection.Empty
            _state.value = SimklSyncState(hasLoaded = true)
        }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) {
        try { readSnapshot(intent, captureInitializationReadScope(), forceBootstrap = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Fixed state error is published by the owning read. */ }
    }

    suspend fun awaitInitializationImport(expected: InitializationReadScope): InitializationReadResult =
        initializationReads.recentComplete(expected)
            ?: readSnapshot(TrackingRefreshIntent.USER_INITIATED, expected, forceBootstrap = true)

    private suspend fun readSnapshot(intent: TrackingRefreshIntent, expected: InitializationReadScope,
        forceBootstrap: Boolean): InitializationReadResult = withContext(ioDispatcher) {
        initializationReads.run(expected) {
            expected.checkpoint()
            ensureLoaded()
            val profileId = profileManager.activeProfileId.value
            val generation = profileGeneration
            val auth = authStorage.currentScope()
            expected.checkpoint()
            val current = _state.value
            val sameAccount = accountIdentityHash(profileId)?.let { it == current.snapshot.accountIdentityHash } == true
            if (!forceBootstrap && sameAccount && !shouldRunSimklRefresh(intent, current.snapshot.lastCheckedAtEpochMs,
                    System.currentTimeMillis(), current.errorMessage != null)) {
                // A polling no-op is not evidence of a newly completed import.
                return@run InitializationReadResult(current.snapshot.entries.size, complete = false)
            }
            withContext(SimklInitializationAuthScope(auth)) {
                refreshSnapshot(profileId, generation, expected, forceBootstrap)
            }
        }
    }

    suspend fun clearCurrentProfile() {
        // Capture before dispatch, while this call still belongs to its initiating viewer.
        val profileId = profileManager.activeProfileId.value
        clearProfile(profileId)
    }

    suspend fun clearProfile(profileId: Int) = withContext(ioDispatcher) {
        require(profileId in 1..6)
        snapshotMutex.withLock {
            storage.remove(profileId)
            if (profileId == profileManager.activeProfileId.value) {
                profileGeneration += 1L
                loadedProfileId = profileId
                _projection.value = SimklSnapshotProjection.Empty
                _state.value = SimklSyncState(hasLoaded = true)
            }
        }
    }

    suspend fun removeProfile(profileId: Int) = clearProfile(profileId)

    suspend fun removePlaybackSessions(sessionIds: Set<Long>) = withContext(ioDispatcher) {
        if (sessionIds.isEmpty()) return@withContext
        ensureLoaded()
        snapshotMutex.withLock {
            val profileId = profileManager.activeProfileId.value
            val current = _state.value
            val playback = current.snapshot.playback.filterNot { session -> session.id in sessionIds }
            if (playback.size == current.snapshot.playback.size) return@withLock
            val snapshot = current.snapshot.copy(playback = playback)
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (profileId == profileManager.activeProfileId.value) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
            }
        }
    }

    internal suspend fun commitScrobble(result: SimklScrobbleResult) = withContext(ioDispatcher) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        snapshotMutex.withLock {
            if (!isCurrent(profileId, generation)) return@withLock
            val current = _state.value
            val snapshot = current.snapshot.applyScrobbleResult(
                result = result,
                committedAtEpochMs = System.currentTimeMillis()
            )
            if (snapshot == current.snapshot) return@withLock
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (isCurrent(profileId, generation)) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
            }
        }
    }

    internal suspend fun commitMutation(receipt: SimklMutationReceipt) = withContext(ioDispatcher) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        snapshotMutex.withLock {
            if (!isCurrent(profileId, generation)) return@withLock
            val current = _state.value
            val snapshot = current.snapshot.applyMutationReceipt(
                receipt = receipt,
                committedAtEpochMs = System.currentTimeMillis()
            )
            if (snapshot == current.snapshot) return@withLock
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (isCurrent(profileId, generation)) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
            }
        }
    }

    private fun accountIdentityHash(profileId: Int): String? {
        val account = authStorage.state.value
        if (!account.isAuthenticated || authStorage.currentScope().profileId != profileId) return null
        val identity = account.accountId?.takeIf { it > 0 }?.let { "id:$it" }
            ?: account.username?.trim()?.takeIf { it.isNotEmpty() }?.let { "name:$it" }
            ?: return null
        return InitializationMarkerKey.fromAccount(profileId, InitializationProvider.SIMKL, identity)?.accountHash
    }

    private suspend fun loadProfile(profileId: Int) = loadMutex.withLock {
        if (loadedProfileId == profileId) return@withLock
        val loaded = storage.load(profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { decodeSnapshot(payload) }
                    .onFailure { Log.w(TAG, "Unable to decode Simkl snapshot") }
                    .getOrNull()
            }
            ?: SimklSyncSnapshot()
        // A legacy/other-account cache is retained on disk but not exposed to a new account.
        val identity = accountIdentityHash(profileId)
        val snapshot = loaded.takeIf { identity != null && it.accountIdentityHash == identity } ?: SimklSyncSnapshot()
        val projection = buildProjection(snapshot)
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            _projection.value = projection
            _state.value = SimklSyncState(snapshot = snapshot, hasLoaded = true)
        }
    }

    private suspend fun refreshSnapshot(profileId: Int, generation: Long, expected: InitializationReadScope,
        forceBootstrap: Boolean): InitializationReadResult = snapshotMutex.withLock {
        expected.checkpoint()
        val identity = accountIdentityHash(profileId)
            ?: throw InitializationReadException(InitializationFailure.UNAVAILABLE)
        val bootstrap = forceBootstrap || _state.value.snapshot.accountIdentityHash != identity
        val previous = if (_state.value.snapshot.accountIdentityHash == identity) _state.value else SimklSyncState(hasLoaded = true)
        if (bootstrap && _state.value.snapshot.accountIdentityHash != identity) _projection.value = SimklSnapshotProjection.Empty
        _state.value = previous.copy(isLoading = true, errorMessage = null)
        val result = try {
            engine.synchronize(if (bootstrap) SimklSyncSnapshot() else previous.snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Simkl sync failed (${error.javaClass.simpleName})")
            if (isCurrent(profileId, generation)) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = "Could not refresh Simkl. Saved data is unchanged."
                )
            }
            throw InitializationReadException(InitializationFailure.NETWORK)
        }
        expected.checkpoint()
        if (!isCurrent(profileId, generation)) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        // Simkl publishes a new position on /sync/playback a moment after it answers a stop or a
        // pause, so a read that follows the user's own write can still describe the previous viewing.
        // Keep our own newer rows over the fetched ones; the account stays authoritative for the rest.
        val merged = result.copy(
            accountIdentityHash = identity,
            playback = if (bootstrap) result.playback else mergeFetchedPlayback(result.playback, previous.snapshot.playback)
        )
        val projection = if (
            merged.entries === previous.snapshot.entries &&
            merged.playback === previous.snapshot.playback
        ) {
            _projection.value
        } else {
            buildProjection(merged)
        }
        expected.checkpoint()
        if (!bootstrap) authRepository.synchronizeUserSettings(merged.activities?.settings?.all)
        expected.checkpoint()
        if (!isCurrent(profileId, generation)) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        if (!storage.saveIfCurrent(profileId, encodeSnapshot(merged)) {
                expected.isCurrent() && isCurrent(profileId, generation)
            }) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        expected.checkpoint()
        if (!isCurrent(profileId, generation)) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        _projection.value = projection
        _state.value = SimklSyncState(snapshot = merged, hasLoaded = true)
        InitializationReadResult(merged.entries.size, complete = merged.isInitialized)
    }

    private fun isCurrent(profileId: Int, generation: Long): Boolean =
        profileId == profileManager.activeProfileId.value && generation == profileGeneration

    fun invalidateProjections(animeIdPreference: SimklAnimeIdPreference? = null) {
        animeIdPreference?.let { preference -> SimklAnimeIdPreferenceHolder.current = preference }
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        scope.launch {
            snapshotMutex.withLock {
                if (!isCurrent(profileId, generation)) return@withLock
                val current = _state.value
                val projection = buildProjection(current.snapshot)
                if (!isCurrent(profileId, generation) || current.snapshot !== _state.value.snapshot) {
                    return@withLock
                }
                _projection.value = projection
                _state.value = current.copy(projectionVersion = current.projectionVersion + 1L)
            }
        }
    }

    private suspend fun buildProjection(snapshot: SimklSyncSnapshot): SimklSnapshotProjection =
        withContext(Dispatchers.Default) { SimklSnapshotProjection.create(snapshot) }

    private suspend fun decodeSnapshot(payload: String): SimklSyncSnapshot =
        withContext(Dispatchers.Default) { json.decodeFromString(payload) }

    private suspend fun encodeSnapshot(snapshot: SimklSyncSnapshot): String =
        withContext(Dispatchers.Default) { json.encodeToString(snapshot) }

    private companion object {
        const val TAG = "SimklSync"
    }
}
