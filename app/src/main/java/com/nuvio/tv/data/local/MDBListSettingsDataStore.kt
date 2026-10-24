package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.MDBListSettings
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class MDBListRatingSettingsSnapshot(val profileId: Int, val settings: MDBListSettings) {
    override fun toString() = "MDBListRatingSettingsSnapshot(profileId=$profileId, keyPresent=${settings.apiKey.isNotBlank()})"
}

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MDBListSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val credentialCipher: MDBListRatingCredentialCipher
) {
    companion object {
        private const val FEATURE = "mdblist_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val activeProfileId = profileManager.activeProfileId
    private val credentialGuard = MDBListRatingProfileGuard(activeProfileId.value)
    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        guardScope.launch(start = CoroutineStart.UNDISPATCHED) {
            activeProfileId.collect { credentialGuard.observe(it) }
        }
    }

    fun captureCredentialScope() = credentialGuard.capture(activeProfileId.value)
    fun isCredentialScopeCurrent(scope: MDBListRatingCredentialScope) = credentialGuard.valid(scope, activeProfileId.value)

    private val enabledKey = booleanPreferencesKey("mdblist_enabled")
    private val apiKeyKey = stringPreferencesKey("mdblist_api_key")
    private val showTraktKey = booleanPreferencesKey("mdblist_show_trakt")
    private val showImdbKey = booleanPreferencesKey("mdblist_show_imdb")
    private val showTmdbKey = booleanPreferencesKey("mdblist_show_tmdb")
    private val showLetterboxdKey = booleanPreferencesKey("mdblist_show_letterboxd")
    private val showTomatoesKey = booleanPreferencesKey("mdblist_show_tomatoes")
    private val showAudienceKey = booleanPreferencesKey("mdblist_show_audience")
    private val showMetacriticKey = booleanPreferencesKey("mdblist_show_metacritic")
    private val showMalKey = booleanPreferencesKey("mdblist_show_mal")
    private val showOnHeroKey = booleanPreferencesKey("mdblist_show_on_hero")
    private val ratingOrderKey = stringPreferencesKey("mdblist_rating_order")

    val scopedSettings: Flow<MDBListRatingSettingsSnapshot> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val key = try { readAndMigrateCredential(pid, prefs) } catch (error: Exception) {
                if (error is CancellationException) throw error
                "" // Keep legacy bytes for retry, but never expose an unprotected key on failed migration.
            }
            MDBListRatingSettingsSnapshot(pid, MDBListSettings(
                enabled = prefs[enabledKey] ?: false,
                apiKey = key,
                showTrakt = prefs[showTraktKey] ?: true,
                showImdb = prefs[showImdbKey] ?: true,
                showTmdb = prefs[showTmdbKey] ?: true,
                showLetterboxd = prefs[showLetterboxdKey] ?: true,
                showTomatoes = prefs[showTomatoesKey] ?: true,
                showAudience = prefs[showAudienceKey] ?: true,
                showMetacritic = prefs[showMetacriticKey] ?: true,
                showMal = prefs[showMalKey] ?: true,
                showOnHero = prefs[showOnHeroKey] ?: false,
                ratingOrder = prefs[ratingOrderKey]?.split(",")?.filter { it.isNotBlank() }
                    ?: MDBListSettings.DEFAULT_RATING_ORDER
            ))
        }
    }

    val settings: Flow<MDBListSettings> = scopedSettings.map { it.settings }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    /** Compatibility entry for existing callers; async UI work must capture its scope before validation. */
    suspend fun setApiKey(apiKey: String) {
        setApiKeyForScope(captureCredentialScope(), apiKey)
    }

    suspend fun credentialForProfile(profileId: Int): String =
        readAndMigrateCredential(profileId, store(profileId).data.first())

    /** Encryption and all guards succeed before the atomic preference replacement. */
    suspend fun setApiKeyForScope(scope: MDBListRatingCredentialScope, apiKey: String,
        expectedApiKey: String? = null): Boolean {
        if (!isCredentialScopeCurrent(scope) || factory.isProfileDeleted(scope.profileId)) return false
        val encrypted = credentialCipher.encrypt(apiKey.trim(), scope.profileId)
        var saved = false
        store(scope.profileId).edit { prefs ->
            currentCoroutineContext().ensureActive()
            if (!isCredentialScopeCurrent(scope) || factory.isProfileDeleted(scope.profileId)) return@edit
            if (expectedApiKey != null && credentialCipher.decrypt(prefs[apiKeyKey].orEmpty(), scope.profileId) != expectedApiKey) return@edit
            if (encrypted.isBlank()) prefs.remove(apiKeyKey) else prefs[apiKeyKey] = encrypted
            saved = true
        }
        return saved
    }

    private suspend fun readAndMigrateCredential(profileId: Int, prefs: Preferences): String {
        if (factory.isProfileDeleted(profileId)) return ""
        val original = prefs[apiKeyKey].orEmpty()
        if (original.isBlank()) return ""
        if (credentialCipher.isEncrypted(original)) return credentialCipher.decrypt(original, profileId)
        // Never remove or replace legacy bytes until an authenticated encrypted value is ready.
        val encrypted = credentialCipher.encrypt(original, profileId)
        var committed = original
        store(profileId).edit { current ->
            currentCoroutineContext().ensureActive()
            if (factory.isProfileDeleted(profileId)) { committed = ""; return@edit }
            if (current[apiKeyKey] == original) current[apiKeyKey] = encrypted
            committed = current[apiKeyKey].orEmpty()
        }
        return credentialCipher.decrypt(committed, profileId)
    }

    suspend fun setShowTrakt(enabled: Boolean) {
        store().edit { it[showTraktKey] = enabled }
    }

    suspend fun setShowImdb(enabled: Boolean) {
        store().edit { it[showImdbKey] = enabled }
    }

    suspend fun setShowTmdb(enabled: Boolean) {
        store().edit { it[showTmdbKey] = enabled }
    }

    suspend fun setShowLetterboxd(enabled: Boolean) {
        store().edit { it[showLetterboxdKey] = enabled }
    }

    suspend fun setShowTomatoes(enabled: Boolean) {
        store().edit { it[showTomatoesKey] = enabled }
    }

    suspend fun setShowAudience(enabled: Boolean) {
        store().edit { it[showAudienceKey] = enabled }
    }

    suspend fun setShowMetacritic(enabled: Boolean) {
        store().edit { it[showMetacriticKey] = enabled }
    }

    suspend fun setShowMal(enabled: Boolean) {
        store().edit { it[showMalKey] = enabled }
    }

    suspend fun setShowOnHero(enabled: Boolean) {
        store().edit { it[showOnHeroKey] = enabled }
    }

    suspend fun setRatingOrder(order: List<String>) {
        store().edit { it[ratingOrderKey] = order.joinToString(",") }
    }
}
