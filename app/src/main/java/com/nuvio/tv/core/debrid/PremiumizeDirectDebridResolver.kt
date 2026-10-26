package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumizeDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: PremiumizeApi,
    private val fileSelector: PremiumizeDirectDownloadFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?,
        operation: CapturedDebridOperation? = null
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = operation?.credential(DebridProviders.PREMIUMIZE_ID) ?: dataStore.settings.first().premiumizeApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val source = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: stream.getStreamUrl()?.takeIf { it.isNotBlank() }
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            val response = scopedDebridRequest(operation) { api.directDownload(authorization, source) }
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                return directDebridHttpFailure(response.code(), DebridProviders.PREMIUMIZE_ID, response.headers()["Retry-After"])
            }
            val body = response.body() ?: return DirectDebridResolveResult.Stale
            if (body.status.equals("error", ignoreCase = true)) {
                // No verified machine-code cache-miss contract is available for directdl.
                // Human error text ("cache server unavailable", for example) is never a
                // negative cache observation or a reason to refresh every source.
                return DirectDebridResolveResult.Failure(DirectDebridFailure(
                    DirectDebridFailureReason.UNAVAILABLE, DebridProviders.PREMIUMIZE_ID))
            }
            val file = fileSelector.selectFile(
                files = body.content.orEmpty(),
                resolve = resolve,
                season = season,
                episode = episode,
                animeProof = operation?.animeProof
            ) ?: return wrongDebridFile(DebridProviders.PREMIUMIZE_ID)
            val url = file.link?.takeIf { it.isNotBlank() } ?: return DirectDebridResolveResult.Stale
            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() } ?: stream.behaviorHints?.filename,
                videoSize = file.size ?: stream.behaviorHints?.videoSize
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            directDebridExceptionFailure(error, DebridProviders.PREMIUMIZE_ID)
        }
    }

    private fun buildMagnetUri(resolve: StreamClientResolve): String? {
        val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            resolve.sources
                ?.mapNotNull { it.toTrackerUrlOrNull() }
                ?.distinct()
                ?.forEach { source ->
                    append("&tr=")
                    append(java.net.URLEncoder.encode(source, "UTF-8"))
                }
        }
    }

    private fun String.toTrackerUrlOrNull(): String? {
        val value = trim()
        if (value.isBlank() || value.startsWith("dht:", ignoreCase = true)) return null
        return value.removePrefix("tracker:").trim().takeIf { it.isNotBlank() }
    }
}
