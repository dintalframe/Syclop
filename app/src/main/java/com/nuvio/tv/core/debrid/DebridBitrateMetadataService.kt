package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamFileMetadata
import com.nuvio.tv.domain.model.StreamDurationOrigin
import java.io.IOException
import java.security.MessageDigest
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
    private val head = DebridSelectedFileHeadProbe()
    private val slots = Semaphore(2)
    private data class CachedSize(val bytes: Long?, val untilMs: Long)
    private val sizes = linkedMapOf<String, CachedSize>()

    suspend fun enrichKnownFiles(streams: List<Stream>, durationMillis: Long?,
        durationOrigin: StreamDurationOrigin?, season: Int?, episode: Int?, expectedProfileId: Int): List<Pair<Stream,Stream>> = coroutineScope {
        val scope = MetadataScope(expectedProfileId, settings.activeProfileSelectionRevision, settings.activeCredentialRevision)
        if (!sameViewer(scope)) return@coroutineScope emptyList()
        // Only a small visible window; no bulk link generation or torrent submission.
        val result = streams.take(12).map { original -> async {
            slots.withPermit {
                val changed = try { withTimeoutOrNull(5_000L) {
                    enrich(original, durationMillis, durationOrigin, season, episode, scope)
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

    private suspend fun authorized(url: String, scope: MetadataScope) = sameViewer(scope) &&
        resolver.isPlaybackAuthorized(url, scope.profile) && sameViewer(scope)

    private suspend fun enrich(original: Stream, durationMillis: Long?, durationOrigin: StreamDurationOrigin?,
        season: Int?, episode: Int?, scope: MetadataScope): Stream? {
        currentCoroutineContext().ensureActive()
        if (!sameViewer(scope)) return null
        val candidate = if(original.getStreamUrl() == null) resolver.cachedPlayableStream(original, season, episode) ?: return null else original
        val url = candidate.getStreamUrl() ?: return null
        if (!authorized(url, scope)) return null
        val key = digest("${scope.profile}|${scope.selection}|${scope.credentials}|$url")
        val nativeBytes = candidate.nativeFileMetadata?.selectedFileBytes?.takeIf { it > 0L }
        val cached = synchronized(sizes) { sizes[key]?.takeIf { it.untilMs > now() } }
        val bytes = nativeBytes ?: cached?.bytes ?: try { if(durationMillis == null || cached != null) null else head.size(url) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (!authorized(url, scope)) return null
        synchronized(sizes) {
            sizes[key] = CachedSize(bytes, now() + if(bytes != null) 10 * 60_000L else 30_000L)
            while(sizes.size > 128) sizes.remove(sizes.keys.first())
        }
        val duration = durationMillis?.takeIf { it > 0L && durationOrigin != null }
        val metadata = StreamFileMetadata(bytes, duration, durationOrigin.takeIf { duration != null }, original.stableKey(0).substringBeforeLast('|'))
        // Cache resolution is private metadata input only. Keep the original source for a fresh
        // native resolve when selected; never promote an expiring signed URL into UI state.
        return original.copy(nativeFileMetadata = metadata)
    }
    private fun now() = System.nanoTime() / 1_000_000L
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
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
