package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.CompiledStreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridStreamPresentation @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val formatter: DebridStreamFormatter
) {
    private val badgeFilterCache = AtomicReference<Pair<StreamBadgeRules, List<CompiledStreamBadgeFilter>>?>()

    suspend fun apply(
        groups: List<AddonStreams>,
        includeBadgeMatches: Boolean = true
    ): List<AddonStreams> {
        return withContext(Dispatchers.Default) {
            apply(
                groups = groups,
                settings = dataStore.settings.first(),
                streamBadgeRules = streamBadgeSettingsDataStore.settings.first().rules,
                includeBadgeMatches = includeBadgeMatches
            )
        }
    }

    fun apply(
        groups: List<AddonStreams>,
        settings: DebridSettings,
        streamBadgeRules: StreamBadgeRules = StreamBadgeRules(),
        includeBadgeMatches: Boolean = true
    ): List<AddonStreams> {
        if (!settings.canResolvePlayableLinks) return groups
        val badgeFilters by lazy {
            if (includeBadgeMatches) getBadgeFilters(streamBadgeRules) else emptyList()
        }
        val visibleGroups = groups.map { group -> group.copy(streams = group.streams
            .filterNot { it.isInactiveResolverStream(settings) || it.isUncachedDebridStream() }) }
        val managed = visibleGroups.flatMap { it.streams }.filter { it.isManagedDebridStream(settings) }
        val ranked = DirectDebridStreamFilter.applyPreferencesWithFacts(managed, settings, enforceLimits = false)
        val presented = ranked.associate { (stream, facts) -> stream to formatter.format(stream, settings, badgeFilters, facts) }
        // Keep complete candidates for flattened All ranking/enrichment; final limits belong there.
        return visibleGroups.map { group -> group.copy(streams = group.streams.mapNotNull { stream ->
            if(stream.isManagedDebridStream(settings)) presented[stream] else stream
        }.let { rankAll(it, settings, enforceLimits = false) }) }
    }

    /** Used for the flattened All list and autoplay, before pagination. */
    fun rankAll(streams: List<Stream>, settings: DebridSettings, enforceLimits: Boolean = true): List<Stream> {
        if (!settings.canResolvePlayableLinks) return streams
        val managed = streams.filter { it.isManagedDebridStream(settings) }
        val ranked = DirectDebridStreamFilter.applyPreferencesWithFacts(managed, settings, enforceLimits).map { it.first }
        return ranked + streams.filterNot { it.isManagedDebridStream(settings) }
    }

    private fun getBadgeFilters(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> {
        val cached = badgeFilterCache.get()
        if (cached?.first == rules) return cached.second
        val compiled = StreamBadgeMatcher.compile(rules)
        badgeFilterCache.set(rules to compiled)
        return compiled
    }

    private fun Stream.isManagedDebridStream(settings: DebridSettings): Boolean {
        if (nativeFileMetadata != null) return true
        val active = settings.activeResolverCredential ?: return false
        if (needsLocalDebridResolve() && active.provider.supports(DebridProviderCapability.LocalTorrentResolve)) return true
        val explicitProvider = DebridProviders.byId(clientResolve?.service)?.id
        return explicitProvider == active.provider.id && (isDirectDebrid() ||
            clientResolve?.type.let { it.equals("hoster", true) || it.equals("http", true) })
    }

    private fun Stream.isUncachedDebridStream(): Boolean =
        needsLocalDebridResolve() &&
            DebridProviders.byId(debridCacheStatus?.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
            debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED

    private fun Stream.isInactiveResolverStream(settings: DebridSettings): Boolean {
        val streamProviderId = DebridProviders.byId(clientResolve?.service)?.id ?: return false
        val activeProviderId = settings.activeResolverProviderId ?: return false
        return isDirectDebrid() && streamProviderId != activeProviderId
    }
}
