package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TRAKT_LEGACY_FORCED_TOKEN_LIFETIME_SECONDS = 86_400
private const val TRAKT_DOCUMENTED_TOKEN_LIFETIME_SECONDS = 604_800

internal fun normalizeTraktTokenLifetimeSeconds(expiresIn: Int): Int {
    return if (expiresIn == TRAKT_LEGACY_FORCED_TOKEN_LIFETIME_SECONDS) {
        TRAKT_DOCUMENTED_TOKEN_LIFETIME_SECONDS
    } else {
        expiresIn
    }
}

data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val userSlug: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresAt: Long? = null,
    val pollInterval: Int? = null,
    val registrationClientId: String? = null,
    val profileId: Int = 1
) {
    override fun toString(): String = "TraktAuthState(authenticated=$isAuthenticated, profileId=$profileId)"

    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val credentialCipher: DebridCredentialCipher
) {
    companion object {
        private const val FEATURE = "trakt_auth_store"
    }

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val tokenTypeKey = stringPreferencesKey("token_type")
    private val createdAtKey = longPreferencesKey("created_at")
    private val expiresInKey = intPreferencesKey("expires_in")

    private val usernameKey = stringPreferencesKey("username")
    private val userSlugKey = stringPreferencesKey("user_slug")

    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")
    private val registrationClientIdKey = stringPreferencesKey("registration_client_id")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val state: Flow<TraktAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { preferences ->
            migrateLegacyCredentials(profileId, preferences)
            TraktAuthState(
                accessToken = readSecret(preferences, accessTokenKey, profileId),
                refreshToken = readSecret(preferences, refreshTokenKey, profileId),
                tokenType = preferences[tokenTypeKey],
                createdAt = preferences[createdAtKey],
                expiresIn = preferences[expiresInKey]?.let(::normalizeTraktTokenLifetimeSeconds),
                username = preferences[usernameKey],
                userSlug = preferences[userSlugKey],
                deviceCode = readSecret(preferences, deviceCodeKey, profileId),
                userCode = preferences[userCodeKey],
                verificationUrl = preferences[verificationUrlKey],
                expiresAt = preferences[expiresAtKey],
                pollInterval = preferences[pollIntervalKey],
                registrationClientId = preferences[registrationClientIdKey],
                profileId = profileId
            )
        }
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated

    suspend fun getCurrentState(): TraktAuthState {
        return getCurrentState(profileManager.activeProfileId.value)
    }

    suspend fun getCurrentState(profileId: Int): TraktAuthState {
        val prefs = store(profileId).data.first()
        migrateLegacyCredentials(profileId, prefs)
        return TraktAuthState(
            accessToken = readSecret(prefs, accessTokenKey, profileId),
            refreshToken = readSecret(prefs, refreshTokenKey, profileId),
            tokenType = prefs[tokenTypeKey],
            createdAt = prefs[createdAtKey],
            expiresIn = prefs[expiresInKey]?.let(::normalizeTraktTokenLifetimeSeconds),
            username = prefs[usernameKey],
            userSlug = prefs[userSlugKey],
            deviceCode = readSecret(prefs, deviceCodeKey, profileId),
            userCode = prefs[userCodeKey],
            verificationUrl = prefs[verificationUrlKey],
            expiresAt = prefs[expiresAtKey],
            pollInterval = prefs[pollIntervalKey],
            registrationClientId = prefs[registrationClientIdKey],
            profileId = profileId
        )
    }

    suspend fun saveToken(token: TraktTokenResponseDto, profileId: Int, clientId: String) {
        store(profileId).edit { preferences ->
            preferences[accessTokenKey] = credentialCipher.encrypt(token.accessToken, profileId, "trakt_access_token")
            preferences[refreshTokenKey] = credentialCipher.encrypt(token.refreshToken, profileId, "trakt_refresh_token")
            preferences[registrationClientIdKey] = clientId
            preferences[tokenTypeKey] = token.tokenType
            preferences[createdAtKey] = token.createdAt
            preferences[expiresInKey] = normalizeTraktTokenLifetimeSeconds(token.expiresIn)
        }
    }

    suspend fun saveUser(username: String?, userSlug: String?, profileId: Int) {
        store(profileId).edit { preferences ->
            if (username.isNullOrBlank()) {
                preferences.remove(usernameKey)
            } else {
                preferences[usernameKey] = username
            }
            if (userSlug.isNullOrBlank()) {
                preferences.remove(userSlugKey)
            } else {
                preferences[userSlugKey] = userSlug
            }
        }
    }

    suspend fun saveDeviceFlow(data: TraktDeviceCodeResponseDto, profileId: Int, clientId: String) {
        val now = System.currentTimeMillis()
        store(profileId).edit { preferences ->
            preferences[deviceCodeKey] = credentialCipher.encrypt(data.deviceCode, profileId, "trakt_device_code")
            preferences[registrationClientIdKey] = clientId
            preferences[userCodeKey] = data.userCode
            preferences[verificationUrlKey] = data.verificationUrl
            preferences[expiresAtKey] = now + (data.expiresIn * 1000L)
            preferences[pollIntervalKey] = data.interval
        }
    }

    suspend fun updatePollInterval(seconds: Int, profileId: Int) {
        store(profileId).edit { preferences ->
            preferences[pollIntervalKey] = seconds
        }
    }

    suspend fun clearDeviceFlow() = clearDeviceFlowForProfile(profileManager.activeProfileId.value)

    suspend fun clearDeviceFlowForProfile(profileId: Int) {
        store(profileId).edit { preferences ->
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }

    suspend fun clearAuth() = clearAuthForProfile(profileManager.activeProfileId.value)

    suspend fun clearAuthForProfile(profileId: Int) {
        store(profileId).edit { preferences ->
            preferences.remove(registrationClientIdKey)
            preferences.remove(accessTokenKey)
            preferences.remove(refreshTokenKey)
            preferences.remove(tokenTypeKey)
            preferences.remove(createdAtKey)
            preferences.remove(expiresInKey)
            preferences.remove(usernameKey)
            preferences.remove(userSlugKey)
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }

    private val secretKeys get() = listOf(accessTokenKey, refreshTokenKey, deviceCodeKey)

    private fun readSecret(preferences: Preferences, key: Preferences.Key<String>, profileId: Int): String? =
        preferences[key]?.let { credentialCipher.decrypt(it, profileId, "trakt_${key.name}") }?.takeIf(String::isNotBlank)

    private suspend fun migrateLegacyCredentials(profileId: Int, preferences: Preferences) {
        if (secretKeys.none { key -> preferences[key]?.let { !credentialCipher.isEncrypted(it) } == true }) return
        store(profileId).edit { current ->
            secretKeys.forEach { key ->
                current[key]?.takeIf { !credentialCipher.isEncrypted(it) }?.let { legacy ->
                    current[key] = credentialCipher.encrypt(legacy, profileId, "trakt_${key.name}")
                }
            }
        }
    }

}
