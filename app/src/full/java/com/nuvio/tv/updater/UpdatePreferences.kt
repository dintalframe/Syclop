package com.nuvio.tv.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_settings")

@Singleton
class UpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.updateDataStore
    private val preferences = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private val deferredTagKey = stringPreferencesKey("deferred_release_tag")
    private val deferredUntilKey = longPreferencesKey("deferred_release_until_ms")
    private val lastCheckAtKey = longPreferencesKey("last_check_at_ms")
    private val updateBannerEnabledKey = booleanPreferencesKey("update_banner_enabled")
    private val updateChannelKey = stringPreferencesKey("update_channel")
    private val downloadedTagKey = stringPreferencesKey("downloaded_release_tag")
    private val downloadedApkPathKey = stringPreferencesKey("downloaded_apk_path")
    val deferredRelease: Flow<Pair<String, Long>?> = preferences.map { prefs ->
        prefs[deferredTagKey]?.let { tag -> tag to (prefs[deferredUntilKey] ?: 0L) }
    }

    val lastCheckAtMs: Flow<Long> = preferences.map { prefs ->
        prefs[lastCheckAtKey] ?: 0L
    }

    val updateBannerEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[updateBannerEnabledKey] ?: true
    }

    val updateChannel: Flow<UpdateChannel> = preferences.map { prefs ->
        UpdateChannel.fromStoredValue(prefs[updateChannelKey])
            ?: UpdateChannel.defaultForVersion(BuildConfig.VERSION_NAME)
    }

    /** Release tag and APK path of the update already downloaded, if any, so it survives the app being closed. */
    val downloadedUpdate: Flow<Pair<String, String>?> = preferences.map { prefs ->
        val tag = prefs[downloadedTagKey]
        val path = prefs[downloadedApkPathKey]
        if (tag != null && path != null) tag to path else null
    }

    suspend fun deferRelease(tag: String, untilMs: Long) {
        dataStore.edit { prefs ->
            prefs[deferredTagKey] = tag
            prefs[deferredUntilKey] = untilMs
        }
    }

    suspend fun setLastCheckAtMs(value: Long) {
        dataStore.edit { prefs ->
            prefs[lastCheckAtKey] = value
        }
    }

    suspend fun setUpdateBannerEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[updateBannerEnabledKey] = enabled
        }
    }

    suspend fun setDownloadedUpdate(tag: String?, apkPath: String?) {
        dataStore.edit { prefs ->
            if (tag == null || apkPath == null) {
                prefs.remove(downloadedTagKey)
                prefs.remove(downloadedApkPathKey)
            } else {
                prefs[downloadedTagKey] = tag
                prefs[downloadedApkPathKey] = apkPath
            }
        }
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        dataStore.edit { prefs ->
            prefs[updateChannelKey] = channel.storedValue
        }
    }
}
