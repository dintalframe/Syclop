package com.nuvio.tv.core.anime

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mal.MalMetadataService
import com.nuvio.tv.data.mal.MalSourceMetadata
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

/** Public ID metadata only. Isolated from every account API, cookie jar and logging interceptor. */
@Singleton
class NativeAnimeEpisodeMappings internal constructor(
    client: OkHttpClient,
    private val clock: () -> Long,
    private val origin: () -> Pair<Int, Long> = { 0 to 0L },
    private val originChanges: Flow<*> = emptyFlow<Unit>(),
    timeoutMillis: Long = 8_000L,
    private val malMetadata: (suspend (Long) -> MalSourceMetadata)? = null
) {
    @Inject constructor(profiles: ProfileManager, mal: MalMetadataService) :
        this(OkHttpClient(), { System.nanoTime() / 1_000_000L },
            { profiles.activeProfileId.value to profiles.activeProfileSelectionRevision }, profiles.activeProfileId,
            malMetadata = mal::sourceMetadata)

    private val transport = client.newBuilder().authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(timeoutMillis.coerceIn(100L, 8_000L), TimeUnit.MILLISECONDS).build()
    private data class Cached(val value: NativeAnimeMappingRecord?, val expires: Long,
        val missedEpisode: Int? = null, val retryAt: Long = 0L)
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 64
    }
    private val requests = Mutex()

    /** No network from sorting, metadata prefetch or a plain cache read. */
    internal fun cachedProof(nativeVideoId: String): NativeAnimeEpisodeProof? {
        val id = identity(nativeVideoId) ?: kitsuIdentity(nativeVideoId) ?: return null
        val key = nativeVideoId.substringBeforeLast(':')
        return synchronized(cache) { cache[key]?.takeIf { it.expires > clock() }?.value?.episodes?.get(id.second) }
    }

    /** Called only when opening a native title's Details without a catalog episode list. */
    internal suspend fun confirmedEpisodeNumbers(contentId: String): List<Int> {
        val firstVideoId = "$contentId:1"
        if (identity(firstVideoId) == null) return emptyList()
        val selected = origin()
        proof(firstVideoId)
        if (origin() != selected) throw CancellationException("The selected viewer changed.")
        return cachedEpisodeNumbers(contentId)
    }

    internal fun cachedEpisodeNumbers(contentId: String): List<Int> {
        if (identity("$contentId:1") == null) return emptyList()
        return synchronized(cache) {
            cache[contentId]?.takeIf { it.expires > clock() }?.value?.episodes?.keys?.sorted().orEmpty()
        }
    }

    /** The primary mapping and optional exact-ID enrichment share a ten-second bound. */
    internal suspend fun proof(nativeVideoId: String, isCurrent: () -> Boolean = { true }): NativeAnimeEpisodeProof? {
        val kitsu = kitsuIdentity(nativeVideoId)
        val id = kitsu ?: identity(nativeVideoId) ?: return null
        val key = nativeVideoId.substringBeforeLast(':')
        val selected = origin()
        fun current() = origin() == selected && isCurrent()
        if (!current()) throw CancellationException("The selected viewer changed.")
        return coroutineScope {
            val lookup = async {
                requests.withLock {
                    if (!current()) throw CancellationException("The selected viewer changed.")
                    val startedAt = clock()
                    val cached = synchronized(cache) { cache[key]?.takeIf { it.expires > startedAt } }
                    val cachedEpisode = cached?.value?.episodes?.get(id.second)
                    if (cached != null) {
                        if (cachedEpisode != null && (kitsu != null || !cachedEpisode.catalogOnly || cached.retryAt > startedAt)) {
                            return@withLock cachedEpisode
                        }
                        if (cached.value == null || (cached.missedEpisode == id.second && cached.retryAt > startedAt)) {
                            return@withLock null
                        }
                    }
                    if (kitsu != null) {
                        val record = withTimeoutOrNull(10_000L) { fetchDirectKitsu(kitsu.first, kitsu.second, ::current) }
                        if (!current()) throw CancellationException("The selected viewer changed.")
                        val now = clock()
                        val previous = cached?.takeIf { it.expires > now && it.value != null }
                        val saved = record ?: previous?.value
                        val selectedProof = saved?.episodes?.get(kitsu.second)
                        val expires = if (record == null && previous != null) previous.expires
                            else deadline(now, if (record == null) NEGATIVE_TTL else POSITIVE_TTL)
                        synchronized(cache) {
                            cache[key] = Cached(saved, expires, kitsu.second.takeIf { selectedProof == null }, deadline(now, NEGATIVE_TTL))
                        }
                        return@withLock selectedProof
                    }
                    var mapped: NativeAnimeMappingRecord? = null
                    val record = withTimeoutOrNull(10_000L) {
                        val document = fetchDocument("https://api.ani.zip/mappings?mal_id=${id.first}", ::current)
                        val base = document?.let { NativeAnimeMappingCodec.parse(it, id.first) }.also { mapped = it }
                        if (base?.episodes?.containsKey(id.second) != true) {
                            // A still-valid catalog proof only needs a primary mapping recheck.
                            if (cachedEpisode != null && cached.expires > clock()) return@withTimeoutOrNull base
                            val link = document?.let { NativeAnimeEpisodeProof.kitsuLink(it, id.first) }
                            val kitsu = link?.let { fetchKitsu(it, id.first, ::current) }
                            val source = fetchMal(id.first)
                            val catalog = if (link != null && kitsu != null && kitsu.agreesWith(source, id.first)) {
                                NativeAnimeEpisodeProof.fromKitsu(id.first, link.kitsuId, kitsu)
                            } else null
                            val local = if (catalog?.episodes?.containsKey(id.second) == true) null
                                else source?.let { NativeAnimeEpisodeProof.fromMal(id.first, id.second, it) }
                            val episodes = catalog?.episodes.orEmpty() + base?.episodes.orEmpty() +
                                listOfNotNull(local).associateBy { it.malEpisode }
                            return@withTimeoutOrNull episodes.takeIf { it.isNotEmpty() }?.let {
                                NativeAnimeMappingRecord(id.first, it, base?.mappedParentImdbId)
                            }
                        }
                        val imdb = base.mappedParentImdbId ?: return@withTimeoutOrNull base
                        val parent = withTimeoutOrNull(2_000L) {
                            fetchDocument("https://v3-cinemeta.strem.io/meta/series/$imdb.json", ::current)
                        }
                        parent?.let(base::withVerifiedParentMetadata) ?: base
                    } ?: mapped // Optional parent metadata cannot remove an already validated mapping.
                    if (!current()) throw CancellationException("The selected viewer changed.")
                    val now = clock()
                    val previous = cached?.takeIf { it.expires > now }?.value
                    val saved = when {
                        previous == null -> record
                        record == null -> previous
                        else -> {
                            val additions = record.episodes.filter { (number, proof) ->
                                val existing = previous.episodes[number]
                                existing == null || (existing.catalogOnly && !proof.catalogOnly)
                            }
                            NativeAnimeMappingRecord(id.first, previous.episodes + additions,
                                previous.mappedParentImdbId ?: record.mappedParentImdbId)
                        }
                    }
                    val selectedProof = saved?.episodes?.get(id.second)
                    val ttl = when {
                        record == null -> NEGATIVE_TTL
                        selectedProof?.catalogOnly != true && record.mappedParentImdbId != null &&
                            record.episodes.values.any { !it.catalogOnly && it.verifiedParentImdbId == null } ->
                            PARENT_RETRY_TTL
                        else -> POSITIVE_TTL
                    }
                    val keptCachedEpisode = cachedEpisode != null && cachedEpisode === selectedProof
                    val expires = if (cached != null && previous != null && (record == null || keptCachedEpisode)) {
                        cached.expires
                    } else deadline(now, ttl)
                    synchronized(cache) {
                        cache[key] = Cached(saved, expires, id.second.takeIf { selectedProof == null },
                            deadline(now, NEGATIVE_TTL))
                    }
                    selectedProof
                }
            }
            val watcher = launch {
                originChanges.collect {
                    if (!current()) lookup.cancel(CancellationException("The selected viewer changed."))
                }
            }
            try {
                val result = lookup.await()
                if (!current()) throw CancellationException("The selected viewer changed.")
                result
            } finally { watcher.cancel() }
        }
    }

    private suspend fun fetchDirectKitsu(kitsuId: Long, number: Int, isCurrent: () -> Boolean): NativeAnimeMappingRecord? {
        val document = fetchKitsuDocument(kitsuId, isCurrent, includeEpisodeIdentity = true) ?: return null
        val linked = NativeAnimeEpisodeProof.kitsuLinks(document, kitsuId) ?: return null
        if (number !in linked.anime.episodes) return null
        val query = linked.malId?.let { "mal_id=$it" } ?: "anilist_id=${linked.aniListId}"
        val response = fetchDocument("https://api.ani.zip/mappings?$query", isCurrent) ?: return null
        return NativeAnimeEpisodeProof.fromCorroboratedKitsu(response, kitsuId, linked)
    }

    private suspend fun fetchKitsu(link: KitsuLink, malId: Long, isCurrent: () -> Boolean): KitsuAnimeEpisodes? =
        fetchKitsuDocument(link.kitsuId, isCurrent)?.let { NativeAnimeEpisodeProof.kitsuEpisodes(it, link, malId) }

    private suspend fun fetchKitsuDocument(
        kitsuId: Long,
        isCurrent: () -> Boolean,
        includeEpisodeIdentity: Boolean = false
    ): String? =
        withTimeoutOrNull(3_000L) {
            val episodeFields = "number,seasonNumber,relativeNumber" +
                if (includeEpisodeIdentity) ",canonicalTitle,airdate" else ""
            fetchDocument("https://kitsu.io/api/edge/anime/$kitsuId?include=episodes,mappings" +
                "&fields%5Banime%5D=canonicalTitle,startDate,subtype,episodes,mappings" +
                "&fields%5Bepisodes%5D=$episodeFields" +
                "&fields%5Bmappings%5D=externalSite,externalId", isCurrent, "application/vnd.api+json")
        }

    private suspend fun fetchMal(malId: Long): MalSourceMetadata? = withTimeoutOrNull(2_000L) {
        try {
            malMetadata?.invoke(malId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    private fun KitsuAnimeEpisodes.agreesWith(source: MalSourceMetadata?, malId: Long): Boolean {
        if (source == null) return true
        if (source.malId != malId || (source.year != null && source.year != startYear)) return false
        val identity = NativeAnimeTitleIdentity.create(malId, source.title, source.alternativeTitles,
            preserveEdition = true) ?: return false
        return identity.matchRelease(canonicalTitle, complete = true).accepted
    }

    private suspend fun fetchDocument(url: String, isCurrent: () -> Boolean, accept: String = "application/json"): String? =
        suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("Accept", accept).header("Accept-Encoding", "identity").get().build()
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val record = try {
                    response.use {
                        val body = it.body
                        if (!continuation.isActive || !isCurrent() || it.code != 200 || body == null ||
                            body.contentLength() > MAX_BODY || it.header("Content-Encoding").let { encoding -> encoding != null && encoding != "identity" } ||
                            body.contentType()?.subtype?.let { type -> type == "json" || type.endsWith("+json") } != true) return@use null
                        val source = body.source()
                        source.request(MAX_BODY + 1L)
                        if (!continuation.isActive || !isCurrent() || source.buffer.size > MAX_BODY) return@use null
                        val bytes = source.buffer.readByteArray()
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                        text
                    }
                } catch (_: Exception) { null } // Never surface response text, URLs or arbitrary exception messages.
                if (continuation.isActive) continuation.resume(record)
            }
        })
    }

    internal companion object {
        private const val MAX_BODY = 2L * 1024L * 1024L
        private const val POSITIVE_TTL = 24L * 60L * 60L * 1000L
        private const val NEGATIVE_TTL = 5L * 60L * 1000L
        private const val PARENT_RETRY_TTL = 5L * 60L * 1000L
        private val ID = Regex("^mal:([1-9][0-9]{0,9}):([1-9][0-9]{0,3})$")
        private val KITSU_ID = Regex("^kitsu:([1-9][0-9]{0,9}):([1-9][0-9]{0,3})$")
        internal fun identity(value: String): Pair<Long, Int>? = episodeIdentity(value, ID)
        internal fun kitsuIdentity(value: String): Pair<Long, Int>? = episodeIdentity(value, KITSU_ID)
            ?.takeIf { it.first <= Int.MAX_VALUE }

        private fun episodeIdentity(value: String, pattern: Regex): Pair<Long, Int>? {
            val match = pattern.matchEntire(value) ?: return null
            val id = match.groupValues[1].toLongOrNull() ?: return null
            val episode = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..4000 } ?: return null
            return id to episode
        }
        private fun deadline(now: Long, ttl: Long): Long = if (now > Long.MAX_VALUE - ttl) Long.MAX_VALUE else now + ttl
    }
}
