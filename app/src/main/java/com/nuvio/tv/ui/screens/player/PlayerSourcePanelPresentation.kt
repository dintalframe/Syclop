package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream

/** Display-only projection; never rewrites streams used by native resolution or bookkeeping. */
internal class PlayerSourcePanelPresentation(
    private val rankAll: (List<Stream>, DebridSettings) -> List<Stream>
) {
    fun apply(state: PlayerUiState, settings: DebridSettings, isCurrent: () -> Boolean): PlayerUiState {
        if (!isCurrent()) return withoutSources(state)
        val sources = rankAll(state.sourceAllStreams, settings)
        val episodes = rankAll(state.episodeAllStreams, settings)
        if (!isCurrent()) return withoutSources(state)
        val filteredSources = sources.filterForAddon(state.sourceSelectedAddonFilter)
        // The controller owns page expansion. Rank the complete list first, then preserve its
        // requested page size so hidden raw entries cannot consume the visible first page.
        val sourcePage = filteredSources.take(state.sourceFilteredStreams.size)
        val sourceNames = sources.mapTo(mutableSetOf()) { it.addonName }
        val episodeNames = episodes.mapTo(mutableSetOf()) { it.addonName }
        return state.copy(
            sourceAllStreams = sources,
            sourceFilteredStreams = sourcePage,
            sourceAvailableAddons = (state.sourceAvailableAddons + sourceNames).distinct().filter { it in sourceNames },
            episodeAllStreams = episodes,
            episodeFilteredStreams = episodes.filterForAddon(state.episodeSelectedAddonFilter),
            episodeAvailableAddons = (state.episodeAvailableAddons + episodeNames).distinct().filter { it in episodeNames }
        )
    }

    private fun List<Stream>.filterForAddon(addon: String?): List<Stream> =
        if (addon == null) this else filter { it.addonName == addon }

    companion object {
        fun withoutSources(state: PlayerUiState): PlayerUiState = state.copy(
            sourceAllStreams = emptyList(), sourceFilteredStreams = emptyList(),
            sourceAvailableAddons = emptyList(), sourceChips = emptyList(),
            episodeAllStreams = emptyList(), episodeFilteredStreams = emptyList(),
            episodeAvailableAddons = emptyList(), episodeSourceChips = emptyList()
        )
    }
}
