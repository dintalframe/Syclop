package com.nuvio.tv.core.trakt

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video

enum class TraktActionKind(val apiType: String, val ratingsType: String) {
    MOVIE("movie", "movies"), SHOW("show", "shows"), EPISODE("episode", "episodes")
}
data class TraktActionTarget(val kind: TraktActionKind, val ids: TraktIdsDto, val title: String) {
    val canCheckin: Boolean get() = kind != TraktActionKind.SHOW
    override fun toString() = "TraktActionTarget(kind=$kind)"
}

/** Remote writes never use title search, inferred absolute anime numbers or index remapping. */
object TraktActionIdentity {
    private val imdb = Regex("tt[0-9]{5,12}")
    fun title(meta: Meta): TraktActionTarget? {
        val kind = when (meta.apiType) { "movie" -> TraktActionKind.MOVIE; "series" -> TraktActionKind.SHOW; else -> return null }
        val imdbId = meta.imdbId?.takeIf(imdb::matches) ?: meta.id.takeIf(imdb::matches)
        val ids = imdbId?.let { TraktIdsDto(imdb = it) } ?: parseNamespaced(meta.id, kind) ?: return null
        return TraktActionTarget(kind, ids, meta.name)
    }
    fun episode(meta: Meta, video: Video): TraktActionTarget? {
        if (meta.apiType != "series") return null
        // Cinemeta's show-imdb:season:episode is NOT an independently verified episode ID.
        val ownImdb = video.id.takeIf(imdb::matches)?.takeIf { it != meta.id && it != meta.imdbId }
        val ids = ownImdb?.let { TraktIdsDto(imdb = it) }
            ?: parseNamespaced(video.id, TraktActionKind.EPISODE) ?: return null
        return TraktActionTarget(TraktActionKind.EPISODE, ids, video.title)
    }
    private fun parseNamespaced(id: String, kind: TraktActionKind): TraktIdsDto? {
        val parts = id.split(':')
        // Generic trakt:<id>/tmdb:<id> are title IDs; episodes require an explicit type.
        val number = when {
            parts.size == 3 && parts[1] == kind.apiType -> parts[2].toIntOrNull()
            parts.size == 2 && kind != TraktActionKind.EPISODE -> parts[1].toIntOrNull()
            else -> null
        }?.takeIf { it > 0 } ?: return null
        return when (parts[0]) { "trakt" -> TraktIdsDto(trakt = number); "tmdb" -> TraktIdsDto(tmdb = number); else -> null }
    }
    fun matches(target: TraktActionTarget, ids: TraktIdsDto?): Boolean {
        ids ?: return false
        val expected = target.ids
        return when {
            expected.trakt != null -> expected.trakt == ids.trakt
            expected.imdb != null -> expected.imdb == ids.imdb
            expected.tmdb != null -> expected.tmdb == ids.tmdb
            else -> false
        }
    }
}
