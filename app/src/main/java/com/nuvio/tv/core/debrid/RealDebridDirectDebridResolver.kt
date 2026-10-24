package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: RealDebridApi,
    private val fileSelector: RealDebridFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?,
        operation: CapturedDebridOperation? = null
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = operation?.credential(DebridProviders.REAL_DEBRID_ID) ?: dataStore.settings.first().realDebridApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val authorization = "Bearer $apiKey"

        return try {
            val magnet = resolve.magnetUri?.takeIf { it.startsWith("magnet:", true) }
                ?: DebridMagnetBuilder.fromStream(stream)
            if (magnet == null) {
                val source = stream.getStreamUrl()?.takeIf { it.isHttpUrl() }
                    ?: return DirectDebridResolveResult.Stale
                val response = scopedDebridRequest(operation) { api.unrestrictLink(authorization, source) }
                val body = response.body()
                response.errorBody()?.close()
                val url = body?.download?.takeIf { response.isSuccessful && it.isHttpUrl() }
                    ?: return DirectDebridResolveResult.Error
                return DirectDebridResolveResult.Success(url, body.filename, body.filesize)
            }
            val add = scopedDebridRequest(operation) { api.addMagnet(authorization, magnet) }
            val torrentId = add.body()?.id?.takeIf { add.isSuccessful && it.isNotBlank() }
                ?: return add.toFailureForAdd()
            // addMagnet does not guarantee a new, app-owned cloud job. Never delete on failure.
            val infoBefore = scopedDebridRequest(operation) { api.getTorrentInfo(authorization, torrentId) }
            if (!infoBefore.isSuccessful) return DirectDebridResolveResult.Stale
            val file = fileSelector.selectFile(
                files = infoBefore.body()?.files.orEmpty(),
                resolve = resolve,
                season = season,
                episode = episode
            ) ?: return DirectDebridResolveResult.Stale
            val fileId = file.id ?: return DirectDebridResolveResult.Stale
            val select = scopedDebridRequest(operation) { api.selectFiles(
                authorization = authorization,
                id = torrentId,
                files = fileId.toString()
            ) }
            select.body()?.close()
            select.errorBody()?.close()
            if (!select.isSuccessful && select.code() != 202) {
                return DirectDebridResolveResult.Stale
            }

            val infoAfter = scopedDebridRequest(operation) { api.getTorrentInfo(authorization, torrentId) }
            if (!infoAfter.isSuccessful) return DirectDebridResolveResult.Stale
            if (infoAfter.body()?.status in setOf("downloading", "queued", "magnet_conversion", "compressing", "uploading")) {
                return DirectDebridResolveResult.NotCached
            }
            val link = infoAfter.body()?.selectedDownloadLink(fileId)
                ?: return DirectDebridResolveResult.Stale
            val unrestrict = scopedDebridRequest(operation) { api.unrestrictLink(authorization, link) }
            if (!unrestrict.isSuccessful) return DirectDebridResolveResult.Stale
            val url = unrestrict.body()?.download?.takeIf { it.isHttpUrl() }
                ?: return DirectDebridResolveResult.Stale
            DirectDebridResolveResult.Success(
                url = url,
                filename = unrestrict.body()?.filename?.takeIf { it.isNotBlank() }
                    ?: file.displayName().takeIf { it.isNotBlank() },
                videoSize = unrestrict.body()?.filesize ?: file.bytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    private fun retrofit2.Response<com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto>.toFailureForAdd(): DirectDebridResolveResult {
        return when (code()) {
            401, 403 -> DirectDebridResolveResult.Error
            else -> DirectDebridResolveResult.Stale
        }
    }

    private fun RealDebridTorrentInfoDto.selectedDownloadLink(requestedFileId: Int): String? {
        if (!status.equals("downloaded", ignoreCase = true)) return null
        // selectFiles can return 202 when a reused job is already selected. The API does not
        // identify each link's file ID, so never guess link order in a multi-file cloud job.
        val selected = files.orEmpty().filter { it.selected == 1 }
        if (selected.singleOrNull()?.id != requestedFileId) return null
        return links.orEmpty().filter { it.isNotBlank() }.singleOrNull()?.takeIf { it.isHttpUrl() }
    }

}
