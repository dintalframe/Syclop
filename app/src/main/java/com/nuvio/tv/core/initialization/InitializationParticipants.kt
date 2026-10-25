package com.nuvio.tv.core.initialization

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.simkl.SimklAuthStorage
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.mal.MalAuthRepository
import com.nuvio.tv.data.mal.MalRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observes actual loaded credential state. Tokens never enter the marker or event identity. */
internal class AccountImportEvents(private val preferences: InitializationPreferences,
    private val provider: InitializationProvider) {
    private val lock = Mutex()
    private val revisions = mutableMapOf<Int, Pair<Long, Long>>()
    private val pendingInvalidations = mutableSetOf<Int>()
    suspend fun ensureReady(profile: Int, current: () -> Boolean) = lock.withLock {
        if (profile !in pendingInvalidations) return@withLock
        try {
            preferences.invalidateProvider(profile, provider, current)
            if (!current()) throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
            pendingInvalidations -= profile
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: InitializationReadException) { throw failure }
        catch (_: Exception) { throw InitializationReadException(InitializationFailure.UNAVAILABLE) }
    }
    suspend fun stamp(profile: Int, selection: Long, revision: Long, connected: Boolean, identity: String?, current: () -> Boolean): String = lock.withLock {
        if (profile !in 1..6 || revision < 0 || !current()) return@withLock "loading"
        val prior = revisions[profile]
        if (!connected || (prior != null && prior.first == selection && prior.second != revision)) {
            pendingInvalidations += profile
            try {
                preferences.invalidateProvider(profile, provider, current)
                if (current()) pendingInvalidations -= profile
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep observing; capture/Retry must repair this before using any marker. */ }
        }
        if (!current()) return@withLock "loading"
        revisions[profile] = selection to revision
        val hash = identity?.let { InitializationMarkerKey.fromAccount(profile, provider, it)?.accountHash }.orEmpty()
        "$profile:$revision:$connected:$hash:${profile in pendingInvalidations}"
    }
}

private fun ProfileManager.owns(profile: Int, selection: Long) =
    activeProfileId.value == profile && activeProfileSelectionRevision == selection

@Singleton
class TraktInitializationParticipant @Inject constructor(
    private val profiles: ProfileManager,
    private val data: TraktAuthDataStore,
    private val auth: TraktAuthService,
    private val progress: TraktProgressService,
    private val library: TraktLibraryService,
    preferences: InitializationPreferences
) : InitializationParticipant {
    override val provider = InitializationProvider.TRAKT
    private val events = AccountImportEvents(preferences, provider)
    private fun identity(state: com.nuvio.tv.data.local.TraktAuthState) =
        state.userSlug?.trim()?.takeIf { it.isNotEmpty() }?.let { "slug:$it" }
            ?: state.username?.trim()?.takeIf { it.isNotEmpty() }?.let { "name:$it" }
    override val accountChanges: Flow<String> = data.state.map { state ->
        val selection = profiles.activeProfileSelectionRevision
        val revision = data.grantRevision(state.profileId)
        events.stamp(state.profileId, selection, revision, state.isAuthenticated, identity(state)) {
            profiles.owns(state.profileId, selection) && data.grantRevision(state.profileId) == revision
        }
    }.distinctUntilChanged()
    override suspend fun capture(profileId: Int, selectionRevision: Long): InitializationSession? {
        val expected = auth.captureInitializationReadScope()
        val account = data.getCurrentState(profileId)
        expected.checkpoint()
        if (!profiles.owns(profileId, selectionRevision) || account.profileId != profileId)
            throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        library.hideForeignInitializationProjection(expected)
        progress.hideForeignInitializationProjection(expected)
        val revision = data.grantRevision(profileId)
        events.stamp(profileId, selectionRevision, revision, account.isAuthenticated, identity(account)) {
            profiles.owns(profileId, selectionRevision) && expected.isCurrent()
        }
        events.ensureReady(profileId) { profiles.owns(profileId, selectionRevision) && expected.isCurrent() }
        if (!account.isAuthenticated) return null
        if (!auth.hasRequiredCredentials()) throw InitializationReadException(InitializationFailure.UNAVAILABLE)
        val marker = identity(account)?.let { InitializationMarkerKey.fromAccount(profileId, provider, it) }
            ?: throw InitializationReadException(InitializationFailure.UNAVAILABLE)
        val captured = InitializationReadScope(provider, expected.operationKey) {
            profiles.owns(profileId, selectionRevision) && expected.isCurrent()
        }
        return object : InitializationSession {
            override val marker = marker
            override val scope = captured
            override suspend fun read(progressUpdate: (InitializationStep) -> Unit): InitializationReadResult {
                scope.checkpoint()
                progressUpdate(InitializationStep.HISTORY)
                val history = progress.awaitInitializationImport(scope)
                scope.checkpoint()
                progressUpdate(InitializationStep.LIBRARY)
                val lists = library.awaitInitializationImport(scope)
                scope.checkpoint()
                return InitializationReadResult(count = (history.count ?: 0) + (lists.count ?: 0),
                    truncated = history.truncated || lists.truncated,
                    complete = history.complete && lists.complete)
            }
        }
    }
}

@Singleton
class SimklInitializationParticipant @Inject constructor(
    private val profiles: ProfileManager,
    private val auth: SimklAuthStorage,
    private val repository: SimklSyncRepository,
    preferences: InitializationPreferences
) : InitializationParticipant {
    override val provider = InitializationProvider.SIMKL
    private val events = AccountImportEvents(preferences, provider)
    private fun identity(state: com.nuvio.tv.data.simkl.SimklAuthState) =
        state.accountId?.takeIf { it > 0 }?.let { "id:$it" }
            ?: state.username?.trim()?.takeIf { it.isNotEmpty() }?.let { "name:$it" }
    override val accountChanges: Flow<String> = combine(auth.state, profiles.activeProfileId) { _, _ ->
        val expected = auth.currentScope() // synchronized: a profile load has finished before returning.
        val state = auth.state.value
        val selection = profiles.activeProfileSelectionRevision
        events.stamp(expected.profileId, selection, expected.generation, state.isAuthenticated, identity(state)) {
            profiles.owns(expected.profileId, selection) && auth.isCurrent(expected)
        }
    }.distinctUntilChanged()
    override suspend fun capture(profileId: Int, selectionRevision: Long): InitializationSession? {
        combine(auth.state, profiles.activeProfileId) { _, _ -> auth.currentScope().profileId }
            .first { it == profileId }
        val expected = auth.currentScope()
        val account = auth.state.value
        if (!profiles.owns(profileId, selectionRevision) || expected.profileId != profileId)
            throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        val original = repository.captureInitializationReadScope()
        if (account.isAuthenticated) repository.hideForeignInitializationProjection(original)
        events.stamp(profileId, selectionRevision, expected.generation, account.isAuthenticated, identity(account)) {
            profiles.owns(profileId, selectionRevision) && auth.isCurrent(expected)
        }
        events.ensureReady(profileId) { profiles.owns(profileId, selectionRevision) && auth.isCurrent(expected) }
        if (!account.isAuthenticated) return null
        val marker = identity(account)?.let { InitializationMarkerKey.fromAccount(profileId, provider, it) }
            ?: throw InitializationReadException(InitializationFailure.UNAVAILABLE)
        val captured = InitializationReadScope(provider, original.operationKey) {
            profiles.owns(profileId, selectionRevision) && auth.isCurrent(expected) && original.isCurrent()
        }
        return object : InitializationSession {
            override val marker = marker
            override val scope = captured
            override suspend fun read(progress: (InitializationStep) -> Unit): InitializationReadResult {
                progress(InitializationStep.HISTORY)
                return repository.awaitInitializationImport(scope)
            }
        }
    }
}

@Singleton
class MalInitializationParticipant @Inject constructor(
    private val profiles: ProfileManager,
    private val auth: MalAuthRepository,
    private val repository: MalRepository,
    preferences: InitializationPreferences
) : InitializationParticipant {
    override val provider = InitializationProvider.MAL
    private val events = AccountImportEvents(preferences, provider)
    override val accountChanges: Flow<String> = auth.state.filter { it.scope.profileId in 1..6 }.map {
        val account = auth.store.initializationSnapshot()
        val selection = profiles.activeProfileSelectionRevision
        events.stamp(account.scope.profileId, selection, account.scope.generation, account.connected, account.username) {
            profiles.owns(account.scope.profileId, selection) && auth.store.isCurrent(account.scope)
        }
    }.distinctUntilChanged()
    override suspend fun capture(profileId: Int, selectionRevision: Long): InitializationSession? {
        auth.state.first { it.scope.profileId == profileId }
        val account = auth.store.initializationSnapshot()
        if (account.scope.profileId != profileId || !profiles.owns(profileId, selectionRevision) || !auth.store.isCurrent(account.scope))
            throw InitializationReadException(InitializationFailure.ACCOUNT_CHANGED)
        events.stamp(profileId, selectionRevision, account.scope.generation, account.connected, account.username) {
            profiles.owns(profileId, selectionRevision) && auth.store.isCurrent(account.scope)
        }
        events.ensureReady(profileId) { profiles.owns(profileId, selectionRevision) && auth.store.isCurrent(account.scope) }
        if (!account.connected) return null
        val marker = account.username?.let { InitializationMarkerKey.fromAccount(profileId, provider, it) }
            ?: throw InitializationReadException(InitializationFailure.UNAVAILABLE)
        val original = repository.captureInitializationReadScope()
        val captured = InitializationReadScope(provider, original.operationKey) {
            profiles.owns(profileId, selectionRevision) && auth.store.isCurrent(account.scope) && original.isCurrent()
        }
        return object : InitializationSession {
            override val marker = marker
            override val scope = captured
            override suspend fun read(progress: (InitializationStep) -> Unit): InitializationReadResult {
                progress(InitializationStep.LIBRARY)
                return repository.awaitInitializationImport(scope)
            }
        }
    }
}
