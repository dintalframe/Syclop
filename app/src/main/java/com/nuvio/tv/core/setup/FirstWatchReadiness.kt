package com.nuvio.tv.core.setup

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.core.debrid.DebridProviders
import java.security.MessageDigest
import com.nuvio.tv.data.local.InstalledAddonConfiguration
import com.nuvio.tv.domain.model.UserProfile

enum class FirstWatchAccountOutcome { ACCEPTED, REJECTED, UNAVAILABLE }
enum class FirstWatchPlaybackOutcome { STARTED, STARTUP_FAILED }

data class FirstWatchWriteScope(val profileId: Int, val selectionRevision: Long, val credentialRevision: Long,
    val resolverRevision: String = "")

data class FirstWatchAccountObservation(
    val binding: String,
    val checkedAtMs: Long,
    val outcome: FirstWatchAccountOutcome,
    val entitlementExpiresAtMs: Long? = null
)

data class FirstWatchPlaybackObservation(
    val binding: String,
    val checkedAtMs: Long,
    val title: String,
    val videoId: String,
    val engine: String,
    val outcome: FirstWatchPlaybackOutcome
)

data class FirstWatchObservations(
    val accounts: Map<String, FirstWatchAccountObservation> = emptyMap(),
    val playback: FirstWatchPlaybackObservation? = null
)

data class FirstWatchAccountStatus(
    val providerId: String,
    val outcome: FirstWatchAccountOutcome? = null,
    val ageMs: Long? = null
)

data class FirstWatchReadiness(
    val catalogAdded: Boolean = false,
    val sourceAdded: Boolean = false,
    val playbackServiceRequired: Boolean = true,
    val accounts: List<FirstWatchAccountStatus> = emptyList(),
    val playback: FirstWatchPlaybackObservation? = null,
    val playbackAgeMs: Long? = null
)

fun firstWatchReadiness(
    addons: List<Addon>, settings: DebridSettings, profileId: Int, engine: String,
    observations: FirstWatchObservations, nowMs: Long, sourceRevision: String = "", engineRevision: String = "",
    resolverRevision: String = ""
): FirstWatchReadiness {
    val enabled = addons.filter { it.enabled }
    val playback = observations.playback?.takeIf {
        it.binding == firstWatchPlaybackBinding(profileId, addons, settings, engine, sourceRevision, engineRevision, resolverRevision) &&
            it.engine in setOf("exo", "mpv")
    }
    return FirstWatchReadiness(
        catalogAdded = enabled.any { it.catalogs.isNotEmpty() },
        sourceAdded = enabled.any { addon -> addon.resources.any { it.name == "stream" } },
        accounts = DebridProviders.configuredServices(settings).map { service ->
            val observation = observations.accounts[service.provider.id]?.takeIf {
                it.binding == firstWatchAccountBinding(profileId, service.provider.id, service.apiKey)
            }
            FirstWatchAccountStatus(service.provider.id,
                observation?.takeIf { fresh(it.checkedAtMs, nowMs, it.entitlementExpiresAtMs) }?.outcome,
                observation?.checkedAtMs?.takeIf { it in 1..nowMs }?.let { nowMs - it })
        },
        playback = playback?.takeIf { fresh(it.checkedAtMs, nowMs) },
        playbackAgeMs = playback?.checkedAtMs?.takeIf { it in 1..nowMs }?.let { nowMs - it }
    )
}

private fun fresh(checkedAtMs: Long, nowMs: Long, deadline: Long? = null): Boolean =
    checkedAtMs in 1..nowMs && nowMs - checkedAtMs < 900_000L && (deadline == null || nowMs < deadline)

fun firstWatchAccountBinding(profileId: Int, providerId: String, credential: String): String =
    fingerprint(listOf(profileId.toString(), providerId, credential.trim()))

fun firstWatchPlaybackBinding(profileId: Int, addons: List<Addon>, settings: DebridSettings, engine: String,
    sourceRevision: String = "", engineRevision: String = "", resolverRevision: String = ""): String =
    fingerprint(listOf(profileId.toString(), engine, sourceRevision, engineRevision, resolverRevision, settings.preferredResolverProviderId,
        settings.connectedServiceFallbackEnabled.toString(), settings.streamPreferences.toString()) +
        addons.filter { it.enabled }.sortedWith(compareBy({ it.id }, { it.baseUrl })).map {
            listOf(it.id, it.baseUrl, it.version, it.resources, it.catalogs, it.types, it.idPrefixes,
                it.configVersion, it.localConfigurationRevision).toString()
        } +
        DebridProviders.configuredServices(settings).map {
            firstWatchAccountBinding(profileId, it.provider.id, it.apiKey)
        })

private fun fingerprint(values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun shouldShowFirstWatchSetup(isEssential: Boolean, hasAddons: Boolean, skipped: Boolean, alreadyShown: Boolean,
    configurationLoaded: Boolean = true): Boolean =
    configurationLoaded && isEssential && (!hasAddons || alreadyShown) && !skipped

fun firstWatchAddonConfigurationReady(profile: UserProfile?, installed: InstalledAddonConfiguration?): Boolean {
    val owner = profile?.let { if (it.usesPrimaryAddons) 1 else it.id } ?: return false
    return installed?.ownerProfileId == owner
}
