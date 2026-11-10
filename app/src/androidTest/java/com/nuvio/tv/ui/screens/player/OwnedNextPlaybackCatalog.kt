package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject

/** Two authored logical episodes over hash-bound owned media; never delegates a catalog request. */
internal class OwnedNextPlaybackCatalog(private val firstId: String, private val secondId: String,
    private val nextInputUrl: String) {
    val nextRequests = AtomicInteger()
    private val audit = mutableListOf<JSONObject>()
    private val meta = Meta(id = firstId, type = ContentType.SERIES, name = "Owned two-episode controller fixture",
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = "Authored logical episode routing; same-content remux, not distinct episode-content proof.",
        releaseInfo = "2020", imdbRating = null, genres = emptyList(), runtime = null,
        director = emptyList(), cast = emptyList(), country = null, awards = null, language = null,
        links = emptyList(), videos = listOf(
            Video(firstId, "Owned episode 1", "2020-01-01T00:00:00.000Z", null,
                season = 1, episode = 1, overview = "Original owned track order.", available = true),
            Video(secondId, "Owned episode 2", "2020-01-02T00:00:00.000Z", null,
                season = 1, episode = 2, overview = "Same owned samples with reversed track order and defaults.", available = true)))

    private fun requireMeta(type: String, id: String) {
        check(type == "series" && id == firstId) { "Unexpected owned metadata request; no network delegation exists." }
    }
    private fun requireNext(type: String, videoId: String, season: Int?, episode: Int?) {
        check(type == "series" && videoId == secondId && season == 1 && episode == 2) {
            "Unexpected owned stream request; no network delegation exists."
        }
    }
    private fun record(kind: String) = synchronized(audit) {
        audit += JSONObject().put("sequence", audit.size + 1).put("kind", kind).put("exactRequestMatches", true)
    }
    fun snapshotAudit() = synchronized(audit) { JSONArray(audit.toList()) }

    val metadata = object : MetaRepository {
        override fun getMeta(addonBaseUrl: String, type: String, id: String) =
            error("Unexpected addon metadata request; no owned addon is installed.")
        override fun getMetaFromAllAddons(type: String, id: String, sourceAddonBaseUrl: String?) = flowOf(
            NetworkResult.Success(meta.also { requireMeta(type, id); check(sourceAddonBaseUrl == null); record("metadata-all") }))
        override fun getMetaFromPrimaryAddon(type: String, id: String) =
            error("Unexpected primary-addon metadata request; no owned addon is installed.")
        override fun getCachedMeta(type: String, id: String): Meta {
            requireMeta(type, id)
            record("metadata-cache")
            return meta
        }
        override fun clearCache() = Unit
    }
    val streams = object : StreamRepository {
        override fun setLocalPluginSearchPaused(paused: Boolean) { record(if (paused) "plugins-paused" else "plugins-unpaused") }
        override fun getStreamsFromAllAddons(type: String, videoId: String, season: Int?, episode: Int?, forceRefresh: Boolean) = flowOf(
            NetworkResult.Success(listOf(AddonStreams("Owned fixture", null, listOf(episodeStream(nextInputUrl, 2))))).also {
                requireNext(type, videoId, season, episode)
                nextRequests.incrementAndGet()
                record("streams-exact-e2")
            })
        override suspend fun getCachedStreamsFromAllAddons(type: String, videoId: String, season: Int?, episode: Int?): List<AddonStreams>? {
            requireNext(type, videoId, season, episode)
            record("streams-cache-miss-e2")
            return null
        }
        override suspend fun getStreamsFromAddon(addon: Addon, type: String, videoId: String): NetworkResult<List<Stream>> =
            error("Unexpected addon stream request; no owned addon is installed.")
    }

    fun episodeStream(inputUrl: String, episode: Int): Stream {
        check(episode in 1..2 && (episode != 2 || inputUrl == nextInputUrl))
        val filename = if (episode == 1) FIRST_FILENAME else NEXT_FILENAME
        val id = if (episode == 1) firstId else secondId
        return Stream(name = "Owned fixture", title = "Owned logical episode $episode", description = null,
            url = inputUrl, ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(null, null, null, null, filename = filename),
            addonName = "Owned fixture", addonLogo = null,
            clientResolve = StreamClientResolve(type = "hoster", infoHash = null, fileIdx = null, magnetUri = null,
                sources = null, torrentName = null, filename = filename, mediaType = "series", mediaId = id,
                mediaOnlyId = firstId, title = "Owned logical episode $episode", season = 1, episode = episode,
                service = DebridProviders.REAL_DEBRID_ID, serviceIndex = null, serviceExtension = null, isCached = true))
    }
    companion object {
        const val FIRST_FILENAME = "owned-tr-en-130s.mp4"
        const val NEXT_FILENAME = "owned-en-tr-130s.mp4"
    }
}
