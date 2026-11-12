package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.StreamDebridCacheState

/** Native display facts only. Neither labels nor addon cache claims authorize playback. */
internal object NativeDebridServiceLabels {
    private const val MARKER = "native-service:"
    data class Services(val providers: List<DebridProvider>, val automatic: Boolean = false,
        val resolved: Boolean = false, val verifiedCached: Boolean = false,
        val libraryProviderIds: Set<String> = emptySet()) {
        val availabilityUnverified: Boolean get() =
            !resolved && !verifiedCached && libraryProviderIds.isEmpty() && providers.any { it.supportsAccountLibraryLookup() }
        val availability: String get() = when {
            libraryProviderIds.isNotEmpty() -> "In your library"
            resolved || verifiedCached -> "Ready"
            availabilityUnverified -> "Availability unverified"
            else -> "Not Ready"
        }
        val shortName: String get() = if (automatic) "Automatic" else providers.joinToString(" / ") { it.shortName }
        val name: String get() = providers.joinToString(" · ") { it.displayName }
    }

    fun services(stream: Stream, settings: DebridSettings, scope: StreamAvailabilityScope? = null,
        nowMs: Long = System.currentTimeMillis()): Services {
        val connected = settings.resolverServices.map { it.provider }.associateBy { it.id }
        DebridProviders.byId(stream.nativeResolvedProviderId)?.let { winner ->
            if (winner.id in connected) return Services(listOf(winner), resolved = true)
        }
        val issuer = DebridProviders.byId(stream.clientResolve?.service)
        if (stream.clientResolve != null) {
            if (issuer == null || issuer.id !in connected) return Services(emptyList())
            val cached = scope != null && stream.nativeAvailabilityFor(issuer.id, scope, nowMs)?.state == StreamDebridCacheState.CACHED
            return Services(listOf(issuer), verifiedCached = cached)
        }
        if (!NativeDebridFallbackRouter.neutralHashSource(stream)) return Services(emptyList())
        val accounts = NativeDebridFallbackRouter.accounts(settings, true).map { it.provider }
        val cached = if (scope == null) emptyList() else accounts.mapNotNull { provider ->
            stream.nativeAvailabilityFor(provider.id, scope, nowMs)
                ?.takeIf { it.state == StreamDebridCacheState.CACHED }?.let { provider to it }
        }
        val library = cached.filter { (_, status) -> status.libraryFile != null }.map { it.first }
        return when {
            library.isNotEmpty() -> Services(library, libraryProviderIds = library.mapTo(mutableSetOf()) { it.id })
            cached.isNotEmpty() -> Services(cached.map { it.first }, verifiedCached = true)
            else -> Services(accounts, automatic = accounts.isNotEmpty())
        }
    }

    fun decorate(stream: Stream, settings: DebridSettings, scope: StreamAvailabilityScope? = null,
        nowMs: Long = System.currentTimeMillis()): Stream {
        val services = services(stream, settings, scope, nowMs)
        val labels = buildList {
            if (services.automatic && services.providers.size > 1) {
                add(StreamBadge("Automatic", tagStyle = MARKER + "automatic"))
            }
            services.providers.forEach { provider ->
                val prefix = when {
                    services.resolved -> "Resolved: "
                    provider.id in services.libraryProviderIds -> "In your library: "
                    services.verifiedCached -> "Available: "
                    services.automatic && services.providers.size > 1 -> "Try "
                    else -> "Resolve with: "
                }
                add(StreamBadge("$prefix${provider.displayName}", tagStyle = MARKER + provider.id))
            }
        }
        val facts = DirectDebridStreamFilter.facts(stream, settings)
        val factLabels = (listOf(facts.resolution.label, facts.quality.label, facts.encode.label) +
            facts.visualTags.map { it.label } + facts.audioTags.map { it.label } + facts.audioChannels.map { it.label })
            .filterNot { it.equals("Unknown", true) }.distinct().map { StreamBadge(it, tagStyle = "native-fact") }
        val original = stream.badges.filterNot { it.tagStyle.startsWith(MARKER) || it.tagStyle == "native-fact" }
        return stream.copy(badges = labels + (factLabels + original).distinctBy { it.name.lowercase(java.util.Locale.ROOT) })
    }
}
