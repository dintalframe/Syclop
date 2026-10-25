package com.nuvio.tv.core.providers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.core.network.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ProviderUpdateCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AddonPreferences,
    private val profiles: ProfileDataStore,
    private val repository: AddonRepositoryImpl
) {
    private val engine = ProviderUpdateEngine(AndroidProviderUpdatePersistence(context), System::currentTimeMillis) { url ->
        when (val result = repository.forceRefreshAddon(url)) {
            is NetworkResult.Success -> null
            is NetworkResult.Error -> if (result.code == AddonRepositoryImpl.IDENTITY_CHANGED_CODE) ProviderUpdateError.IDENTITY_CHANGED else ProviderUpdateError.NETWORK_OR_INVALID
            NetworkResult.Loading -> ProviderUpdateError.NETWORK_OR_INVALID
        }
    }
    val states get() = engine.states
    suspend fun updateAll(force: Boolean = false, periodic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val ids = profiles.profilesList.first().filter { it.id == 1 || !it.usesPrimaryAddons }.map { it.id }
        engine.update(preferences.installedUrlsForProfiles(ids), force, periodic)
    }
}

private class AndroidProviderUpdatePersistence(context: Context):ProviderUpdatePersistence {
    private val prefs = context.getSharedPreferences("provider_update_status", Context.MODE_PRIVATE)
    private val gson = Gson()
    override fun read(): Map<String, ProviderUpdateStatus> = runCatching {
        val value = prefs.getString("states", null)?.takeIf { it.length <= 256_000 } ?: return emptyMap()
        val type = object: TypeToken<Map<String, ProviderUpdateStatus>>(){}.type
        val parsed: Map<String, ProviderUpdateStatus> = gson.fromJson(value, type) ?: emptyMap()
        parsed.entries.filter { it.key.matches(Regex("[a-f0-9]{64}")) && it.value.lastAttemptMillis >= 0L && it.value.lastSuccessMillis >= 0L }
            .take(500).associate { it.key to it.value.copy(running = false) }
    }.getOrDefault(emptyMap())
    override fun write(states: Map<String, ProviderUpdateStatus>) {
        check(prefs.edit().putString("states", gson.toJson(states)).commit()) { "Provider status could not be saved." }
    }
}
