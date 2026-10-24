package com.nuvio.tv.core.debrid

import android.util.Log

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AllDebridDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val service: AllDebridService
) {
    suspend fun resolve(stream: Stream, season: Int?, episode: Int?, operation: CapturedDebridOperation? = null): DirectDebridResolveResult {
        val metadata = stream.clientResolve ?: return DirectDebridResolveResult.Stale
        val apiKey = operation?.credential(DebridProviders.ALL_DEBRID_ID) ?: dataStore.settings.first().allDebridApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        var stage = "input"
        return try {
            val magnet = metadata.magnetUri?.takeIf { it.startsWith("magnet:", true) }
                ?: DebridMagnetBuilder.fromStream(stream)
            val source: String
            var selectedFile: AllDebridFile? = null
            if (magnet != null) {
                stage = "magnet_upload"
                val uploaded = scopedDebridRequest(operation) { service.upload(apiKey, magnet) }
                val id = uploaded.id ?: return DirectDebridResolveResult.Stale
                // No background download polling. An uncached job remains available in cloud.
                stage = "magnet_status"
                val ready = uploaded.ready == true || scopedDebridRequest(operation) { service.status(apiKey, id) }.any { it.id == id && it.statusCode == 4 }
                if (!ready) return DirectDebridResolveResult.NotCached
                stage = "magnet_files"
                val files = scopedDebridRequest(operation) { service.files(apiKey, listOf(id)) }[id].orEmpty()
                stage = "file_select"
                selectedFile = selectDebridFile(
                    files = files,
                    resolve = metadata,
                    season = season,
                    episode = episode,
                    path = { it.path },
                    isPlayable = { it.path.hasDebridVideoExtension() },
                    size = { it.size ?: 0L }
                ) ?: return DirectDebridResolveResult.Stale
                source = selectedFile.link
            } else {
                source = stream.getStreamUrl()?.takeIf { it.isHttpUrl() }
                    ?: return DirectDebridResolveResult.Stale
            }
            stage = "link_unlock"
            val result = scopedDebridRequest(operation) { service.unlock(apiKey, source) }
            DirectDebridResolveResult.Success(
                url = result.link ?: return DirectDebridResolveResult.Stale,
                filename = result.filename ?: selectedFile?.path?.substringAfterLast('/'),
                videoSize = result.filesize ?: selectedFile?.size
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Fixed stage/status/class only: never log server text, request fields or Throwable.
            val status = (error as? AllDebridApiException)?.httpStatus?.takeIf { it in 100..599 }
            Log.w("DebridResolve", "provider=alldebrid stage=$stage result=error " +
                "httpStatus=${status ?: "none"} exception=${error.javaClass.simpleName}")
            DirectDebridResolveResult.Error
        }
    }
}
