package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchedHistoryItem
import java.util.Locale

/** Shares browse admission rules without changing a history or resume card's original payload. */
internal object HomeTrackingRowRules {
    fun history(
        section: HomeBrowseSection,
        items: List<WatchedHistoryItem>,
        metadata: List<MetaPreview> = emptyList()
    ): List<WatchedHistoryItem> {
        if (section == HomeBrowseSection.HOME) return items
        val lookup = MetadataLookup(metadata)
        return items.filter { item -> lookup.matches(section, preview(
            item.contentId, item.contentType, item.title, item.genres, item.ageRating, item.country,
            item.imdbId, item.sourceAddonBaseUrl
        )) }
    }

    fun continueWatching(
        section: HomeBrowseSection,
        items: List<ContinueWatchingItem>,
        metadata: List<MetaPreview> = emptyList()
    ): List<ContinueWatchingItem> {
        if (section == HomeBrowseSection.HOME) return items
        val lookup = MetadataLookup(metadata)
        return items.filter { item ->
            val preview = when (item) {
                is ContinueWatchingItem.InProgress -> preview(item.progress.contentId,
                    item.progress.contentType, item.progress.name, item.genres)
                is ContinueWatchingItem.NextUp -> preview(item.info.contentId,
                    item.info.contentType, item.info.name, item.info.genres)
            }
            lookup.matches(section, preview)
        }
    }

    private class MetadataLookup(metadata: List<MetaPreview>) {
        private val byIdentity = buildMap<String, MutableList<MetaPreview>> {
            metadata.forEach { item ->
                identities(item).forEach { identity -> getOrPut(identity) { mutableListOf() }.add(item) }
            }
        }

        fun matches(section: HomeBrowseSection, item: MetaPreview): Boolean {
            val known = identities(item).flatMap { byIdentity[it].orEmpty() }.distinct()
            val genres = (item.genres + known.flatMap { it.genres }).distinct()
            val certificates = (listOfNotNull(item.ageRating) + known.mapNotNull { it.ageRating })
                .map(String::trim).filter(String::isNotEmpty)
            // Conflicting older/unknown certificates cannot be overridden by another source's G.
            val kidsCertificate = certificates.firstOrNull()?.takeIf {
                certificates.all { certificate ->
                    HomeBrowseRules.isRatedForKidsThroughSeven(item.copy(ageRating = certificate))
                }
            }
            // Anime evidence may be a native identifier/provenance on a verified IMDb sibling.
            // Country, language, title and Animation alone never establish that classification.
            val anime = HomeBrowseRules.isAnime(item) || known.any { HomeBrowseRules.isAnime(it) }
            val classified = item.copy(
                genres = if (anime) genres + "Anime" else genres,
                ageRating = kidsCertificate
            )
            return HomeBrowseRules.matches(section, classified)
        }
    }

    private fun preview(id: String, rawType: String, title: String, genres: List<String>,
        certificate: String? = null, country: String? = null, imdbId: String? = null,
        sourceAddonBaseUrl: String? = null) = MetaPreview(
        id = id, type = ContentType.fromString(rawType), rawType = rawType, name = title,
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = genres,
        ageRating = certificate, country = country, imdbId = imdbId,
        sourceAddonBaseUrl = sourceAddonBaseUrl
    )

    /** Namespace and media type remain part of the key; custom IDs are matched exactly. */
    private fun identities(item: MetaPreview): Set<String> {
        val family = when (item.type) {
            ContentType.SERIES, ContentType.TV -> "series"
            ContentType.UNKNOWN -> "unknown:${item.rawType.trim().lowercase(Locale.ROOT)}"
            else -> item.type.name
        }
        return buildSet {
            fun addId(raw: String?) {
                val id = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isISOControl) }
                    ?: return
                add("$family|exact:$id")
                imdb.matchEntire(id)?.let { add("$family|imdb:${it.groupValues[1].lowercase(Locale.ROOT)}") }
                numbered.matchEntire(id)?.let {
                    add("$family|${it.groupValues[1].lowercase(Locale.ROOT)}:${it.groupValues[2]}")
                }
            }
            addId(item.id)
            addId(item.imdbId)
        }
    }

    private val imdb = Regex("(?i)^(?:imdb:)?(tt[0-9]{5,12})(?::[0-9]+(?::[0-9]+)?)?$")
    private val numbered = Regex("(?i)^(tmdb|tvdb|trakt|simkl|mal|kitsu|anilist|anidb):([0-9]+)(?::[0-9]+(?::[0-9]+)?)?$")
}
