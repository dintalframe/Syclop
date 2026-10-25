package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamFileMetadata
import com.nuvio.tv.domain.model.StreamDurationOrigin
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Never resolves/adds a remote magnet for ranking. Only reads an existing native resolve cache. */
@Singleton
class DebridBitrateMetadataService @Inject constructor(
    private val resolver: DirectDebridResolver,
    private val settings: DebridSettingsDataStore
) {
    private val slots = Semaphore(2)

    suspend fun enrichKnownFiles(streams: List<Stream>, durationMillis: Long?,
        durationOrigin: StreamDurationOrigin?, season: Int?, episode: Int?, expectedProfileId: Int,
        nativeVideoId: String? = null, nativeMediaType: String? = null,
        activeFileDuration: ActivePlayerFileDuration? = null): List<Pair<Stream,Stream>> = coroutineScope {
        val scope = MetadataScope(expectedProfileId, settings.activeProfileSelectionRevision, settings.activeCredentialRevision)
        if (!sameViewer(scope)) return@coroutineScope emptyList()
        // Only a small visible window; no bulk link generation or torrent submission.
        val result = streams.take(12).map { original -> async {
            slots.withPermit {
                val changed = try { withTimeoutOrNull(5_000L) {
                    enrich(original, durationMillis, durationOrigin, season, episode, scope, nativeVideoId, nativeMediaType, activeFileDuration)
                } } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                changed?.let { original to it }
            }
        } }.awaitAll().filterNotNull()
        // A completed child may wait behind another child while the viewer/account changes.
        if (sameViewer(scope)) result else emptyList()
    }

    private data class MetadataScope(val profile: Int, val selection: Long, val credentials: Long)
    private fun sameViewer(scope: MetadataScope) = scope.credentials >= 0L &&
        settings.activeProfileId.value == scope.profile && settings.activeProfileSelectionRevision == scope.selection &&
        settings.activeCredentialRevision == scope.credentials

    private suspend fun authorized(url: String, scope: MetadataScope, nativeVideoId: String?, season: Int?,
        episode: Int?, nativeMediaType: String?) = sameViewer(scope) &&
        resolver.isPlaybackAuthorized(url, scope.profile, nativeVideoId, season, episode, nativeMediaType) && sameViewer(scope)

    private suspend fun enrich(original: Stream, durationMillis: Long?, durationOrigin: StreamDurationOrigin?,
        season: Int?, episode: Int?, scope: MetadataScope, nativeVideoId: String?, nativeMediaType: String?,
        activeFileDuration: ActivePlayerFileDuration?): Stream? {
        currentCoroutineContext().ensureActive()
        if (!sameViewer(scope)) return null
        val candidate = if(original.getStreamUrl() == null) resolver.cachedPlayableStream(original, season, episode, nativeVideoId, nativeMediaType) ?: return null else original
        val url = candidate.getStreamUrl() ?: return null
        if (!authorized(url, scope, nativeVideoId, season, episode, nativeMediaType)) return null
        // Result-list enrichment is cache-only. Network verification belongs to a selected file.
        // A source key can now resolve through a different connected service. Only an
        // identical native URL can reuse older file-specific duration/tracks/bytes.
        val previous = original.nativeFileMetadata?.takeIf { original.getStreamUrl() == url }
        val bytes = candidate.nativeFileMetadata?.selectedFileBytes?.takeIf { it > 0L }
            ?: previous?.selectedFileBytes?.takeIf { it > 0L }
        currentCoroutineContext().ensureActive()
        if (!authorized(url, scope, nativeVideoId, season, episode, nativeMediaType)) return null
        val playingDuration = activeFileDuration?.takeIf {
            it.sourceKey == original.stableKey(0) && it.matches(scope.profile, scope.selection, scope.credentials,
                nativeVideoId, nativeMediaType, season, episode, url)
        }?.durationMillis
        val measuredDuration = playingDuration ?: previous?.durationMillis?.takeIf { it > 0L && previous.durationOrigin == StreamDurationOrigin.NATIVE_CONTAINER }
        val duration = measuredDuration ?: durationMillis?.takeIf { it > 0L && durationOrigin != null }
        val metadata = (previous ?: StreamFileMetadata()).copy(selectedFileBytes = bytes,
            durationMillis = duration, durationOrigin = if(measuredDuration != null) StreamDurationOrigin.NATIVE_CONTAINER else durationOrigin.takeIf { duration != null },
            sourceIdentity = original.stableKey(0).substringBeforeLast('|'))
        // Cache resolution is private metadata input only. Keep the original source for a fresh
        // native resolve when selected; never promote an expiring signed URL into UI state.
        return original.copy(nativeFileMetadata = metadata)
    }

}

/** HEAD only: no media body, credentials, addon headers, redirects, retries or logging. */
internal class DebridSelectedFileHeadProbe(private val client: OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build(),
    private val allowLocalHttpForTests: Boolean = false) {
    suspend fun size(url: String): Long? {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val localTest = allowLocalHttpForTests && parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1")
        if((parsed.scheme != "https" && !localTest) || parsed.host.isNullOrBlank() || parsed.rawUserInfo != null ||
            (!localTest && parsed.port !in setOf(-1,443))) return null
        val request = Request.Builder().url(url).head().header("Accept-Encoding", "identity").header("User-Agent", "Syclop metadata").build()
        return perform(request).use { response ->
            if(response.code != 200 || response.header("Content-Encoding").let { it != null && it != "identity" }) return@use null
            val type = response.header("Content-Type")?.substringBefore(';')?.lowercase().orEmpty()
            if(!type.startsWith("video/") && type != "application/octet-stream" && type != "binary/octet-stream") return@use null
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        }
    }
    private suspend fun perform(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if(continuation.isActive) continuation.resumeWithException(IOException("File metadata probe failed."))
            }
            override fun onResponse(call: Call, response: Response) {
                if(continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
            }
        })
    }
}
