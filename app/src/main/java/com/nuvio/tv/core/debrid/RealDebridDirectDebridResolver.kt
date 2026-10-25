package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
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
        var stage = "input"
        var httpStatus: Int? = null
        var diagnosticInfo: RealDebridTorrentInfoDto? = null
        fun finish(result: DirectDebridResolveResult): DirectDebridResolveResult {
            val label = when (result) {
                is DirectDebridResolveResult.Success -> "success"
                DirectDebridResolveResult.NotCached -> "not_cached"
                DirectDebridResolveResult.Stale -> "stale"
                DirectDebridResolveResult.Error -> "error"
                DirectDebridResolveResult.MissingApiKey -> "missing_key"
            }
            Log.d("DebridResolve", RealDebridResolveDiagnostics.fields(stage, label, httpStatus,
                diagnosticInfo, resolve, season, episode))
            return result
        }

        return try {
            val magnet = resolve.magnetUri?.takeIf { it.startsWith("magnet:", true) }
                ?: DebridMagnetBuilder.fromStream(stream)
            if (magnet == null) {
                val source = stream.getStreamUrl()?.takeIf { it.isHttpUrl() }
                    ?: return finish(DirectDebridResolveResult.Stale)
                stage = "hoster_unrestrict"
                val response = scopedDebridRequest(operation) { api.unrestrictLink(authorization, source) }
                httpStatus = response.code()
                val body = response.body()
                response.errorBody()?.close()
                val url = body?.download?.takeIf { response.isSuccessful && it.isHttpUrl() }
                    ?: return finish(DirectDebridResolveResult.Error)
                return finish(DirectDebridResolveResult.Success(url, body.filename, body.filesize))
            }
            stage = "magnet_add"
            val add = scopedDebridRequest(operation) { api.addMagnet(authorization, magnet) }
            httpStatus = add.code()
            val torrentId = add.body()?.id?.takeIf { add.isSuccessful && it.isNotBlank() }
                ?: return finish(add.toFailureForAdd())
            // addMagnet does not guarantee a new, app-owned cloud job. Never delete on failure.
            stage = "info_before"
            httpStatus = null
            val infoBefore = scopedDebridRequest(operation) { api.getTorrentInfo(authorization, torrentId) }
            httpStatus = infoBefore.code()
            diagnosticInfo = infoBefore.body()
            if (!infoBefore.isSuccessful) return finish(DirectDebridResolveResult.Stale)
            stage = "file_select"
            val files = diagnosticInfo?.files.orEmpty()
            // A converting cloud job may not have a playable file list yet. Never turn a
            // present-but-contradictory filename or episode proof into a processing claim.
            if (diagnosticInfo?.status in RD_PROCESSING_STATES &&
                files.none { it.displayName().hasDebridVideoExtension() }) {
                return finish(DirectDebridResolveResult.NotCached)
            }
            val file = fileSelector.selectFile(
                files = files,
                resolve = resolve,
                season = season,
                episode = episode,
                animeProof = operation?.animeProof
            ) ?: return finish(DirectDebridResolveResult.Stale)
            stage = "file_id"
            val fileId = file.id ?: return finish(DirectDebridResolveResult.Stale)
            stage = "select_files"
            httpStatus = null
            val select = scopedDebridRequest(operation) { api.selectFiles(
                authorization = authorization,
                id = torrentId,
                files = fileId.toString()
            ) }
            httpStatus = select.code()
            select.body()?.close()
            select.errorBody()?.close()
            if (!select.isSuccessful && select.code() != 202) {
                return finish(DirectDebridResolveResult.Stale)
            }

            stage = "info_after"
            httpStatus = null
            val infoAfter = scopedDebridRequest(operation) { api.getTorrentInfo(authorization, torrentId) }
            httpStatus = infoAfter.code()
            diagnosticInfo = infoAfter.body()
            if (!infoAfter.isSuccessful) return finish(DirectDebridResolveResult.Stale)
            if (diagnosticInfo?.status in RD_PROCESSING_STATES) {
                return finish(DirectDebridResolveResult.NotCached)
            }
            stage = "anime_file_check"
            if (operation?.animeProof?.let { proof ->
                infoAfter.body()?.files?.singleOrNull { it.selected == 1 && it.id == fileId }
                    ?.path?.let(proof::matchesFile) != true
            } == true) return finish(DirectDebridResolveResult.Stale)
            stage = "selected_link"
            val link = diagnosticInfo?.selectedDownloadLink(fileId)
                ?: return finish(DirectDebridResolveResult.Stale)
            stage = "unrestrict_link"
            httpStatus = null
            val unrestrict = scopedDebridRequest(operation) { api.unrestrictLink(authorization, link) }
            httpStatus = unrestrict.code()
            if (!unrestrict.isSuccessful) return finish(DirectDebridResolveResult.Stale)
            stage = "final_url"
            val url = unrestrict.body()?.download?.takeIf { it.isHttpUrl() }
                ?: return finish(DirectDebridResolveResult.Stale)
            stage = "complete"
            finish(DirectDebridResolveResult.Success(
                url = url,
                filename = unrestrict.body()?.filename?.takeIf { it.isNotBlank() }
                    ?: file.displayName().takeIf { it.isNotBlank() },
                videoSize = unrestrict.body()?.filesize ?: file.bytes
            ))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            finish(DirectDebridResolveResult.Error)
        }
    }

    private fun retrofit2.Response<com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto>.toFailureForAdd(): DirectDebridResolveResult {
        return when (code()) {
            401, 403, 451 -> DirectDebridResolveResult.Error
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

// Documented torrent statuses: https://api.real-debrid.com/ (torrents/info schema).
private val RD_PROCESSING_STATES = setOf("magnet_conversion", "queued", "downloading", "compressing", "uploading")

/** Allowlisted labels and counts only: raw status, paths, identifiers and links never pass through. */
internal object RealDebridResolveDiagnostics {
    private val stages = setOf("input", "hoster_unrestrict", "magnet_add", "info_before", "file_select", "file_id",
        "select_files", "info_after", "anime_file_check", "selected_link", "unrestrict_link", "final_url", "complete")
    private val results = setOf("success", "not_cached", "stale", "error", "missing_key")
    private val statuses = RD_PROCESSING_STATES + setOf("magnet_error", "waiting_files_selection", "downloaded", "error", "virus", "dead")

    fun fields(stage: String, result: String, httpStatus: Int?, info: RealDebridTorrentInfoDto?,
        resolve: StreamClientResolve, season: Int?, episode: Int?): String {
        val files = info?.files.orEmpty()
        val status = info?.status?.let { if (it in statuses) it else "unknown" } ?: "none"
        return "provider=realdebrid stage=${stage.takeIf { it in stages } ?: "unknown"} " +
            "result=${result.takeIf { it in results } ?: "unknown"} httpStatus=${httpStatus?.takeIf { it in 100..599 } ?: "none"} " +
            "status=$status fileCount=${files.size} playableCount=${files.count { it.displayName().hasDebridVideoExtension() }} " +
            "selectedCount=${files.count { it.selected == 1 }} linkCount=${info?.links.orEmpty().size} " +
            "filenameHint=${!resolve.filename.isNullOrBlank()} rawFilenameHint=${!resolve.stream?.raw?.filename.isNullOrBlank()} " +
            "fileIndexHint=${resolve.fileIdx != null} episodeHint=${(season ?: resolve.season) != null && (episode ?: resolve.episode) != null}"
    }
}
