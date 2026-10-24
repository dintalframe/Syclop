package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SimklV2AuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

/** Atomic profile-scoped state. Persistence must encrypt the complete serialized record. */
class SimklV2AuthStore(
    private val persistence: SimklV2AuthPersistence,
    initialProfileId: Int = 1
) : SimklAuthStorage, ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = SimklAuthScope(initialProfileId, 0)
    private val initial = load(initialProfileId)
    private var stored = initial.value
    private var failedRead = initial.error != null
    private val mutableState = MutableStateFlow(stateFor(initial.error))
    override val state: StateFlow<SimklAuthState> = mutableState.asStateFlow()

    override fun currentScope(): SimklAuthScope = synchronized(lock) { currentScope }
    override fun isCurrent(scope: SimklAuthScope): Boolean = synchronized(lock) { currentScope == scope }
    override fun authorization(): SimklAuthorization? = synchronized(lock) {
        stored.tokens?.let { SimklAuthorization(currentScope, it.accessToken) }
    }
    override fun oauthAuthorization(): SimklV2Authorization? = synchronized(lock) {
        stored.tokens?.let { SimklV2Authorization(currentScope, it) }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        require(profileId in 1..6)
        if (currentScope.profileId == profileId && !failedRead) return@synchronized
        // Invalidate the prior viewer before touching the next viewer's persistence.
        // A failed read must never leave the prior grant exposed or kill the observer.
        currentScope = SimklAuthScope(profileId, currentScope.generation + 1)
        stored = Stored()
        publish()
        val loaded = load(profileId)
        stored = loaded.value
        failedRead = loaded.error != null
        publish(loaded.error)
    }

    override fun savePinSession(session: SimklPinSession, scope: SimklAuthScope): Boolean =
        mutate(scope) { it.copy(metadata = it.metadata.copy(pinSession = session)) }

    override fun clearPinSession(error: SimklAuthError?, scope: SimklAuthScope): Boolean =
        mutate(scope, advanceGeneration = true, error = error) {
            it.copy(metadata = it.metadata.copy(pinSession = null))
        }

    // AUTH V1 tokens cannot be converted to V2, and must never be paired with a V2 client ID.
    override fun completePinAuthorization(token: String, scope: SimklAuthScope): Boolean = false

    override fun saveV2Tokens(
        tokens: SimklV2Tokens,
        scope: SimklAuthScope,
        expectedAccessToken: String?
    ): Boolean = synchronized(lock) {
        if (currentScope != scope || stored.tokens?.accessToken != expectedAccessToken) return@synchronized false
        mutate(scope, advanceGeneration = expectedAccessToken == null) {
            it.copy(tokens = tokens, metadata = it.metadata.copy(pinSession = null))
        }
    }

    override fun saveIdentity(
        username: String?, accountId: Long?, settingsActivityWatermark: String?, scope: SimklAuthScope
    ): Boolean = mutate(scope) {
        it.copy(metadata = it.metadata.copy(
            username = username, accountId = accountId, hasFetchedUserSettings = true,
            settingsActivityWatermark = settingsActivityWatermark ?: it.metadata.settingsActivityWatermark
        ))
    }

    override fun recordSettingsActivityWatermark(watermark: String, scope: SimklAuthScope): Boolean =
        mutate(scope) { it.copy(metadata = it.metadata.copy(settingsActivityWatermark = watermark)) }

    override fun clearAuth(error: SimklAuthError?, scope: SimklAuthScope, expectedAccessToken: String?): Boolean =
        synchronized(lock) {
            if (expectedAccessToken != null && stored.tokens?.accessToken != expectedAccessToken) return@synchronized false
            mutate(scope, advanceGeneration = true, error = error) { Stored() }
        }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = Stored()
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = Stored()
        publish()
    }

    private fun mutate(
        scope: SimklAuthScope, advanceGeneration: Boolean = false, error: SimklAuthError? = null,
        transform: (Stored) -> Stored
    ): Boolean = synchronized(lock) {
        if (currentScope != scope || scope.profileId !in 1..6) return@synchronized false
        val value = transform(stored)
        // Publish only after the encrypted, synchronous commit succeeds; never plaintext fallback.
        persistence.write(scope.profileId, json.encodeToString(value))
        stored = value
        failedRead = false
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): Loaded {
        if (profileId !in 1..6) return Loaded(Stored())
        val payload = try {
            persistence.read(profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Catch only the persistence read boundary, not unrelated store/programming logic.
            return Loaded(Stored(), SimklAuthError.STORAGE)
        } ?: return Loaded(Stored())
        val value = runCatching { json.decodeFromString<Stored>(payload) }.getOrNull()
            ?.takeIf { it.tokens == null || it.tokens.isValid() }
            ?: return Loaded(Stored(), SimklAuthError.STORAGE)
        // The polling secret is process-local; a public old code cannot resume after restart.
        return Loaded(value.copy(metadata = value.metadata.copy(pinSession = null)))
    }

    private data class Loaded(val value: Stored, val error: SimklAuthError? = null)

    private fun publish(error: SimklAuthError? = null) { mutableState.value = stateFor(error) }
    private fun stateFor(error: SimklAuthError? = null) = SimklAuthState(
        isAuthenticated = stored.tokens != null, username = stored.metadata.username,
        accountId = stored.metadata.accountId, hasFetchedUserSettings = stored.metadata.hasFetchedUserSettings,
        settingsActivityWatermark = stored.metadata.settingsActivityWatermark,
        pinSession = stored.metadata.pinSession, error = error
    )

    @Serializable
    private data class Stored(
        val tokens: SimklV2Tokens? = null, val metadata: SimklStoredAuthMetadata = SimklStoredAuthMetadata()
    ) {
        override fun toString() = "SimklStoredAuth()"
    }
}
