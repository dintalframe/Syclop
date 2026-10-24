package com.nuvio.tv.core.debrid

import android.util.Log

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.core.streams.ServicePlaybackGrantRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxResolver: TorboxDirectDebridResolver,
    private val realDebridResolver: RealDebridDirectDebridResolver,
    private val allDebridResolver: AllDebridDirectDebridResolver,
    private val premiumizeResolver: PremiumizeDirectDebridResolver,
    private val localDebridService: LocalDebridService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val resolvedCache = mutableMapOf<String, CachedDirectDebridResolve>()
    private val inFlightResolves = mutableMapOf<String, Deferred<DirectDebridResolveResult>>()

    init {
        // Account or viewer changes invalidate all process-local grants. No portable grant exists.
        scope.launch {
            combine(dataStore.activeProfileId, dataStore.settings) { profile, settings ->
                profile to DebridProviders.all().associate { it.id to settings.apiKeyFor(it.id).trim() }
            }.collect { (profile, credentials) -> ConnectedServicePlaybackGrants.registry.observe(profile, credentials, dataStore.activeProfileSelectionRevision, dataStore.activeCredentialRevision) }
        }
    }

    private suspend fun playbackScope(): ServicePlaybackGrantRegistry.Scope? {
        val profile = dataStore.activeProfileId.value
        val selectionRevision = dataStore.activeProfileSelectionRevision
        val accountRevision = dataStore.activeCredentialRevision
        if (accountRevision < 0L) return null
        val credentials = DebridProviders.all().associate { it.id to dataStore.credentialForProfile(profile, it.id).trim() }
        if (dataStore.activeProfileId.value != profile) return null
        // Do not use the previous viewer's still-delivering settings emission for a new operation.
        val settings = dataStore.settings.first()
        if (credentials.any { (id, key) -> settings.apiKeyFor(id).trim() != key }) return null
        if (dataStore.activeProfileId.value != profile) return null
        if (dataStore.activeProfileSelectionRevision != selectionRevision || dataStore.activeCredentialRevision != accountRevision) return null
        return ConnectedServicePlaybackGrants.registry.observe(profile, credentials, selectionRevision, accountRevision)
    }

    suspend fun isPlaybackAuthorized(url: String, expectedProfileId: Int? = null): Boolean {
        val binding = playbackScope() ?: return false
        if (expectedProfileId != null && binding.profileId != expectedProfileId) return false
        return ConnectedServicePlaybackGrants.registry.permits(url, binding)
    }

    suspend fun resolve(stream: Stream, season: Int?, episode: Int?): DirectDebridResolveResult {
        val binding = playbackScope() ?: return DirectDebridResolveResult.MissingApiKey
        val settings = dataStore.settings.first()
        val provider = if (stream.needsLocalDebridResolve()) settings.activeResolverProviderId
            else DebridProviders.byId(stream.clientResolve?.service)?.id
        val result = resolveInternal(stream, season, episode, binding, settings)
        if (result is DirectDebridResolveResult.Success) {
            if (provider == null) {
                Log.w("DebridResolve", "stage=final_grant result=provider_missing")
                return DirectDebridResolveResult.Error
            }
            if (playbackScope() != binding) {
                Log.w("DebridResolve", "stage=final_grant result=scope_changed")
                return DirectDebridResolveResult.Error
            }
            if (!ConnectedServicePlaybackGrants.registry.issue(result.url, provider, binding)) {
                Log.w("DebridResolve", "stage=final_grant result=policy_rejected")
                return DirectDebridResolveResult.Error
            }
        }
        return result
    }

    private suspend fun resolveInternal(
        stream: Stream,
        season: Int?,
        episode: Int?,
        binding: ServicePlaybackGrantRegistry.Scope,
        capturedSettings: com.nuvio.tv.domain.model.DebridSettings
    ): DirectDebridResolveResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridResolveResult.Stale
        }
        val cacheKey = stream.directDebridResolveCacheKey(season, episode)?.let { "${binding.profileId}:${binding.revision}:$it" }
        if (cacheKey == null) {
            return resolveUncached(stream, season, episode, binding, capturedSettings)
        }
        getCachedResult(cacheKey, binding)?.let {
            return it
        }

        var ownsResolve = false
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            resolveUncached(stream, season, episode, binding, capturedSettings)
        }
        val activeResolve = mutex.withLock {
            getCachedResultLocked(cacheKey, binding)?.let { cached ->
                return@withLock null to cached
            }
            val existing = inFlightResolves[cacheKey]
            if (existing != null) {
                existing to null
            } else {
                inFlightResolves[cacheKey] = newResolve
                ownsResolve = true
                newResolve to null
            }
        }
        activeResolve.second?.let {
            newResolve.cancel()
            return it
        }
        val deferred = activeResolve.first ?: return DirectDebridResolveResult.Error
        if (!ownsResolve) newResolve.cancel()
        if (ownsResolve) deferred.start()

        return try {
            val result = deferred.await()
            if (ownsResolve && result is DirectDebridResolveResult.Success) {
                mutex.withLock {
                    resolvedCache[cacheKey] = CachedDirectDebridResolve(
                        result = result,
                        origin = binding,
                        cachedAtMs = System.currentTimeMillis()
                    )
                }
            }
            result
        } finally {
            if (ownsResolve) {
                mutex.withLock {
                    if (inFlightResolves[cacheKey] === deferred) {
                        inFlightResolves.remove(cacheKey)
                    }
                }
            }
        }
    }

    suspend fun cachedPlayableStream(stream: Stream, season: Int?, episode: Int?): Stream? {
        val binding = playbackScope() ?: return null
        if (!shouldResolveToPlayableStream(stream)) return null
        val cacheKey = stream.directDebridResolveCacheKey(season, episode)?.let { "${binding.profileId}:${binding.revision}:$it" } ?: return null
        val result = getCachedResult(cacheKey, binding) ?: return null
        if (playbackScope() != binding) return null
        val provider = if (stream.needsLocalDebridResolve()) dataStore.settings.first().activeResolverProviderId
            else DebridProviders.byId(stream.clientResolve?.service)?.id
        if (provider == null || playbackScope() != binding ||
            !ConnectedServicePlaybackGrants.registry.issue(result.url, provider, binding)) return null
        return stream.withResolvedDebridUrl(result)
    }

    suspend fun resolveToPlayableStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridPlayableResult {
        if (!shouldResolveToPlayableStream(stream)) {
            val url = stream.getStreamUrl()
            return if (url != null && isPlaybackAuthorized(url)) DirectDebridPlayableResult.Success(stream)
                else DirectDebridPlayableResult.MissingApiKey
        }
        return when (val result = resolve(stream, season, episode)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.NotCached -> DirectDebridPlayableResult.NotCached
            DirectDebridResolveResult.Stale -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }

    suspend fun shouldResolveToPlayableStream(stream: Stream): Boolean {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return false
        if (stream.needsLocalDebridResolve()) {
            return localTorrentResolveCredential(settings) != null
        }
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        val explicitHoster = providerId in setOf(DebridProviders.REAL_DEBRID_ID, DebridProviders.ALL_DEBRID_ID) &&
            stream.clientResolve?.type.let { it.equals("hoster", true) || it.equals("http", true) }
        if (!explicitHoster && (!stream.isDirectDebrid() || stream.getStreamUrl() != null)) return false
        return providerId == settings.activeResolverProviderId &&
            settings.apiKeyFor(providerId).isNotBlank()
    }

    private suspend fun getCachedResult(cacheKey: String, binding: ServicePlaybackGrantRegistry.Scope): DirectDebridResolveResult.Success? =
        mutex.withLock { getCachedResultLocked(cacheKey, binding) }

    private fun getCachedResultLocked(cacheKey: String, binding: ServicePlaybackGrantRegistry.Scope): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[cacheKey] ?: return null
        return if (cached.isReusable(binding, System.currentTimeMillis())) {
            cached.result
        } else {
            resolvedCache.remove(cacheKey)
            null
        }
    }

    private suspend fun resolveUncached(
        stream: Stream,
        season: Int?,
        episode: Int?,
        binding: ServicePlaybackGrantRegistry.Scope,
        capturedSettings: com.nuvio.tv.domain.model.DebridSettings
    ): DirectDebridResolveResult {
        if (stream.needsLocalDebridResolve()) {
            return resolveLocalTorrentStream(stream, season, episode, binding, capturedSettings)
        }
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return DirectDebridResolveResult.Error
        val operation = CapturedDebridOperation(providerId, capturedSettings.apiKeyFor(providerId)) {
            ConnectedServicePlaybackGrants.registry.scopeIsCurrent(binding)
        }
        return when (providerId) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(stream, season, episode, operation)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(stream, season, episode, operation)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(stream, season, episode, operation)
            DebridProviders.ALL_DEBRID_ID -> allDebridResolver.resolve(stream, season, episode, operation)
            else -> DirectDebridResolveResult.Error
        }
    }

    private suspend fun Stream.directDebridResolveCacheKey(season: Int?, episode: Int?): String? {
        if (needsLocalDebridResolve()) {
            val settings = dataStore.settings.first()
            val account = localTorrentResolveCredential(settings) ?: return null
            val apiKey = account.apiKey.trim().takeIf { it.isNotBlank() } ?: return null
            val identity = getEffectiveInfoHash() ?: torrentMagnetUri() ?: behaviorHints?.filename ?: return null
            return listOf(
                account.provider.id,
                apiKey.stableFingerprint(),
                identity.trim().lowercase(),
                getEffectiveFileIdx()?.toString().orEmpty(),
                behaviorHints?.filename.orEmpty().trim().lowercase(),
                season?.toString().orEmpty(),
                episode?.toString().orEmpty()
            ).joinToString("|")
        }
        val resolve = clientResolve ?: return null
        val providerId = DebridProviders.byId(resolve.service)?.id ?: return null
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks || providerId != settings.activeResolverProviderId) return null
        val apiKey = settings.apiKeyFor(providerId).trim().takeIf { it.isNotBlank() } ?: return null
        val identity = resolve.infoHash
            ?: resolve.magnetUri
            ?: getStreamUrl()
            ?: resolve.torrentName
            ?: resolve.filename
            ?: return null

        return listOf(
            providerId,
            apiKey.stableFingerprint(),
            identity.trim().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            (resolve.filename ?: behaviorHints?.filename).orEmpty().trim().lowercase(),
            (season ?: resolve.season)?.toString().orEmpty(),
            (episode ?: resolve.episode)?.toString().orEmpty()
        ).joinToString("|")
    }

    private suspend fun resolveLocalTorrentStream(
        stream: Stream,
        season: Int?,
        episode: Int?,
        binding: ServicePlaybackGrantRegistry.Scope,
        settings: com.nuvio.tv.domain.model.DebridSettings
    ): DirectDebridResolveResult {
        val account = localTorrentResolveCredential(settings) ?: return DirectDebridResolveResult.MissingApiKey
        val operation = CapturedDebridOperation(account.provider.id, account.apiKey) {
            ConnectedServicePlaybackGrants.registry.scopeIsCurrent(binding)
        }
        val hash = stream.getEffectiveInfoHash()?.trim()?.lowercase()
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) {
            return DirectDebridResolveResult.NotCached
        }
        if (
            !hash.isNullOrBlank() &&
            stream.debridCacheStatus?.state != StreamDebridCacheState.CACHED &&
            account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck)
        ) {
            when (scopedDebridRequest(operation) { localDebridService.isCached(account, hash) }) {
                false -> return DirectDebridResolveResult.NotCached
                true, null -> Unit
            }
        }

        val magnet = DebridMagnetBuilder.fromStream(stream)
            ?: return DirectDebridResolveResult.Stale
        val resolveStream = stream.copy(
            clientResolve = StreamClientResolve(
                type = "torrent",
                infoHash = stream.getEffectiveInfoHash(),
                fileIdx = stream.getEffectiveFileIdx(),
                magnetUri = magnet,
                sources = stream.sources,
                torrentName = null,
                filename = stream.behaviorHints?.filename,
                mediaType = null,
                mediaId = null,
                mediaOnlyId = null,
                title = stream.title ?: stream.name,
                season = season,
                episode = episode,
                service = account.provider.id,
                serviceIndex = null,
                serviceExtension = null,
                isCached = stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED,
                stream = null
            )
        )

        return when (account.provider.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(resolveStream, season, episode, operation)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(resolveStream, season, episode, operation)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(resolveStream, season, episode, operation)
            DebridProviders.ALL_DEBRID_ID -> allDebridResolver.resolve(resolveStream, season, episode, operation)
            else -> DirectDebridResolveResult.Error
        }
    }

    private fun localTorrentResolveCredential(
        settings: com.nuvio.tv.domain.model.DebridSettings
    ): DebridServiceCredential? =
        settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
}

private const val DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

internal data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val origin: ServicePlaybackGrantRegistry.Scope,
    val cachedAtMs: Long
) {
    fun isReusable(binding: ServicePlaybackGrantRegistry.Scope, nowMs: Long): Boolean =
        origin == binding && nowMs - cachedAtMs in 0..DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS
}

sealed class DirectDebridPlayableResult {
    data class Success(val stream: Stream) : DirectDebridPlayableResult()
    data object MissingApiKey : DirectDebridPlayableResult()
    data object NotCached : DirectDebridPlayableResult()
    data object Stale : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

private fun String.stableFingerprint(): String {
    val hash = fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
    return hash.toULong().toString(16)
}

private fun Stream.withResolvedDebridUrl(result: DirectDebridResolveResult.Success): Stream =
    copy(
        url = result.url,
        externalUrl = null,
        clientResolve = null,
        behaviorHints = behaviorHints.mergeResolvedDebridHints(result),
        nativeFileMetadata = com.nuvio.tv.domain.model.StreamFileMetadata(
            selectedFileBytes = result.videoSize?.takeIf { it > 0L } ?: nativeFileMetadata?.selectedFileBytes?.takeIf { url == result.url },
            durationMillis = nativeFileMetadata?.durationMillis?.takeIf { url == result.url },
            durationOrigin = nativeFileMetadata?.durationOrigin?.takeIf { url == result.url },
            sourceIdentity = nativeFileMetadata?.sourceIdentity ?: stableKey(0).substringBeforeLast('|')
        )
    )

private fun StreamBehaviorHints?.mergeResolvedDebridHints(
    result: DirectDebridResolveResult.Success
): StreamBehaviorHints {
    val current = this
    if (current != null) {
        return current.copy(
            proxyHeaders = null, // Authenticated service URLs do not inherit arbitrary addon proxy headers.
            filename = result.filename ?: current.filename,
            videoSize = result.videoSize ?: current.videoSize
        )
    }
    return StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        filename = result.filename,
        videoSize = result.videoSize
    )
}
