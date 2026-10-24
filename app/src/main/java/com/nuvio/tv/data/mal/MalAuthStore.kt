package com.nuvio.tv.data.mal

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.local.ProfileDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Every payload is encrypted by the Android implementation; a write must commit before returning. */
internal interface MalAccountPersistence {
    val available: Boolean get() = true
    fun read(key: String): String?
    fun write(values: Map<String, String?>)
    fun clear()
}

/** MAL-only per-profile state. New account state is published only after encrypted persistence commits. */
@Singleton
class MalAuthStore internal constructor(
    private val persistence: MalAccountPersistence,
    profiles: Flow<Int>,
    backgroundScope: CoroutineScope,
    private val shippedConfiguration: MalConfiguration,
    initialProfileId: Int = 0,
    private val diagnose: (MalAuthStage, Throwable?) -> Unit = MalAuthDiagnostics::event
) : ProfileScopedCredentialStore {
    @Inject constructor(@ApplicationContext context: Context, profileDataStore: ProfileDataStore) : this(
        AndroidMalAccountPersistence(context), profileDataStore.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        MalConfiguration(BuildConfig.MAL_CLIENT_ID.trim(), BuildConfig.MAL_REDIRECT_URI.trim())
    )

    private val lock = Any()
    private val mutableState = MutableStateFlow(MalAccountState(scope = MalScope(initialProfileId, 0), configuration = shippedConfiguration))
    val state: StateFlow<MalAccountState> = mutableState.asStateFlow()
    private var tokens: MalTokens? = null

    init {
        if (initialProfileId in 1..6) load(initialProfileId)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            profiles.distinctUntilChanged().collect { profile -> if (profile != state.value.scope.profileId) load(profile) }
        }
    }

    val storageAvailable: Boolean get() = persistence.available
    fun isCurrent(expected: MalScope): Boolean = state.value.scope == expected
    fun credentials(expected: MalScope): MalTokens? = synchronized(lock) { if (persistence.available && isCurrent(expected)) tokens else null }

    fun configure(configuration: MalConfiguration) = synchronized(lock) {
        val current = state.value
        require(current.scope.profileId in 1..6) { "The active profile is still loading." }
        if (current.configuration == configuration) return@synchronized
        val next = current.copy(scope = current.scope.copy(generation = current.scope.generation + 1),
            configuration = configuration, connected = false, username = null)
        persistence.write(mapOf("application_configuration" to configurationJson(configuration), key(next.scope.profileId) to record(next, null)))
        tokens = null
        mutableState.value = next
    }

    fun complete(expected: MalScope, received: MalTokens, username: String): Boolean = synchronized(lock) {
        if (!isCurrent(expected) || expected.profileId !in 1..6) return@synchronized false
        require(received.accessToken.isNotBlank() && received.refreshToken.isNotBlank() && received.expiresAtMs > 0)
        // A newly completed login is a different grant, even on the same viewer/registration.
        val next = state.value.copy(scope = expected.copy(generation = expected.generation + 1), connected = true, username = username)
        persistence.write(mapOf(key(expected.profileId) to record(next, received)))
        tokens = received
        mutableState.value = next
        true
    }

    fun replaceTokens(expected: MalScope, previous: MalTokens, received: MalTokens): Boolean = synchronized(lock) {
        if (!persistence.available || !isCurrent(expected) || !state.value.connected || tokens != previous) return@synchronized false
        persistence.write(mapOf(key(expected.profileId) to record(state.value, received)))
        tokens = received
        true
    }

    fun automaticProgress(enabled: Boolean) = synchronized(lock) {
        require(state.value.scope.profileId in 1..6) { "The active profile is still loading." }
        val next = state.value.copy(automaticProgress = enabled)
        persistence.write(mapOf(key(next.scope.profileId) to record(next, tokens)))
        mutableState.value = next
    }

    fun signOut(expected: MalScope = state.value.scope): Boolean = synchronized(lock) {
        if (!isCurrent(expected)) return@synchronized false
        val next = state.value.copy(scope = expected.copy(generation = expected.generation + 1), connected = false, username = null)
        persistence.write(mapOf(key(expected.profileId) to record(next, null)))
        tokens = null
        mutableState.value = next
        true
    }

    /** A late refresh failure may only revoke the exact token grant it actually used. */
    fun signOutIfTokensCurrent(expected: MalScope, previous: MalTokens): Boolean = synchronized(lock) {
        if (!persistence.available || !isCurrent(expected) || !state.value.connected || tokens != previous) return@synchronized false
        signOut(expected)
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(mapOf(key(profileId) to null))
        if (state.value.scope.profileId == profileId) {
            tokens = null
            mutableState.value = MalAccountState(scope = state.value.scope.copy(generation = state.value.scope.generation + 1),
                configuration = state.value.configuration)
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        tokens = null
        mutableState.value = MalAccountState(scope = state.value.scope.copy(generation = state.value.scope.generation + 1), configuration = shippedConfiguration)
    }

    private fun load(profileId: Int) = synchronized(lock) {
        require(profileId in 1..6)
        val scope = MalScope(profileId, state.value.scope.generation + 1)
        // Clear previous-viewer memory before a storage read can block or fail.
        tokens = null
        mutableState.value = MalAccountState(scope = scope, configuration = shippedConfiguration)
        try {
            val account = persistence.read(key(profileId))?.let { Json.parseToJsonElement(it).jsonObject }
            val application = persistence.read("application_configuration")?.let { Json.parseToJsonElement(it).jsonObject }
            fun string(name: String) = account?.get(name)?.jsonPrimitive?.contentOrNull.orEmpty()
            val configuration = MalConfiguration(
                application?.get("client_id")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: string("client_id").takeIf(String::isNotBlank) ?: shippedConfiguration.clientId,
                application?.get("redirect_uri")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: string("redirect_uri").takeIf(String::isNotBlank) ?: shippedConfiguration.redirectUri)
            val registrationMatches = string("client_id").isBlank() || string("client_id") == configuration.clientId
            val restored = string("access_token").takeIf { registrationMatches && it.isNotBlank() }?.let { access ->
                MalTokens(access, string("refresh_token"), account?.get("expires_at")?.jsonPrimitive?.longOrNull ?: 0)
            }?.takeIf { it.refreshToken.isNotBlank() && it.expiresAtMs > 0 }
            tokens = restored
            mutableState.value = MalAccountState(scope = scope, configuration = configuration,
                connected = restored != null, username = string("username").takeIf { restored != null && it.isNotBlank() },
                automaticProgress = string("automatic_progress") != "false")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (failure: Exception) {
            diagnose(MalAuthStage.STORAGE_READ, failure)
            // Keep the requested profile disconnected. Do not delete unreadable data or stop observing profiles.
        }
    }

    private fun configurationJson(configuration: MalConfiguration) = buildJsonObject {
        put("client_id", configuration.clientId); put("redirect_uri", configuration.redirectUri)
    }.toString()
    private fun record(account: MalAccountState, credential: MalTokens?) = buildJsonObject {
        put("client_id", account.configuration.clientId); put("redirect_uri", account.configuration.redirectUri)
        put("username", account.username.orEmpty()); put("automatic_progress", account.automaticProgress.toString())
        credential?.let { put("access_token", it.accessToken); put("refresh_token", it.refreshToken); put("expires_at", it.expiresAtMs) }
    }.toString()
    private fun key(profile: Int) = "account.p$profile"
}
