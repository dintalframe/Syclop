package com.nuvio.tv.core.setup

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.repository.AddonRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class FirstWatchConfiguration(
    val profileId: Int,
    val addons: List<Addon>,
    val settings: DebridSettings,
    val engine: String,
    val sourceRevision: String = "",
    val engineRevision: String = "",
    val resolverRevision: String = ""
) {
    val binding: String get() = firstWatchPlaybackBinding(profileId, addons, settings, engine, sourceRevision, engineRevision, resolverRevision)
    override fun toString() = "FirstWatchConfiguration(profileId=$profileId, configuration=[redacted])"
}

data class FirstWatchSnapshot(val configuration: FirstWatchConfiguration? = null,
    val observations: FirstWatchObservations = FirstWatchObservations()) {
    fun at(nowMs: Long): FirstWatchReadiness? = configuration?.let {
        firstWatchReadiness(it.addons, it.settings, it.profileId, it.engine, observations, nowMs,
            it.sourceRevision, it.engineRevision, it.resolverRevision)
    }
}

/** Local configuration/evidence only. Endpoint checks remain explicit user actions. */
@Singleton
class FirstWatchReadinessRepository @Inject constructor(
    addons: AddonRepository,
    private val accounts: DebridSettingsDataStore,
    players: PlayerSettingsDataStore,
    profiles: ProfileManager,
    addonPreferences: AddonPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sources = combine(addons.getInstalledAddons(), addonPreferences.firstWatchSourceRevision) { installed, revision -> installed to revision }
    private val engines = combine(players.playerSettings, players.firstWatchEngineRevision) { player, revision -> player to revision }
    private val resolvers = combine(accounts.settings, accounts.firstWatchResolverRevision) { settings, revision -> settings to revision }
    val configuration = combine(profiles.activeProfileId, sources, resolvers,
        engines) { profile, source, resolver, engine ->
        val player = engine.first
        FirstWatchConfiguration(profile, source.first, resolver.first,
            if (player.playerPreference == PlayerPreference.EXTERNAL) "external"
            else "${player.playerPreference.name}:${player.internalPlayerEngine.name}", source.second, engine.second, resolver.second)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val snapshot = combine(configuration, accounts.firstWatchObservations) { config, observations ->
        FirstWatchSnapshot(config, observations)
    }.stateIn(scope, SharingStarted.Eagerly, FirstWatchSnapshot())

    fun captureScope() = FirstWatchWriteScope(accounts.activeProfileId.value,
        accounts.activeProfileSelectionRevision, accounts.activeCredentialRevision, configuration.value?.resolverRevision.orEmpty())

    fun isCurrent(captured: FirstWatchWriteScope): Boolean = captured.credentialRevision >= 0L &&
        accounts.activeProfileId.value == captured.profileId &&
        accounts.activeProfileSelectionRevision == captured.selectionRevision &&
        accounts.activeCredentialRevision == captured.credentialRevision

    suspend fun recordPlayback(captured: FirstWatchWriteScope, observation: FirstWatchPlaybackObservation,
        isCurrent: () -> Boolean): Boolean = accounts.recordFirstWatchPlayback(captured, observation, isCurrent)
}
