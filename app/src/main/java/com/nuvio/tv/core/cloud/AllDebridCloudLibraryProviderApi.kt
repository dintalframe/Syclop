package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.AllDebridApiException
import com.nuvio.tv.core.debrid.AllDebridService
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.hasDebridVideoExtension
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AllDebridCloudLibraryProviderApi @Inject constructor(
    private val service: AllDebridService
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
}
