package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

interface MdbListAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

class MdbListAuthStore(
    private val persistence: MdbListAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = MdbListAuthScope(initialProfileId, 0)
    private var playbackGrantId: String? = null
    private var stored = load(initialProfileId)
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<MdbListAuthState> = mutableState.asStateFlow()

    suspend fun capturePlaybackGrantId(profileId: Int): String? {
        if (profileId !in 1..6) return null
        val expected = synchronized(lock) { currentScope.takeIf { it.profileId == profileId && validTokens() } } ?: return null
        val captured = withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (currentScope != expected || !validTokens()) return@synchronized null
                playbackGrantId?.takeIf(::validPlaybackGrantId) ?: try {
                    val id = UUID.randomUUID().toString()
                    persistence.write(profileId, encode(stored, id))
                    playbackGrantId = id
                    id
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
        return captured?.takeIf { synchronized(lock) { currentScope == expected && isPlaybackGrantCurrent(profileId, it) } }
    }

    fun isPlaybackGrantCurrent(profileId: Int, grantId: String): Boolean = synchronized(lock) {
        profileId in 1..6 && currentScope.profileId == profileId && validTokens() && validPlaybackGrantId(grantId) && playbackGrantId == grantId
    }

    fun scope(): MdbListAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: MdbListAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: MdbListAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("MDBList account changed")
    }

    fun authorization(): MdbListAuthorization? = synchronized(lock) {
        stored.tokens?.let { MdbListAuthorization(currentScope, it) }
    }

    fun deviceCode(scope: MdbListAuthScope): String? = synchronized(lock) {
        stored.deviceCode.takeIf { currentScope == scope }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        val value = load(profileId)
        currentScope = MdbListAuthScope(profileId, currentScope.generation + 1)
        stored = value
        publish()
    }

    fun saveSession(session: MdbListDeviceSession, deviceCode: String, scope: MdbListAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) {
            it.copy(session = session, deviceCode = deviceCode)
        }

    fun updateSession(session: MdbListDeviceSession, scope: MdbListAuthScope): Boolean =
        mutate(scope) { it.copy(session = session) }

    fun cancelSession(error: MdbListAuthError? = null, scope: MdbListAuthScope = scope()): Boolean =
        mutate(scope, advanceGeneration = true, error = error) {
            it.copy(session = null, deviceCode = null)
        }

    fun authorize(tokens: MdbListTokens, scope: MdbListAuthScope): Boolean =
        mutate(scope, advanceGeneration = true, renewPlaybackGrant = true) { MdbListStoredAuth(tokens = tokens) }

    fun refreshTokens(tokens: MdbListTokens, expected: MdbListAuthorization): Boolean = synchronized(lock) {
        if (stored.tokens?.accessToken != expected.tokens.accessToken) return@synchronized false
        mutate(expected.scope) { it.copy(tokens = tokens) }
    }

    fun saveUser(user: MdbListUser, scope: MdbListAuthScope): Boolean =
        mutate(scope) { it.copy(user = user) }

    fun clearAuth(
        scope: MdbListAuthScope = scope(),
        error: MdbListAuthError? = null,
        expectedAccessToken: String? = null
    ): Boolean = synchronized(lock) {
        if (expectedAccessToken != null && stored.tokens?.accessToken != expectedAccessToken) {
            return@synchronized false
        }
        mutate(scope, advanceGeneration = true, error = error) { MdbListStoredAuth() }
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = MdbListStoredAuth()
            playbackGrantId = null
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = MdbListStoredAuth()
        playbackGrantId = null
        publish()
    }

    private fun mutate(
        scope: MdbListAuthScope,
        advanceGeneration: Boolean = false,
        error: MdbListAuthError? = null,
        renewPlaybackGrant: Boolean = false,
        transform: (MdbListStoredAuth) -> MdbListStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        val nextGrant = if (value.tokens == null) null
            else if (renewPlaybackGrant) UUID.randomUUID().toString() else playbackGrantId
        persistence.write(scope.profileId, encode(value, nextGrant))
        stored = value
        playbackGrantId = nextGrant
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): MdbListStoredAuth {
        playbackGrantId = null
        val payload = persistence.read(profileId) ?: return MdbListStoredAuth()
        val value = runCatching { json.decodeFromString<MdbListStoredAuth>(payload) }.getOrNull()
            ?.takeIf { it.tokens == null || (it.tokens.accessToken.isNotBlank() && it.tokens.refreshToken.isNotBlank()) }
            ?: return MdbListStoredAuth()
        playbackGrantId = runCatching {
            json.parseToJsonElement(payload).jsonObject["playback_grant_id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { value.tokens != null && validPlaybackGrantId(it) }
        return value
    }

    private fun encode(value: MdbListStoredAuth, grantId: String?): String {
        val values = json.parseToJsonElement(json.encodeToString(value)).jsonObject.toMutableMap()
        if (value.tokens != null) grantId?.takeIf(::validPlaybackGrantId)?.let { values["playback_grant_id"] = JsonPrimitive(it) }
        return JsonObject(values).toString()
    }

    private fun validTokens(): Boolean = stored.tokens?.let { it.accessToken.isNotBlank() && it.refreshToken.isNotBlank() } == true
    private fun validPlaybackGrantId(value: String): Boolean =
        value.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))

    private fun publish(error: MdbListAuthError? = null) {
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: MdbListAuthError? = null) = MdbListAuthState(
        scope = currentScope,
        isAuthenticated = stored.tokens != null,
        user = stored.user,
        session = stored.session,
        error = error
    )
}
