package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.AllDebridApiException
import com.nuvio.tv.core.debrid.AllDebridService
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.dataOrThrow
import com.nuvio.tv.core.debrid.hasDebridVideoExtension
import com.nuvio.tv.core.debrid.isHttpUrl
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class AllDebridCloudLibraryProviderApi @Inject constructor(
    private val service: AllDebridService,
    private val deletion: CloudLibraryDeletionTransport
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.AllDebrid

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> = try {
        val magnets = service.status(apiKey)
        val files = service.files(apiKey, magnets.filter { it.statusCode == 4 }.mapNotNull { it.id })
        Result.success(magnets.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            CloudLibraryItem(
                providerId = provider.id,
                providerName = provider.displayName,
                id = id.toString(),
                type = CloudLibraryItemType.Torrent,
                name = item.filename ?: item.name ?: "Magnet $id",
                status = item.status,
                sizeBytes = item.size,
                progressFraction = if (item.statusCode == 4) 1f else item.size?.takeIf { it > 0L }?.let {
                    ((item.downloaded ?: 0L).toDouble() / it).toFloat().coerceIn(0f, 1f)
                },
                files = files[id].orEmpty().map { file ->
                    CloudLibraryFile(id = file.path, name = file.path, sizeBytes = file.size,
                        playable = file.path.hasDebridVideoExtension())
                }
            )
        })
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(AllDebridApiException("Unable to load AllDebrid cloud. Check the account and network connection."))
    }

    override suspend fun resolvePlayback(apiKey: String, item: CloudLibraryItem, file: CloudLibraryFile): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val id = item.id.toLongOrNull() ?: return CloudLibraryPlaybackResult.NotPlayable
        return try {
            val source = service.files(apiKey, listOf(id))[id].orEmpty().singleOrNull { it.path == file.id }
                ?: return CloudLibraryPlaybackResult.NotPlayable
            val result = service.unlock(apiKey, source.link)
            CloudLibraryPlaybackResult.Success(result.link ?: return CloudLibraryPlaybackResult.NotPlayable,
                result.filename ?: file.name.substringAfterLast('/'), result.filesize ?: file.sizeBytes)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed("Unable to prepare this AllDebrid file.")
        }
    }

    override suspend fun torrentIdsBeforeCreate(apiKey: String): Set<String>? {
        if (apiKey.isBlank()) return null
        return try {
            val inventory = deletion.allDebrid.magnetInventory(
                "Bearer $apiKey", session = Random.nextInt(1, Int.MAX_VALUE)
            ).dataOrThrow()
            val magnets = inventory.magnets?.takeIf { inventory.fullsync == true } ?: return null
            val ids = magnets.map { it.id?.takeIf { id -> id > 0 }?.toString() ?: return null }
            ids.toSet().takeIf { it.size == magnets.size }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    override suspend fun deleteCompletedFile(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        isStillAuthorized: suspend () -> Boolean
    ): Boolean {
        if (apiKey.isBlank() || item.type != CloudLibraryItemType.Torrent ||
            !item.matchesSinglePlayableFile(provider.id, file)) return false
        val id = item.id.toLongOrNull()?.takeIf { it > 0 && it.toString() == item.id } ?: return false
        val authorization = "Bearer $apiKey"
        return try {
            val magnet = deletion.allDebrid.magnetFiles(authorization, listOf(id)).dataOrThrow()
                .magnets.singleOrNull()?.takeIf { it.id == id && it.error == null } ?: return false
            // Browsing omits unlinked leaves, so prove the entire raw tree has only one leaf.
            var node = magnet.files.singleOrNull() ?: return false
            val path = mutableListOf<String>()
            while (node.children.isNotEmpty()) {
                if (node.name.isBlank() || !node.link.isNullOrBlank()) return false
                path += node.name
                node = node.children.singleOrNull() ?: return false
            }
            if (node.name.isBlank() || !node.link.isHttpUrl() || node.size != file.sizeBytes) return false
            path += node.name
            val name = path.joinToString("/")
            if (name != file.id || name != file.name) return false
            if (!isStillAuthorized()) return false
            val response = deletion.allDebrid.deleteMagnet(authorization, id)
            response.errorBody()?.close()
            val result = response.body()
            response.isSuccessful && result?.successful == true && result.error == null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }
}
