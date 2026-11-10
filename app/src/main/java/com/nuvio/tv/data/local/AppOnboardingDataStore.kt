package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appOnboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_onboarding",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class AppOnboardingDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.appOnboardingDataStore
    private val hasSeenAuthQrOnFirstLaunchKey = booleanPreferencesKey("has_seen_auth_qr_on_first_launch")
    private val lastAcknowledgedUpdateVersionCodeKey = intPreferencesKey("last_acknowledged_update_version_code")

    val lastAcknowledgedUpdateVersionCode: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[lastAcknowledgedUpdateVersionCodeKey]
    }

    suspend fun acknowledgeUpdateVersionCode(versionCode: Int) {
        dataStore.edit { prefs ->
            prefs[lastAcknowledgedUpdateVersionCodeKey] = maxOf(versionCode, prefs[lastAcknowledgedUpdateVersionCodeKey] ?: 0)
        }
    }

    val hasSeenAuthQrOnFirstLaunch: Flow<Boolean> = dataStore.data.map { prefs ->
        BuildConfig.LOCAL_ONLY_BUILD || (prefs[hasSeenAuthQrOnFirstLaunchKey] ?: false)
    }

    suspend fun setHasSeenAuthQrOnFirstLaunch(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[hasSeenAuthQrOnFirstLaunchKey] = value
        }
    }
}
