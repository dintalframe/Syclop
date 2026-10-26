package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.core.anime.NativeAnimeEpisodeProof

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
class DirectDebridResolver internal constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxResolver: TorboxDirectDebridResolver,
    private val realDebridResolver: RealDebridDirectDebridResolver,
    private val allDebridResolver: AllDebridDirectDebridResolver,
    private val premiumizeResolver: PremiumizeDirectDebridResolver,
    private val localDebridService: LocalDebridService,
    private val scope: CoroutineScope,
    private val grants: ServicePlaybackGrantRegistry = ConnectedServicePlaybackGrants.registry,
    private val animeMappings: NativeAnimeEpisodeMappings? = null,
    private val overallBudgetMillis: Long = 30_000L,
    private val serviceBudgetMillis: Long = 12_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Inject constructor(dataStore: DebridSettingsDataStore, torboxResolver: TorboxDirectDebridResolver,
        realDebridResolver: RealDebridDirectDebridResolver, allDebridResolver: AllDebridDirectDebridResolver,
        premiumizeResolver: PremiumizeDirectDebridResolver, localDebridService: LocalDebridService,
        animeMappings: NativeAnimeEpisodeMappings? = null) : this(
        dataStore, torboxResolver, realDebridResolver, allDebridResolver, premiumizeResolver, localDebridService,
        CoroutineScope(SupervisorJob() + Dispatchers.Default), animeMappings = animeMappings)
    private val mutex = Mutex()
    private val resolvedCache = mutableMapOf<String, CachedDirectDebridResolve>()
    private data class SharedResolve(val deferred: Deferred<NativeDebridAttempt>, var waiters: Int = 0)
    private val inFlightResolves = mutableMapOf<String, SharedResolve>()
    private val serviceCooldowns = LinkedHashMap<String, Long>()
    private data class AnimeOrigin(val binding: ServicePlaybackGrantRegistry.Scope, val proofKey: String)
    private val animeOrigins = LinkedHashMap<String, AnimeOrigin>()

    init {
        scope.launch {
            combine(dataStore.activeProfileId, dataStore.settings) { profile, settings ->
                profile to DebridProviders.all().associate { it.id to settings.apiKeyFor(it.id).trim() }
            }.collect { (profile, credentials) -> grants.observe(profile, credentials, dataStore.activeProfileSelectionRevision, dataStore.activeCredentialRevision) }
        }
    }

    private suspend fun playbackScope(): ServicePlaybackGrantRegistry.Scope? {
        val profile = dataStore.activeProfileId.value
        val selectionRevision = dataStore.activeProfileSelectionRevision
        val accountRevision = dataStore.activeCredentialRevision
        if (accountRevision < 0L) return null
        val credentials = DebridProviders.all().associate { it.id to dataStore.credentialForProfile(profile, it.id).trim() }
        if (dataStore.activeProfileId.value != profile) return null
        val settings = dataStore.settings.first()
        if (credentials.any { (id, key) -> settings.apiKeyFor(id).trim() != key }) return null
        if (dataStore.activeProfileId.value != profile || dataStore.activeProfileSelectionRevision != selectionRevision ||
            dataStore.activeCredentialRevision != accountRevision) return null
        return grants.observe(profile, credentials, selectionRevision, accountRevision)
    }

    private fun bindingIsCurrent(binding: ServicePlaybackGrantRegistry.Scope): Boolean =
        binding.accountRevision >= 0L && dataStore.activeProfileId.value == binding.profileId &&
            dataStore.activeProfileSelectionRevision == binding.selectionRevision &&
            dataStore.activeCredentialRevision == binding.accountRevision && grants.scopeIsCurrent(binding)

    suspend fun isPlaybackAuthorized(url: String, expectedProfileId: Int? = null,
        nativeVideoId: String? = null, season: Int? = null, episode: Int? = null, nativeMediaType: String? = null): Boolean {
        val binding = playbackScope() ?: return false
        if (expectedProfileId != null && binding.profileId != expectedProfileId) return false
        if (nativeVideoId.requiresNativeAnimeProof(nativeMediaType, season, episode)) {
            if (!validNativeMalContext(nativeVideoId!!, season, episode, nativeMediaType)) return false
            val proof = animeMappings?.cachedProof(nativeVideoId)?.takeIf { it.hasTrustedTitleIdentity } ?: return false
            val origin = mutex.withLock { animeOrigins[url] } ?: return false
            if (origin.binding != binding || origin.proofKey != proof.cacheKey || !bindingIsCurrent(binding)) return false
        }
        return grants.permits(url, binding)
    }

    suspend fun resolve(stream: Stream, season: Int?, episode: Int?, allowFallback: Boolean = true,
        nativeVideoId: String? = null, allowAnimeMappingFetch: Boolean = true,
        nativeMediaType: String? = null, onProviderAttempt: (String) -> Unit = {}): DirectDebridResolveResult =
        withTimeoutOrNull(overallBudgetMillis.coerceAtLeast(1L)) {
            resolveWithinBudget(stream, season, episode, allowFallback, nativeVideoId,
                allowAnimeMappingFetch, nativeMediaType, onProviderAttempt)
        } ?: DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.TIMED_OUT))

    private suspend fun resolveWithinBudget(stream: Stream, season: Int?, episode: Int?, allowFallback: Boolean,
        nativeVideoId: String?, allowAnimeMappingFetch: Boolean,
        nativeMediaType: String?, onProviderAttempt: (String) -> Unit): DirectDebridResolveResult {
        val binding = playbackScope() ?: return DirectDebridResolveResult.MissingApiKey
        val settings = dataStore.settings.first()
        if (playbackScope() != binding) throw kotlinx.coroutines.CancellationException("The connected service changed.")
        val animeProof = if (nativeVideoId.requiresNativeAnimeProof(nativeMediaType, season, episode)) {
            if (!validNativeMalContext(nativeVideoId!!, season, episode, nativeMediaType) || !canSelectNativeAnimeFile(stream))
                return DirectDebridResolveResult.Stale
            (if (allowAnimeMappingFetch) animeMappings?.proof(nativeVideoId) { bindingIsCurrent(binding) }
                else animeMappings?.cachedProof(nativeVideoId))?.takeIf { it.hasTrustedTitleIdentity }
                ?: return DirectDebridResolveResult.Stale
        } else null
        if (animeProof != null && !stream.matchesNativeAnimeSource(animeProof)) return wrongDebridFile(null)
        if (!bindingIsCurrent(binding)) throw kotlinx.coroutines.CancellationException("The selected viewer changed.")
        val accounts = resolutionAccounts(stream, settings, allowFallback)
        if (accounts.isEmpty()) return DirectDebridResolveResult.Stale
        // The routing loop belongs to the caller, not the independent per-provider shared job.
        // Cancelling a selection cannot start a later service attempt in the background.
        val route = NativeDebridFallbackRouter.resolve(accounts,
            isCurrent = { bindingIsCurrent(binding) },
            onAttempt = onProviderAttempt) { account ->
            resolveAttempt(stream, season, episode, binding, account, animeProof)
        }
        val result = route.result
        if (result is DirectDebridResolveResult.Success) {
            val provider = route.providerId ?: return DirectDebridResolveResult.Error
            if (playbackScope() != binding) {
                Log.w("DebridResolve", "stage=final_grant result=scope_changed")
                return DirectDebridResolveResult.Error
            }
            if (!grants.issue(result.url, provider, binding)) {
                Log.w("DebridResolve", "stage=final_grant result=policy_rejected")
                return DirectDebridResolveResult.Error
            }
            rememberAnimeOrigin(result.url, binding, animeProof)
        }
        return result
    }

    private fun resolutionAccounts(stream: Stream, settings: com.nuvio.tv.domain.model.DebridSettings,
        allowFallback: Boolean): List<DebridServiceCredential> {
        if (!settings.canResolvePlayableLinks) return emptyList()
        if (NativeDebridFallbackRouter.neutralHashSource(stream)) return NativeDebridFallbackRouter.accounts(settings, allowFallback)
        // Provider-specific addon/hoster contracts and signed URLs are never rebound.
        if (stream.needsLocalDebridResolve()) return emptyList()
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return emptyList()
        val account = settings.activeResolverCredential?.takeIf { it.provider.id == providerId } ?: return emptyList()
        val hoster = providerId in setOf(DebridProviders.REAL_DEBRID_ID, DebridProviders.ALL_DEBRID_ID) &&
            stream.clientResolve?.type.let { it.equals("hoster", true) || it.equals("http", true) }
        return if (hoster || (stream.isDirectDebrid() && stream.getStreamUrl() == null)) listOf(account) else emptyList()
    }

    private suspend fun resolveAttempt(stream: Stream, season: Int?, episode: Int?,
        binding: ServicePlaybackGrantRegistry.Scope, account: DebridServiceCredential,
        animeProof: NativeAnimeEpisodeProof?): NativeDebridAttempt {
        val cooldownKey = "${binding.profileId}:${binding.revision}:${binding.selectionRevision}:${binding.accountRevision}:${account.provider.id}"
        val cacheKey = stream.directDebridResolveCacheKey(season, episode, account, animeProof)
            ?.let { "${binding.profileId}:${binding.revision}:$it" }
        if (cacheKey != null) getCachedResult(cacheKey, binding, account.provider.id)?.let { return NativeDebridAttempt(it) }
        mutex.withLock {
            val until = serviceCooldowns[cooldownKey]
            if (until != null && until > clock()) return NativeDebridAttempt(DirectDebridResolveResult.Failure(
                DirectDebridFailure(DirectDebridFailureReason.RATE_LIMITED, account.provider.id,
                    ((until - clock()).coerceAtLeast(1L) - 1L) / 1_000L + 1L)))
            serviceCooldowns.remove(cooldownKey)
        }
        if (cacheKey == null) return boundedResolveUncached(stream, season, episode, binding, account, animeProof)
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            boundedResolveUncached(stream, season, episode, binding, account, animeProof)
        }
        val activeResolve = try {
            mutex.withLock {
                getCachedResultLocked(cacheKey, binding, account.provider.id)?.let { return@withLock null to it }
                val entry = inFlightResolves.getOrPut(cacheKey) { SharedResolve(newResolve) }
                entry.waiters++
                entry to null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            newResolve.cancel()
            throw cancelled
        }
        activeResolve.second?.let { newResolve.cancel(); return NativeDebridAttempt(it) }
        val entry = activeResolve.first ?: return NativeDebridAttempt(DirectDebridResolveResult.Error)
        val deferred = entry.deferred
        if (deferred !== newResolve) newResolve.cancel() else deferred.start()
        return try {
            val outcome = deferred.await()
            if (outcome.result is DirectDebridResolveResult.Success &&
                bindingIsCurrent(binding)) {
                mutex.withLock { resolvedCache[cacheKey] = CachedDirectDebridResolve(outcome.result, binding,
                    System.currentTimeMillis(), account.provider.id) }
            }
            outcome
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    entry.waiters--
                    if (entry.waiters == 0 && inFlightResolves[cacheKey] === entry) {
                        inFlightResolves.remove(cacheKey)
                        // A cancelled selection leaves no independent network operation behind;
                        // another waiting selection still owns the same bounded job.
                        if (deferred.isActive) deferred.cancel()
                    }
                }
            }
        }
    }

    private suspend fun boundedResolveUncached(stream: Stream, season: Int?, episode: Int?,
        binding: ServicePlaybackGrantRegistry.Scope, account: DebridServiceCredential,
        animeProof: NativeAnimeEpisodeProof?): NativeDebridAttempt {
        val outcome = withTimeoutOrNull(serviceBudgetMillis.coerceAtLeast(1L)) {
            try {
                resolveUncached(stream, season, episode, binding, account, animeProof)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                NativeDebridAttempt(directDebridExceptionFailure(error, account.provider.id))
            }
        } ?: NativeDebridAttempt(DirectDebridResolveResult.Failure(
            DirectDebridFailure(DirectDebridFailureReason.TIMED_OUT, account.provider.id)))
        val failure = outcome.result as? DirectDebridResolveResult.Failure
        if (failure?.detail?.reason == DirectDebridFailureReason.RATE_LIMITED && bindingIsCurrent(binding)) {
            val seconds = failure.detail.retryAfterSeconds?.coerceAtLeast(1L) ?: 60L
            val delay = if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
            val now = clock()
            val deadline = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            mutex.withLock {
                val key = "${binding.profileId}:${binding.revision}:${binding.selectionRevision}:${binding.accountRevision}:${account.provider.id}"
                serviceCooldowns[key] = maxOf(serviceCooldowns[key] ?: Long.MIN_VALUE, deadline)
                while (serviceCooldowns.size > 128) serviceCooldowns.remove(serviceCooldowns.keys.first())
            }
        }
        return outcome
    }

    suspend fun cachedPlayableStream(stream: Stream, season: Int?, episode: Int?, nativeVideoId: String? = null, nativeMediaType: String? = null): Stream? {
        val binding = playbackScope() ?: return null
        val settings = dataStore.settings.first()
        if (playbackScope() != binding) return null
        val animeProof = if (nativeVideoId.requiresNativeAnimeProof(nativeMediaType, season, episode)) {
            if (!validNativeMalContext(nativeVideoId!!, season, episode, nativeMediaType) || !canSelectNativeAnimeFile(stream)) return null
            animeMappings?.cachedProof(nativeVideoId)?.takeIf { it.hasTrustedTitleIdentity } ?: return null // Cache-only metadata may never start a mapping request.
        } else null
        if (animeProof != null && !stream.matchesNativeAnimeSource(animeProof)) return null
        for (account in resolutionAccounts(stream, settings, true)) {
            if (!bindingIsCurrent(binding)) return null
            val key = stream.directDebridResolveCacheKey(season, episode, account, animeProof)
                ?.let { "${binding.profileId}:${binding.revision}:$it" } ?: continue
            val result = getCachedResult(key, binding, account.provider.id) ?: continue
            if (playbackScope() != binding || !grants.issue(result.url, account.provider.id, binding)) return null
            rememberAnimeOrigin(result.url, binding, animeProof)
            return stream.withResolvedDebridUrl(result)
        }
        return null
    }

    suspend fun resolveToPlayableStream(stream: Stream, season: Int?, episode: Int?, allowFallback: Boolean = true,
        nativeVideoId: String? = null, allowAnimeMappingFetch: Boolean = true,
        nativeMediaType: String? = null, onProviderAttempt: (String) -> Unit = {}): DirectDebridPlayableResult {
        if (!shouldResolveToPlayableStream(stream)) {
            val url = stream.getStreamUrl()
            if (nativeVideoId.requiresNativeAnimeProof(nativeMediaType, season, episode)) {
                if (!validNativeMalContext(nativeVideoId!!, season, episode, nativeMediaType)) return DirectDebridPlayableResult.Stale
                val proof = animeMappings?.cachedProof(nativeVideoId)?.takeIf { it.hasTrustedTitleIdentity } ?: return DirectDebridPlayableResult.Stale
                if (!stream.matchesNativeAnimeSource(proof)) return DirectDebridPlayableResult.Failure(
                    DirectDebridFailure(DirectDebridFailureReason.WRONG_EPISODE))
                return if (url != null && animeOriginAllows(url, proof)) DirectDebridPlayableResult.Success(stream)
                    else DirectDebridPlayableResult.Stale
            }
            return if (url != null && isPlaybackAuthorized(url)) DirectDebridPlayableResult.Success(stream)
                else DirectDebridPlayableResult.MissingApiKey
        }
        return when (val result = resolve(stream, season, episode, allowFallback, nativeVideoId, allowAnimeMappingFetch, nativeMediaType, onProviderAttempt)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            is DirectDebridResolveResult.Failure -> DirectDebridPlayableResult.Failure(result.detail)
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.NotCached -> DirectDebridPlayableResult.NotCached
            DirectDebridResolveResult.Stale -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }

    suspend fun shouldResolveToPlayableStream(stream: Stream): Boolean =
        resolutionAccounts(stream, dataStore.settings.first(), false).isNotEmpty()

    private suspend fun getCachedResult(key: String, binding: ServicePlaybackGrantRegistry.Scope, providerId: String) =
        mutex.withLock { getCachedResultLocked(key, binding, providerId) }

    private fun getCachedResultLocked(key: String, binding: ServicePlaybackGrantRegistry.Scope, providerId: String): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[key] ?: return null
        return if (cached.providerId == providerId && cached.isReusable(binding, System.currentTimeMillis())) cached.result
            else { resolvedCache.remove(key); null }
    }

    private suspend fun resolveUncached(stream: Stream, season: Int?, episode: Int?,
        binding: ServicePlaybackGrantRegistry.Scope, account: DebridServiceCredential,
        animeProof: NativeAnimeEpisodeProof?): NativeDebridAttempt {
        val operation = CapturedDebridOperation(account.provider.id, account.apiKey) {
            bindingIsCurrent(binding)
        }.withAnimeProof(animeProof)
        val rawResult = if (NativeDebridFallbackRouter.neutralHashSource(stream)) {
            resolveNeutralHash(stream, season, episode, account, operation, binding)
        } else resolveProvider(stream, season, episode, account.provider.id, operation)
        // A service response cannot authorize a URL that the final playback policy rejects.
        // Treat that service attempt as failed before caching; never upgrade HTTP to HTTPS.
        val result = if (rawResult is DirectDebridResolveResult.Success) {
            // A final service filename that contradicts the exact selected-file proof is terminal.
            if (animeProof != null && rawResult.filename?.let(animeProof::matchesFile) != true)
                return NativeDebridAttempt(wrongDebridFile(account.provider.id))
            if (com.nuvio.tv.core.streams.SyclopPlaybackPolicy.isHttps(rawResult.url))
                rawResult.copy(videoSize = rawResult.videoSize?.takeIf { it > 0L }) else DirectDebridResolveResult.Error
        } else rawResult
        return NativeDebridAttempt(result)
    }

    private suspend fun resolveProvider(stream: Stream, season: Int?, episode: Int?, providerId: String,
        operation: CapturedDebridOperation): DirectDebridResolveResult = when (providerId) {
        DebridProviders.TORBOX_ID -> torboxResolver.resolve(stream, season, episode, operation)
        DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(stream, season, episode, operation)
        DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(stream, season, episode, operation)
        DebridProviders.ALL_DEBRID_ID -> allDebridResolver.resolve(stream, season, episode, operation)
        else -> DirectDebridResolveResult.Error
    }

    private fun Stream.directDebridResolveCacheKey(season: Int?, episode: Int?, account: DebridServiceCredential,
        animeProof: NativeAnimeEpisodeProof?): String? {
        val resolve = clientResolve
        val identity = if (NativeDebridFallbackRouter.neutralHashSource(this)) getEffectiveInfoHash()
            else resolve?.infoHash ?: resolve?.magnetUri ?: getStreamUrl() ?: resolve?.torrentName ?: resolve?.filename
        identity ?: return null
        val identityKey = if (NativeDebridFallbackRouter.neutralHashSource(this) || resolve?.infoHash != null)
            identity.trim().lowercase() else identity.trim()
        return listOf(account.provider.id, account.apiKey.trim().stableFingerprint(), identityKey,
            getEffectiveFileIdx()?.toString().orEmpty(), (resolve?.filename ?: behaviorHints?.filename).orEmpty().trim(),
            (season ?: resolve?.season)?.toString().orEmpty(), (episode ?: resolve?.episode)?.toString().orEmpty(),
            animeProof?.let { it.cacheKey + ":" + nativeAnimeHintKey() } ?: "not-mal").joinToString("|")
    }

    private fun String?.requiresNativeAnimeProof(mediaType: String?, season: Int?, episode: Int?): Boolean {
        if (this?.startsWith("mal:", ignoreCase = true) != true) return false
        // A native MAL movie has no episode identity. Never infer this exemption from addon fields.
        return !(mediaType == "movie" && season == null && episode == null && Regex("^mal:[1-9][0-9]{0,9}$").matches(this))
    }

    private fun validNativeMalContext(videoId: String, season: Int?, episode: Int?, mediaType: String?): Boolean {
        if (mediaType == "movie") return false
        val identity = NativeAnimeEpisodeMappings.identity(videoId) ?: return false
        return (season == null || season == 1) && (episode == null || episode == identity.second)
    }

    /** Reject an unrelated declared release before its hash can reach any account API. */
    private fun Stream.matchesNativeAnimeSource(proof: NativeAnimeEpisodeProof): Boolean {
        val identity = proof.titleIdentity ?: return false
        val resolve = clientResolve
        if (fileIdx != null && resolve?.fileIdx != null && fileIdx != resolve.fileIdx) return false
        val filenameHints = listOfNotNull(behaviorHints?.filename, resolve?.filename, resolve?.stream?.raw?.filename)
        if (filenameHints.any { it.isBlank() || !proof.matchesFile(it) }) return false
        val basenames = filenameHints.map { it.trim().replace('\\', '/').substringAfterLast('/').lowercase(java.util.Locale.ROOT) }.distinct()
        if (basenames.size > 1) return false // One actual file cannot satisfy different explicit basenames.
        val names = listOfNotNull(title, behaviorHints?.filename, resolve?.filename, resolve?.torrentName,
            resolve?.stream?.raw?.filename, resolve?.stream?.raw?.torrentName).filter { it.isNotBlank() }
        return names.isNotEmpty() && names.all(identity::matchesRelease)
    }

    /** Every selector-relevant hint is bound, including absent versus present-empty values. */
    private fun Stream.nativeAnimeHintKey(): String {
        val resolve = clientResolve
        val hints = listOf(fileIdx?.toString(), resolve?.fileIdx?.toString(), title, behaviorHints?.filename,
            resolve?.filename, resolve?.torrentName, resolve?.stream?.raw?.filename, resolve?.stream?.raw?.torrentName)
        val encoded = hints.joinToString("") { value -> if (value == null) "-1:" else "${value.length}:$value" }
        // Preserve every UTF-16 code unit; a replacement encoder must not collapse malformed
        // provider strings into a different explicit filename's cache identity.
        val bytes = java.nio.ByteBuffer.allocate(encoded.length * 2)
        for (character in encoded) bytes.putChar(character)
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }

    private fun canSelectNativeAnimeFile(stream: Stream): Boolean =
        stream.getEffectiveInfoHash()?.matches(Regex("[0-9a-fA-F]{40}|[a-zA-Z2-7]{32}")) == true &&
            stream.getStreamUrl().let { it == null || it.startsWith("magnet:", true) }

    private suspend fun rememberAnimeOrigin(url: String, binding: ServicePlaybackGrantRegistry.Scope, proof: NativeAnimeEpisodeProof?) {
        if (proof == null) return
        mutex.withLock {
            animeOrigins[url] = AnimeOrigin(binding, proof.cacheKey)
            while (animeOrigins.size > 256) animeOrigins.remove(animeOrigins.keys.first())
        }
    }

    private suspend fun animeOriginAllows(url: String, proof: NativeAnimeEpisodeProof): Boolean {
        val binding = playbackScope() ?: return false
        val origin = mutex.withLock { animeOrigins[url] } ?: return false
        return origin.binding == binding && origin.proofKey == proof.cacheKey && bindingIsCurrent(binding) && grants.permits(url, binding)
    }

    private suspend fun resolveNeutralHash(stream: Stream, season: Int?, episode: Int?, account: DebridServiceCredential,
        operation: CapturedDebridOperation, binding: ServicePlaybackGrantRegistry.Scope): DirectDebridResolveResult {
        val hash = canonicalAvailabilityHash(stream.getEffectiveInfoHash()) ?: return DirectDebridResolveResult.Stale
        val availability = stream.nativeAvailabilityFor(account.provider.id,
            com.nuvio.tv.domain.model.StreamAvailabilityScope(binding.profileId, binding.selectionRevision, binding.accountRevision))
        if (availability?.state == StreamDebridCacheState.NOT_CACHED) return DirectDebridResolveResult.NotCached
        if (availability?.state != StreamDebridCacheState.CACHED && account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck)) {
            if (scopedDebridRequest(operation) { localDebridService.isCached(account, hash) } == false) return DirectDebridResolveResult.NotCached
        }
        // Rebuild only the validated neutral hash. Do not forward addon headers, hoster URLs,
        // signed service URLs, tracker passkeys, serviceIndex or cached claims to another service.
        val resolveStream = stream.copy(url = null, externalUrl = null, sources = null, debridCacheStatus = availability,
            nativeDebridAvailability = emptyList(),
            behaviorHints = stream.behaviorHints?.copy(proxyHeaders = null),
            clientResolve = StreamClientResolve(type = "torrent", infoHash = hash, fileIdx = stream.getEffectiveFileIdx(),
                magnetUri = "magnet:?xt=urn:btih:$hash", sources = null, torrentName = null,
                filename = stream.behaviorHints?.filename, mediaType = null, mediaId = null, mediaOnlyId = null,
                title = stream.title ?: stream.name, season = season, episode = episode, service = account.provider.id,
                serviceIndex = null, serviceExtension = null, isCached = availability?.state == StreamDebridCacheState.CACHED))
        return resolveProvider(resolveStream, season, episode, account.provider.id, operation)
    }

}

private const val DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

internal data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val origin: ServicePlaybackGrantRegistry.Scope,
    val cachedAtMs: Long,
    val providerId: String? = null
) {
    fun isReusable(binding: ServicePlaybackGrantRegistry.Scope, nowMs: Long): Boolean =
        origin == binding && nowMs - cachedAtMs in 0..DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS
}

sealed class DirectDebridPlayableResult {
    data class Success(val stream: Stream) : DirectDebridPlayableResult()
    data class Failure(val detail: DirectDebridFailure) : DirectDebridPlayableResult()
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
        debridCacheStatus = null,
        nativeDebridAvailability = emptyList(),
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
            videoSize = result.videoSize
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
