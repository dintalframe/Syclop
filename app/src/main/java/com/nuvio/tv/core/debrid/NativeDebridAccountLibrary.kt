package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.anime.NativeAnimeEpisodeProof
import com.nuvio.tv.core.streams.SyclopPlaybackPolicy
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridFileNodeDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamDebridLibraryFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response

internal data class NativeDebridLibraryRequest(val type: String, val videoId: String, val season: Int?, val episode: Int?)

internal data class AccountLibraryFile(val path: String, val size: Long?, val providerFileId: String?, val available: Boolean)

internal data class AccountLibraryItem(val id: String, val files: List<AccountLibraryFile>) {
    fun exactFile(stream: Stream, season: Int?, episode: Int?, animeProof: NativeAnimeEpisodeProof?): StreamDebridLibraryFile? {
        val hints = listOfNotNull(stream.behaviorHints?.filename, stream.clientResolve?.filename,
            stream.clientResolve?.stream?.raw?.filename)
        val playable = files.filter { it.path.hasDebridVideoExtension() }
        var explicitFile: AccountLibraryFile? = null
        for (hint in hints.distinct()) {
            val normalized = hint.libraryPath() ?: return null
            val match = files.singleOrNull { file ->
                if ('/' in normalized) file.path.equals(normalized, true) || file.path.endsWith("/$normalized", true)
                else file.path.substringAfterLast('/').equals(normalized, true)
            } ?: return null
            if (explicitFile != null && explicitFile != match) return null
            explicitFile = match
        }
        val index = stream.getEffectiveFileIdx()
        val hasProviderIndices = files.any { it.providerFileId != null }
        if (index != null && season == null && episode == null && animeProof == null) {
            if (hasProviderIndices) {
                val indexed = files.getOrNull(index) ?: return null
                if (explicitFile != null && explicitFile != indexed) return null
                explicitFile = indexed
            } else if (explicitFile == null && (index != 0 || files.size != 1)) return null
        }
        val broadcast = stream.nativeTurkishBroadcastEpisode
        val matches = playable.filter {
            when {
                animeProof != null -> animeProof.matchesFile(it.path)
                season != null && episode != null && season >= 0 && episode > 0 ->
                    it.path.matchesSingleEpisode(season, episode, broadcast)
                season != null || episode != null || broadcast != null -> false
                else -> true
            }
        }
        val selected = (if (explicitFile != null) explicitFile.takeIf { it in matches } else matches.singleOrNull())
            ?.takeIf { it.available } ?: return null
        if (animeProof != null && index != null && hasProviderIndices && files.getOrNull(index) != selected) return null
        return StreamDebridLibraryFile(id, selected.providerFileId, selected.path, selected.size)
    }
}

internal fun DebridProvider.supportsAccountLibraryLookup(): Boolean =
    id == DebridProviders.REAL_DEBRID_ID || id == DebridProviders.ALL_DEBRID_ID

/** Existing account entries only. Browsing never creates, selects, deletes or unlocks anything. */
@Singleton
class NativeDebridAccountLibrary @Inject constructor(
    private val realDebrid: RealDebridApi,
    private val allDebrid: AllDebridApi,
    private val allDebridService: AllDebridService
) {
    private data class ReadyTorrent<Id>(val id: Id, val hash: String?)
    private val rdInventory = Inventory<String>()
    private val adInventory = Inventory<Long>()

    private class Inventory<Id> {
        private data class Snapshot<Id>(val scope: StreamAvailabilityScope, val ready: List<ReadyTorrent<Id>>, val checkedAt: Long)
        private val mutex = Mutex()
        private var snapshot: Snapshot<Id>? = null

        suspend fun read(scope: StreamAvailabilityScope, load: suspend () -> List<ReadyTorrent<Id>>?): List<ReadyTorrent<Id>> =
            mutex.withLock {
                val now = System.nanoTime()
                snapshot?.takeIf { it.scope == scope && now - it.checkedAt in 0L until 10_000_000_000L }
                    ?.let { return@withLock it.ready }
                val ready = load() ?: return@withLock emptyList()
                snapshot = Snapshot(scope, ready, System.nanoTime())
                ready
            }
    }

    internal suspend fun find(account: DebridServiceCredential, scope: StreamAvailabilityScope,
        hashes: Set<String>, operation: CapturedDebridOperation): Map<String, AccountLibraryItem> {
        if (hashes.isEmpty()) return emptyMap()
        val authorization = "Bearer ${operation.credential(account.provider.id)}"
        return when (account.provider.id) {
            DebridProviders.REAL_DEBRID_ID -> findRealDebrid(authorization, scope, hashes, operation)
            DebridProviders.ALL_DEBRID_ID -> findAllDebrid(authorization, scope, hashes, operation)
            else -> emptyMap()
        }
    }

    private suspend fun findRealDebrid(authorization: String, scope: StreamAvailabilityScope,
        hashes: Set<String>, operation: CapturedDebridOperation): Map<String, AccountLibraryItem> {
        val ready = rdInventory.read(scope) {
            // shortcut: Search the newest page, increase this bound if older library entries need coverage.
            val summaries = operation.request { realDebrid.listTorrents(authorization, 1, 200) }.readBody()
                ?: return@read null
            summaries.take(200).mapNotNull { summary ->
                val id = summary.id?.takeIf { it.isNotBlank() && summary.status.equals("downloaded", true) }
                    ?: return@mapNotNull null
                val hash = canonicalAvailabilityHash(summary.hash) ?: return@mapNotNull null
                ReadyTorrent(id, hash)
            }
        }
        val matches = ready.mapNotNull { item ->
            item.hash?.takeIf { it in hashes }?.let { it to item.id }
        }.groupBy({ it.first }, { it.second })
        val found = linkedMapOf<String, AccountLibraryItem>()
        withTimeoutOrNull(2_000L) {
            for ((hash, ids) in matches.entries.take(MAX_MATCHING_ITEMS)) {
                val id = ids.distinct().singleOrNull() ?: continue
                val detail = operation.request { realDebrid.getTorrentInfo(authorization, id) }.readBody()
                val item = detail?.asLibraryItem(id, hash) ?: continue
                found[hash] = item
            }
        }
        return found
    }

    private suspend fun findAllDebrid(authorization: String, scope: StreamAvailabilityScope,
        hashes: Set<String>, operation: CapturedDebridOperation): Map<String, AccountLibraryItem> {
        val ready = adInventory.read(scope) {
            val magnets = operation.request { allDebrid.magnetStatus(authorization) }.dataOrThrow().magnets
            magnets.take(1_000).groupBy { it.id }.mapNotNull { (id, items) ->
                val item = items.singleOrNull()?.takeIf { it.isReady() } ?: return@mapNotNull null
                if (id == null || id <= 0) return@mapNotNull null
                val hash = item.hash?.let { canonicalAvailabilityHash(it) ?: return@mapNotNull null }
                ReadyTorrent(id, hash)
            }
        }
        val matches = ready.mapNotNull { item ->
            val hash = item.hash ?: allDebridService.selectedMagnetHash(scope, item.id)
            hash?.takeIf { it in hashes }?.let { it to item.id }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (hash, ids) ->
            ids.singleOrNull()?.let { it to hash }
        }.take(MAX_MATCHING_ITEMS).toMap()
        if (matches.isEmpty()) return emptyMap()
        val details = operation.request { allDebrid.magnetFiles(authorization, matches.keys.toList()) }
            .dataOrThrow().magnets.groupBy { it.id }
        return matches.mapNotNull { (id, hash) ->
            val item = details[id]?.singleOrNull() ?: return@mapNotNull null
            item.asLibraryItem(id, hash)?.let { hash to it.first }
        }.toMap()
    }

    internal suspend fun resolve(account: DebridServiceCredential, scope: StreamAvailabilityScope, hash: String,
        file: StreamDebridLibraryFile, stream: Stream, season: Int?, episode: Int?,
        operation: CapturedDebridOperation): DirectDebridResolveResult {
        val authorization = "Bearer ${operation.credential(account.provider.id)}"
        val unavailable = DirectDebridResolveResult.Failure(
            DirectDebridFailure(DirectDebridFailureReason.UNAVAILABLE, account.provider.id))
        return when (account.provider.id) {
            DebridProviders.REAL_DEBRID_ID -> {
                val infoResponse = operation.request { realDebrid.getTorrentInfo(authorization, file.torrentId) }
                val info = infoResponse.readBody() ?: return infoResponse.toPlaybackFailure(account.provider.id)
                if (info.asLibraryItem(file.torrentId, hash)?.exactFile(stream, season, episode, operation.animeProof) != file)
                    return unavailable
                val link = info.links?.singleOrNull() ?: return unavailable
                val unlockResponse = operation.request { realDebrid.unrestrictLink(authorization, link) }
                val unlocked = unlockResponse.readBody() ?: return unlockResponse.toPlaybackFailure(account.provider.id)
                file.playback(unlocked.download, unlocked.filename, unlocked.filesize) ?: unavailable
            }
            DebridProviders.ALL_DEBRID_ID -> {
                val id = file.torrentId.toLongOrNull()?.takeIf { it > 0 } ?: return unavailable
                val status = operation.request { allDebrid.magnetStatus(authorization, id) }.dataOrThrow().magnets
                    .singleOrNull()?.takeIf { it.id == id && it.isReady() && it.verifiedHash(scope, id) == hash }
                    ?: return unavailable
                val detail = if (status.files.isNotEmpty()) status else
                    operation.request { allDebrid.magnetFiles(authorization, listOf(id)) }.dataOrThrow().magnets.singleOrNull()
                val (item, links) = detail?.asLibraryItem(id, hash) ?: return unavailable
                if (item.exactFile(stream, season, episode, operation.animeProof) != file) return unavailable
                val link = links[file.path] ?: return unavailable
                val unlocked = allDebridService.unlock(account.apiKey, link, operation)
                file.playback(unlocked.link, unlocked.filename, unlocked.filesize) ?: unavailable
            }
            else -> unavailable
        }
    }

    private fun AllDebridMagnetDto.verifiedHash(scope: StreamAvailabilityScope, id: Long): String? =
        if (hash == null) allDebridService.selectedMagnetHash(scope, id) else canonicalAvailabilityHash(hash)

    private companion object {
        const val MAX_MATCHING_ITEMS = 8
    }
}

private fun Response<*>.toPlaybackFailure(providerId: String): DirectDebridResolveResult =
    directDebridHttpFailure(code(), providerId, headers()["Retry-After"])

private fun <T> Response<T>.readBody(): T? {
    if (isSuccessful) return body()
    errorBody()?.close()
    return null
}

private fun RealDebridTorrentInfoDto.asLibraryItem(expectedId: String, hash: String): AccountLibraryItem? {
    val link = links?.singleOrNull() ?: return null
    if (id != expectedId || canonicalAvailabilityHash(this.hash) != hash || !status.equals("downloaded", true) ||
        !SyclopPlaybackPolicy.isHttps(link)) return null
    val source = files?.takeIf { it.size in 1..400 && it.count { file -> file.selected == 1 } == 1 } ?: return null
    val parsed = source.map { file ->
        val path = file.path?.libraryPath() ?: return null
        val fileId = file.id?.takeIf { it > 0 } ?: return null
        AccountLibraryFile(path, file.bytes, fileId.toString(), file.selected == 1)
    }
    if (parsed.distinctBy { it.providerFileId }.size != parsed.size) return null
    return AccountLibraryItem(expectedId, parsed)
}

private fun AllDebridMagnetDto.isReady(): Boolean = statusCode == 4 && error == null

private fun AllDebridMagnetDto.asLibraryItem(expectedId: Long, hash: String): Pair<AccountLibraryItem, Map<String, String>>? {
    if (id != expectedId || error != null || (this.hash != null && canonicalAvailabilityHash(this.hash) != hash)) return null
    val leaves = mutableListOf<AccountLibraryFile>()
    val links = mutableMapOf<String, String>()
    fun visit(nodes: List<AllDebridFileNodeDto>, parent: String, depth: Int): Boolean {
        if (depth > 24 || nodes.size > 400) return false
        for (node in nodes) {
            val name = node.name.libraryPath()?.takeUnless { '/' in it } ?: return false
            val path = if (parent.isEmpty()) name else "$parent/$name"
            if (path.length > 4_096) return false
            if (node.children.isNotEmpty()) {
                if (!node.link.isNullOrBlank() || !visit(node.children, path, depth + 1)) return false
            } else {
                if (leaves.size >= 400) return false
                val link = node.link?.takeIf(SyclopPlaybackPolicy::isHttps)
                if (link != null) links[path] = link
                leaves += AccountLibraryFile(path, node.size, null, link != null)
            }
        }
        return true
    }
    if (!visit(files, "", 0) || leaves.distinctBy { it.path }.size != leaves.size ||
        links.values.toSet().size != links.size) return null
    return AccountLibraryItem(expectedId.toString(), leaves) to links
}

private fun String.libraryPath(): String? =
    trim().replace('\\', '/').removePrefix("/").takeIf { path ->
        path.length in 1..4_096 && path.none(Char::isISOControl) &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

private fun StreamDebridLibraryFile.playback(url: String?, filename: String?, size: Long?): DirectDebridResolveResult.Success? {
    if (url == null || !SyclopPlaybackPolicy.isHttps(url)) return null
    val basename = path.substringAfterLast('/')
    if (filename != null && filename.libraryPath()?.substringAfterLast('/')?.equals(basename, true) != true) return null
    if (size != null && size > 0 && sizeBytes != null && sizeBytes > 0 && size != sizeBytes) return null
    return DirectDebridResolveResult.Success(url, basename, size?.takeIf { it > 0 } ?: sizeBytes)
}
