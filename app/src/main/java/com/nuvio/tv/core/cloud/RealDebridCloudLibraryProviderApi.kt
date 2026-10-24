package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.hasDebridVideoExtension
import com.nuvio.tv.core.debrid.isHttpUrl
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only cloud browsing. Playback only unrestricts an already-selected file's link. */
@Singleton
class RealDebridCloudLibraryProviderApi @Inject constructor(
    private val api: RealDebridApi
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.RealDebrid
    private val requestMutex = Mutex()
    private var lastRequestAtNanos = 0L

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> = try {
        val authorization = "Bearer ${apiKey.trim()}"
        val torrents = linkedMapOf<String, RealDebridTorrentInfoDto>()
        var page = 1
        while (true) {
            val response = request { api.listTorrents(authorization, page, PAGE_SIZE) }
            val batch = response.bodyOrThrow()
            if (batch.isEmpty()) break
            val previousCount = torrents.size
            batch.forEach { torrent -> torrent.id?.takeIf { it.isNotBlank() }?.let { torrents[it] = torrent } }
            if (torrents.size == previousCount) throw IOException("Real-Debrid pagination did not advance.")
            val total = response.headers()["X-Total-Count"]?.toIntOrNull()
            if (batch.size < PAGE_SIZE || (total != null && torrents.size >= total)) break
            page += 1
        }
        Result.success(torrents.mapNotNull { (id, summary) ->
            // Pending jobs do not need a second call; downloaded jobs expose their file list.
            val detail = if (summary.status.equals("downloaded", true)) {
                val response = request { api.getTorrentInfo(authorization, id) }
                if (response.code() == 404) {
                    response.errorBody()?.close()
                    return@mapNotNull null // The user or service removed it while the list was loading.
                }
                response.bodyOrThrow()
            } else summary
            detail.toCloudLibraryItem(id)
        })
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        // Neither server bodies nor exception messages are safe to show with authenticated links.
        Result.failure(IOException("Unable to load Real-Debrid cloud. Check the account and network connection."))
    }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable || item.providerId != provider.id) return CloudLibraryPlaybackResult.NotPlayable
        val requestedId = file.id?.toIntOrNull() ?: return CloudLibraryPlaybackResult.NotPlayable
        val authorization = "Bearer ${apiKey.trim()}"
        return try {
            val info = request { api.getTorrentInfo(authorization, item.id) }.bodyOrThrow()
            if (!info.status.equals("downloaded", true)) return CloudLibraryPlaybackResult.NotPlayable
            val selected = info.files.orEmpty().filter { it.selected == 1 }
            val requested = selected.singleOrNull { it.id == requestedId }
                ?.takeIf { it.path.orEmpty().hasDebridVideoExtension() }
                ?: return CloudLibraryPlaybackResult.NotPlayable
            if (requested.path.normalizedPath() != file.name.normalizedPath()) return CloudLibraryPlaybackResult.NotPlayable
            val links = info.links.orEmpty().filter { it.isHttpUrl() }.distinct()
            if (links.isEmpty()) return CloudLibraryPlaybackResult.NotPlayable

            // The sole selected-file/sole-link case is unambiguous without relying on link order.
            if (selected.size == 1 && links.size == 1) {
                return unlock(authorization, links.single()).toPlaybackResult(requested)
            }

            // A link index is only a search hint. The unrestrict response must independently
            // identify exactly the requested selected file by filename and compatible byte size.
            val hintedLink = links.getOrNull(selected.indexOf(requested))
            val candidates = listOfNotNull(hintedLink) + links.filter { it != hintedLink }
            for (link in candidates) {
                val unlocked = unlock(authorization, link)
                if (unlocked.matchesRequestedFile(requested, selected)) {
                    return unlocked.toPlaybackResult(requested)
                }
            }
            CloudLibraryPlaybackResult.Failed("Real-Debrid could not identify the selected file safely.")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed("Unable to prepare this Real-Debrid file.")
        }
    }

    private suspend fun unlock(authorization: String, link: String): RealDebridUnrestrictLinkDto =
        request { api.unrestrictLink(authorization, link) }.bodyOrThrow()

    private fun RealDebridUnrestrictLinkDto.toPlaybackResult(file: RealDebridTorrentFileDto): CloudLibraryPlaybackResult {
        val url = download?.takeIf { it.isHttpUrl() } ?: return CloudLibraryPlaybackResult.NotPlayable
        return CloudLibraryPlaybackResult.Success(url, filename ?: file.displayName(), filesize ?: file.bytes)
    }

    private fun RealDebridTorrentInfoDto.toCloudLibraryItem(id: String): CloudLibraryItem = CloudLibraryItem(
        providerId = provider.id,
        providerName = provider.displayName,
        id = id,
        type = CloudLibraryItemType.Torrent,
        name = filename ?: originalFilename ?: "Torrent $id",
        status = status,
        sizeBytes = bytes ?: originalBytes,
        progressFraction = progress?.let { (it / 100f).coerceIn(0f, 1f) },
        files = files.orEmpty().mapNotNull { file ->
            val fileId = file.id ?: return@mapNotNull null
            CloudLibraryFile(
                id = fileId.toString(),
                name = file.path.orEmpty().removePrefix("/"),
                sizeBytes = file.bytes,
                playable = status.equals("downloaded", true) && file.selected == 1 &&
                    file.path.orEmpty().hasDebridVideoExtension() && links.orEmpty().any { it.isHttpUrl() }
            )
        }
    )

    // The service allows 250 requests/minute. Cloud browsing uses roughly 150/minute,
    // leaving headroom for foreground playback; there is no polling or background refresh.
    private suspend fun <T> request(block: suspend () -> Response<T>): Response<T> = requestMutex.withLock {
        val elapsedMs = (System.nanoTime() - lastRequestAtNanos) / 1_000_000L
        if (lastRequestAtNanos != 0L && elapsedMs < REQUEST_INTERVAL_MS) delay(REQUEST_INTERVAL_MS - elapsedMs)
        lastRequestAtNanos = System.nanoTime()
        block()
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (isSuccessful) body()?.let { return it }
        errorBody()?.close()
        throw IOException("Real-Debrid cloud request failed.")
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val REQUEST_INTERVAL_MS = 400L
    }
}

private fun RealDebridUnrestrictLinkDto.matchesRequestedFile(
    requested: RealDebridTorrentFileDto,
    selected: List<RealDebridTorrentFileDto>
): Boolean {
    if (!download.isHttpUrl()) return false
    val name = filename.normalizedPath().takeIf { it.isNotBlank() } ?: return false
    val matches = selected.filter { candidate ->
        val path = candidate.path.normalizedPath()
        path.equals(name, true) || path.endsWith("/$name", true)
    }
    if (matches.singleOrNull()?.id != requested.id) return false
    val expectedSize = requested.bytes?.takeIf { it > 0L }
    val actualSize = filesize?.takeIf { it > 0L }
    return expectedSize == null || actualSize == null || expectedSize == actualSize
}

private fun String?.normalizedPath(): String = orEmpty().trim().replace('\\', '/').removePrefix("/")
