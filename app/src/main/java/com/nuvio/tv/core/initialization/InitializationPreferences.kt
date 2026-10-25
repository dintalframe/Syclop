package com.nuvio.tv.core.initialization

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

@Singleton
class InitializationPreferences @Inject constructor(private val factory: ProfileDataStoreFactory) : InitializationMarkerStore {
    private val completedKey = stringSetPreferencesKey("completed_account_imports_v1")
    private fun store(profile: Int) = factory.get(profile, "first_use_initialization")
    override suspend fun contains(key: InitializationMarkerKey): Boolean =
        key.stored in store(key.profileId).data.first()[completedKey].orEmpty()

    override suspend fun invalidateProvider(profileId: Int, provider: InitializationProvider, current: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!current()) return
        store(profileId).edit { prefs ->
            currentCoroutineContext().ensureActive()
            if (current()) prefs[completedKey] = prefs[completedKey].orEmpty()
                .filterNot { it.startsWith("v$INITIALIZATION_SCHEMA:${provider.name}:") }.toSet()
        }
    }

    override suspend fun complete(key: InitializationMarkerKey, current: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!current()) return false
        var saved = false
        store(key.profileId).edit { prefs ->
            currentCoroutineContext().ensureActive()
            if (!current()) return@edit
            // Only bounded opaque account hashes, never tokens, names, URLs or payload history.
            prefs[completedKey] = (prefs[completedKey].orEmpty() + key.stored).toList().takeLast(64).toSet()
            saved = true
        }
        return saved && current()
    }
}
