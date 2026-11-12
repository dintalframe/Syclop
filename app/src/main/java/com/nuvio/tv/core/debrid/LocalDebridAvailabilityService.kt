package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded, account-scoped enrichment; unknown never means unavailable. */
@Singleton
class LocalDebridAvailabilityService internal constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService,
    private val clock: () -> Long,
    private val accountLibrary: NativeDebridAccountLibrary? = null,
    private val animeMappings: NativeAnimeEpisodeMappings? = null
) {
    @Inject constructor(dataStore: DebridSettingsDataStore, service: LocalDebridService,
        accountLibrary: NativeDebridAccountLibrary, animeMappings: NativeAnimeEpisodeMappings) :
        this(dataStore, service, System::currentTimeMillis, accountLibrary, animeMappings)

    private data class Snapshot(val scope: StreamAvailabilityScope, val settings: DebridSettings)
    private data class Key(val scope: StreamAvailabilityScope, val provider: String, val hash: String)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Key, StreamDebridCacheStatus>()
    private data class LibraryObservation(val item: AccountLibraryItem?, val checkedAt: Long) {
        fun fresh(now: Long) = now - checkedAt in 0 until if (item == null) 5_000L else 30_000L
    }

    private val libraryObservations = LinkedHashMap<Key, LibraryObservation>()
    private val cooldowns = LinkedHashMap<Pair<StreamAvailabilityScope, String>, Long>()

    suspend fun markChecking(groups: List<AddonStreams>) = annotateFromMemory(groups, null, checking = true)
    internal suspend fun markChecking(groups: List<AddonStreams>, expectedSettings: DebridSettings,
        request: NativeDebridLibraryRequest? = null) = annotateFromMemory(groups, expectedSettings, checking = true, request)

    internal suspend fun annotateFromMemory(groups: List<AddonStreams>, expectedSettings: DebridSettings,
        request: NativeDebridLibraryRequest? = null) = annotateFromMemory(groups, expectedSettings, checking = false, request)

    private suspend fun annotateFromMemory(groups: List<AddonStreams>, expected: DebridSettings?,
        checking: Boolean, request: NativeDebridLibraryRequest? = null): List<AddonStreams> {
        val snapshot = requiredSnapshot(expected) ?: return groups.clearNativeAvailability()
        val updated = annotate(groups, snapshot, emptyMap(), checking, request)
        if (expected != null && !current(snapshot)) throw CancellationException("The connected service changed.")
        return if (current(snapshot)) updated else groups.clearNativeAvailability()
    }

    suspend fun annotateCachedAvailability(groups: List<AddonStreams>) = annotateCachedAvailabilityInternal(groups, null)
    internal suspend fun annotateCachedAvailability(groups: List<AddonStreams>, expectedSettings: DebridSettings,
        request: NativeDebridLibraryRequest? = null,
        onBulkReady: suspend (List<AddonStreams>) -> Unit = {}) =
        annotateCachedAvailabilityInternal(groups, expectedSettings, request, onBulkReady)

    private suspend fun annotateCachedAvailabilityInternal(groups: List<AddonStreams>, expected: DebridSettings?,
        request: NativeDebridLibraryRequest? = null, onBulkReady: suspend (List<AddonStreams>) -> Unit = {}): List<AddonStreams> {
        val snapshot = requiredSnapshot(expected) ?: return groups.clearNativeAvailability()
        val requests = groups.flatMap { group -> group.streams.flatMap { stream ->
            val hash = stream.localAvailabilityHash() ?: return@flatMap emptyList()
            availabilityAccounts(stream, snapshot.settings).filter { it.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
                .map { it to hash }
        } }.groupBy({ it.first }, { it.second })
        val fresh = coroutineScope {
            val libraryCheck = async {
                if (request != null) checkAccountLibraries(groups, snapshot, request)
            }
            val result = requests.entries.take(2).map { (account, allHashes) -> async {
                val hashes = allHashes.distinct().take(300)
                val existing = mutex.withLock {
                    cache.keys.removeAll { it.scope != snapshot.scope }
                    hashes.mapNotNull { hash -> cache[Key(snapshot.scope, account.provider.id, hash)]
                        ?.takeIf { it.isFreshNativeAvailability(clock()) }?.let { hash to it } }.toMap()
                }
                val pending = hashes.filterNot(existing::containsKey)
                val cooldownKey = snapshot.scope to account.provider.id
                // Retain completed chunks if a later chunk reaches this operation's deadline.
                val found = LinkedHashMap<String, StreamDebridCacheStatus>()
                withTimeoutOrNull(5_000L) {
                    for (chunk in pending.chunked(64)) {
                        if (!current(snapshot)) return@withTimeoutOrNull
                        // Another collector may receive a rate limit while this one's previous
                        // chunk is in flight. Recheck before every subsequent dispatch.
                        if (mutex.withLock { (cooldowns[cooldownKey] ?: 0L) > clock() }) return@withTimeoutOrNull
                        val response = try { localDebridService.checkCached(account, chunk) }
                        catch (rejected: DirectDebridHttpException) {
                            if (rejected.status == 429 && current(snapshot)) coolDown(cooldownKey, rejected.retryAfterSeconds)
                            // No additional chunk request after authentication/rate rejection.
                            // Completed chunks survive; all other observations remain unknown.
                            return@withTimeoutOrNull
                        }
                        if (!current(snapshot)) return@withTimeoutOrNull
                        val now = clock()
                        chunk.forEach { hash ->
                            val item = response?.get(hash)
                            found[hash] = status(account, hash, snapshot.scope, when {
                                response == null -> StreamDebridCacheState.UNKNOWN
                                item == null -> StreamDebridCacheState.NOT_CACHED
                                else -> StreamDebridCacheState.CACHED
                            }, now, item)
                        }
                    }
                }
                val now = clock()
                val combined = existing + pending.associateWith { hash -> found[hash] ?:
                    status(account, hash, snapshot.scope, StreamDebridCacheState.UNKNOWN, now) }
                if (current(snapshot)) mutex.withLock {
                    combined.forEach { (hash, value) -> cache[Key(snapshot.scope, account.provider.id, hash)] = value }
                    while (cache.size > 512) cache.remove(cache.keys.first())
                }
                account.provider.id to combined
            } }.awaitAll().toMap()
            if (!libraryCheck.isCompleted && result.values.any { states ->
                    states.values.any { it.state == StreamDebridCacheState.CACHED }
                } && current(snapshot)) {
                val partial = annotate(groups, snapshot, result, checking = false, request)
                if (current(snapshot)) onBulkReady(partial)
            }
            libraryCheck.await()
            result
        }
        if (!current(snapshot)) {
            if (expected != null) throw CancellationException("The connected service changed.")
            return groups.clearNativeAvailability()
        }
        val updated = annotate(groups, snapshot, fresh, checking = false, request)
        if (expected != null && !current(snapshot)) throw CancellationException("The connected service changed.")
        return if (current(snapshot)) updated else groups.clearNativeAvailability()
    }

    private suspend fun checkAccountLibraries(groups: List<AddonStreams>, snapshot: Snapshot,
        request: NativeDebridLibraryRequest) {
        val library = accountLibrary ?: return
        val requests = groups.flatMap { group -> group.streams.flatMap { stream ->
            val hash = stream.localAvailabilityHash() ?: return@flatMap emptyList()
            if (!eligibleForLibrary(stream, request)) return@flatMap emptyList()
            availabilityAccounts(stream, snapshot.settings).filter { it.provider.supportsAccountLibraryLookup() }
                .map { it to hash }
        } }.groupBy({ it.first }, { it.second })
        coroutineScope {
            requests.map { (account, hashes) -> async {
                val providerId = account.provider.id
                val cooldownKey = snapshot.scope to providerId
                val pending = mutex.withLock {
                    libraryObservations.keys.removeAll { it.scope != snapshot.scope }
                    if ((cooldowns[cooldownKey] ?: 0L) > clock()) return@withLock emptySet()
                    hashes.distinct().take(300).filterTo(linkedSetOf()) {
                        libraryObservations[Key(snapshot.scope, providerId, it)]?.fresh(clock()) != true
                    }
                }
                if (pending.isEmpty() || !current(snapshot)) return@async
                val operation = CapturedDebridOperation(providerId, account.apiKey, snapshot.scope) {
                    currentScope() == snapshot.scope
                }
                val items = try {
                    withTimeoutOrNull(3_500L) { library.find(account, snapshot.scope, pending, operation) }.orEmpty()
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (current(snapshot)) throw error
                        return@async
                    }
                    val failure = directDebridExceptionFailure(error, providerId).detail
                    if (failure.reason == DirectDebridFailureReason.RATE_LIMITED && current(snapshot)) {
                        coolDown(cooldownKey, failure.retryAfterSeconds)
                    }
                    emptyMap()
                }
                if (current(snapshot)) mutex.withLock {
                    val now = clock()
                    for (hash in pending) {
                        val key = Key(snapshot.scope, providerId, hash)
                        // A slow miss cannot replace a successful concurrent observation.
                        if (items[hash] != null || libraryObservations[key]?.fresh(now) != true) {
                            libraryObservations[key] = LibraryObservation(items[hash], now)
                        }
                    }
                    while (libraryObservations.size > 640) libraryObservations.remove(libraryObservations.keys.first())
                }
            } }.awaitAll()
        }
    }

    private suspend fun coolDown(key: Pair<StreamAvailabilityScope, String>, retryAfterSeconds: Long?) {
        val seconds = retryAfterSeconds?.coerceAtLeast(1L) ?: 60L
        val delay = if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
        val now = clock()
        val until = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
        mutex.withLock {
            cooldowns[key] = maxOf(cooldowns[key] ?: Long.MIN_VALUE, until)
            while (cooldowns.size > 128) cooldowns.remove(cooldowns.keys.first())
        }
    }

    private fun eligibleForLibrary(stream: Stream, request: NativeDebridLibraryRequest): Boolean {
        if (!NativeDebridFallbackRouter.neutralHashSource(stream)) return false
        val broadcast = stream.nativeTurkishBroadcastEpisode
        if (broadcast != null && !broadcast.matchesContext(request.videoId, request.season, request.episode, request.type))
            return false
        if (request.needsAnimeProof(stream)) return request.animeProof(stream, animeMappings) != null
        if (request.type.equals("movie", true)) return request.season == null && request.episode == null
        return EpisodeSourcePolicy.isEpisodeRequest(request.type, request.episode) &&
            request.season != null && request.season >= 0 && (request.episode ?: 0) > 0 && !EpisodeSourcePolicy.isReportedPack(stream)
    }

    suspend fun isCached(hash: String): Boolean? {
        val snapshot = snapshot(null) ?: return null
        val account = snapshot.settings.activeResolverCredential
            ?.takeIf { it.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) } ?: return null
        val canonical = canonicalAvailabilityHash(hash) ?: return null
        val result = withTimeoutOrNull(5_000L) {
            try { localDebridService.isCached(account, canonical) }
            catch (_: DirectDebridHttpException) { null }
        }
        return result.takeIf { current(snapshot) }
    }

    private suspend fun requiredSnapshot(expected: DebridSettings?): Snapshot? {
        val value = snapshot(expected)
        if (value == null && expected?.canResolvePlayableLinks == true)
            throw CancellationException("The connected service changed.")
        return value
    }

    private suspend fun snapshot(expected: DebridSettings?): Snapshot? {
        val scope = currentScope() ?: return null
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks || expected != null && settings != expected) return null
        for (provider in DebridProviders.all()) {
            if (dataStore.credentialForProfile(scope.profileId, provider.id).trim() != settings.apiKeyFor(provider.id).trim()) return null
        }
        return Snapshot(scope, settings).takeIf { current(it) }
    }

    private fun currentScope(): StreamAvailabilityScope? = dataStore.activeCredentialRevision.takeIf { it >= 0L }?.let {
        StreamAvailabilityScope(dataStore.activeProfileId.value, dataStore.activeProfileSelectionRevision, it)
    }
    private suspend fun current(snapshot: Snapshot): Boolean = currentScope() == snapshot.scope &&
        dataStore.settings.first() == snapshot.settings && currentScope() == snapshot.scope

    private suspend fun annotate(groups: List<AddonStreams>, snapshot: Snapshot,
        responses: Map<String, Map<String, StreamDebridCacheStatus>>, checking: Boolean,
        request: NativeDebridLibraryRequest?): List<AddonStreams> =
        groups.map { group -> group.copy(streams = group.streams.map { stream ->
            val hash = stream.localAvailabilityHash()
            val accounts = availabilityAccounts(stream, snapshot.settings)
            if (hash == null || accounts.isEmpty()) stream.copy(nativeDebridAvailability = emptyList(),
                debridCacheStatus = stream.debridCacheStatus?.takeIf { it.nativeScope == null })
            else {
                val now = clock()
                val observations = accounts.map { account ->
                    val known = responses[account.provider.id]?.get(hash) ?: mutex.withLock {
                        cache[Key(snapshot.scope, account.provider.id, hash)]?.takeIf { it.isFreshNativeAvailability(now) }
                    }
                    val library = if (request != null && account.provider.supportsAccountLibraryLookup() &&
                        eligibleForLibrary(stream, request)) mutex.withLock {
                        libraryObservations[Key(snapshot.scope, account.provider.id, hash)]?.takeIf { it.fresh(now) }
                    } else null
                    val file = if (request == null) null else library?.item?.exactFile(stream, request.season, request.episode,
                        request.animeProof(stream, animeMappings))
                    when {
                        file != null && library != null -> status(account, hash, snapshot.scope, StreamDebridCacheState.CACHED,
                            library.checkedAt, LocalDebridCachedItem(file.path.substringAfterLast('/'), file.sizeBytes))
                            .copy(libraryFile = file)
                        known != null -> known
                        else -> status(account, hash, snapshot.scope,
                            if (checking && account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck))
                                StreamDebridCacheState.CHECKING else StreamDebridCacheState.UNKNOWN, now)
                    }
                }
                val selected = observations.firstOrNull { it.libraryFile != null }
                    ?: observations.firstOrNull { it.state == StreamDebridCacheState.CACHED }
                    ?: observations.firstOrNull { it.state == StreamDebridCacheState.CHECKING }
                        .takeIf { request != null && accounts.any { it.provider.supportsAccountLibraryLookup() } }
                    ?: observations.firstOrNull { it.state == StreamDebridCacheState.UNKNOWN }
                        .takeIf { request != null && accounts.any { it.provider.supportsAccountLibraryLookup() } }
                    // An unchecked fallback may still resolve this source. One negative answer
                    // cannot suppress native resolution through another connected account.
                    ?: observations.firstOrNull().takeUnless {
                        accounts.any { !it.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
                    }?.let { primary -> if (observations.any { it.state != StreamDebridCacheState.NOT_CACHED } &&
                        primary.state == StreamDebridCacheState.NOT_CACHED) primary.copy(state = StreamDebridCacheState.UNKNOWN) else primary }
                stream.copy(debridCacheStatus = selected, nativeDebridAvailability = observations)
            }
        }) }

    private fun status(account: DebridServiceCredential, hash: String, scope: StreamAvailabilityScope,
        state: StreamDebridCacheState, now: Long, item: LocalDebridCachedItem? = null): StreamDebridCacheStatus {
        val ttl = when (state) { StreamDebridCacheState.CACHED -> 30_000L
            StreamDebridCacheState.NOT_CACHED -> 10_000L; else -> 5_000L }
        return StreamDebridCacheStatus(account.provider.id, account.provider.displayName, state,
            item?.name, item?.size, scope, hash, now, now + ttl)
    }
}

fun Stream.localAvailabilityHash(): String? =
    getEffectiveInfoHash()?.takeIf { needsLocalDebridResolve() }?.let(::canonicalAvailabilityHash)

private fun List<AddonStreams>.clearNativeAvailability(): List<AddonStreams> = map { group ->
    group.copy(streams = group.streams.map { stream -> stream.copy(nativeDebridAvailability = emptyList(),
        debridCacheStatus = stream.debridCacheStatus?.takeIf { it.nativeScope == null }) })
}
