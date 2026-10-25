package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.util.parseRuntimeMinutes
import com.nuvio.tv.core.debrid.ActivePlayerFileDuration
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDurationOrigin
import com.nuvio.tv.domain.model.StreamFileMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PlayerSourceMetadataContext(
    val nativeVideoId: String, val mediaType: String, val season: Int?, val episode: Int?,
    val durationMillis: Long?, val durationOrigin: StreamDurationOrigin?,
    val activeFileDuration: ActivePlayerFileDuration? = null
) {
    override fun toString() = "PlayerSourceMetadataContext([redacted])"
}

internal data class PlayerSourceMetadataRequest(
    val context: PlayerSourceMetadataContext, val streams: List<Stream>,
    val profileId: Int, val selectionRevision: Long, val credentialRevision: Long,
    val sourceRequestKey: String, val mediaOriginKey: String?
) {
    override fun toString() = "PlayerSourceMetadataRequest([redacted])"
}

internal data class PlayerSourceMetadataSnapshot(
    val request: PlayerSourceMetadataRequest? = null,
    val metadata: Map<String, StreamFileMetadata> = emptyMap()
) {
    override fun toString() = "PlayerSourceMetadataSnapshot(items=${metadata.size})"
}

/** Cache-only display copies. No resolver, transport or account-operation API is exposed here. */
internal class PlayerSourcePanelMetadata(
    private val readCached: suspend (List<Stream>, PlayerSourceMetadataContext, Int) -> List<Pair<Stream, Stream>>
) {
    suspend fun enrich(request: PlayerSourceMetadataRequest,
        rankAll: (List<Stream>) -> List<Stream>, current: () -> Boolean): PlayerSourceMetadataSnapshot {
        currentCoroutineContext().ensureActive()
        if (!current() || request.credentialRevision < 0L) return PlayerSourceMetadataSnapshot()
        val ranked = rankAll(displayStreams(request, PlayerSourceMetadataSnapshot()))
        val playing = request.context.activeFileDuration?.sourceKey?.let { key -> ranked.singleOrNull { it.stableKey(0) == key } }
        // Reserve one of the existing twelve cache reads for the exact current source.
        val candidates = (listOfNotNull(playing) + ranked.filterNot { it === playing }).take(12)
        val found = try { readCached(candidates, request.context, request.profileId) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { return PlayerSourceMetadataSnapshot() }
        currentCoroutineContext().ensureActive()
        if (!current()) return PlayerSourceMetadataSnapshot()
        val acceptedKeys = candidates.mapTo(hashSetOf()) { it.stableKey(0) }
        // Retain only facts. A returned cache URL/header/filename can never replace a controller source.
        val facts = found.mapNotNull { (original, enriched) ->
            original.stableKey(0).takeIf { it in acceptedKeys }?.let { key ->
                enriched.nativeFileMetadata?.let { key to it }
            }
        }.toMap()
        if (!current()) return PlayerSourceMetadataSnapshot()
        return PlayerSourceMetadataSnapshot(request, facts)
    }

    fun displayStreams(request: PlayerSourceMetadataRequest, snapshot: PlayerSourceMetadataSnapshot): List<Stream> {
        val facts = snapshot.metadata.takeIf { snapshot.request == request }.orEmpty()
        val movieRuntime = request.context.durationMillis?.takeIf {
            request.context.durationOrigin == StreamDurationOrigin.CATALOG_MOVIE &&
                request.context.mediaType == "movie" && request.context.season == null && request.context.episode == null
        }
        return request.streams.map { original ->
            original.copy(nativeFileMetadata = facts[original.stableKey(0)] ?: original.nativeFileMetadata,
                reportedMovieRuntimeMillis = movieRuntime)
        }
    }

    companion object {
        /** Identification only; native URL/authorization still must match before duration is used. */
        fun currentSourceKey(state: PlayerUiState): String? {
            val hash = state.currentStreamInfoHash?.takeIf { it.isNotBlank() }
            val addon = state.currentStreamAddonName?.takeIf { it.isNotBlank() } ?: return null
            val matches = if (hash != null) state.sourceAllStreams.filter {
                it.getEffectiveInfoHash().equals(hash, ignoreCase = true) && it.addonName == addon &&
                    (state.currentStreamFileIdx == null || it.getEffectiveFileIdx() == state.currentStreamFileIdx)
            } else state.sourceAllStreams.filter {
                !state.currentStreamUrl.isNullOrBlank() && it.getStreamUrl() == state.currentStreamUrl && it.addonName == addon
            }
            return matches.singleOrNull()?.stableKey(0)
        }

        /** Cached catalog data only. The movie estimate follows the main source screen policy. */
        fun context(type: String, nativeVideoId: String, season: Int?, episode: Int?,
            contentId: String?, cachedMeta: Meta?): PlayerSourceMetadataContext {
            val meta = cachedMeta?.takeIf { it.id == contentId }
            val episodic = season != null && episode != null
            val minutes = if (episodic) meta?.videos?.singleOrNull {
                it.id == nativeVideoId && it.season == season && it.episode == episode
            }?.runtime?.takeIf { it in 1..1_440 } else if (type == "movie" && season == null && episode == null && meta?.apiType == "movie") {
                // Bound the parser's arithmetic and reject signs/fractions rather than inventing a duration.
                meta.runtime?.trim()?.takeIf { it.matches(runtimeFormat) }?.let(::parseRuntimeMinutes)?.takeIf { it in 1..1_440 }
            } else null
            val origin = when {
                minutes == null -> null
                episodic -> StreamDurationOrigin.CATALOG_EPISODE
                else -> StreamDurationOrigin.CATALOG_MOVIE
            }
            return PlayerSourceMetadataContext(nativeVideoId, type, season, episode,
                minutes?.toLong()?.times(60_000L), origin)
        }
        private val runtimeFormat = Regex("(?:[0-9]{1,4}(?:\\s*m(?:in(?:ute)?s?)?)?|[0-9]{1,2}\\s*h(?:\\s*[0-9]{1,2}\\s*m(?:in(?:ute)?s?)?)?)", RegexOption.IGNORE_CASE)
    }
}
