package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.components.StreamImportedBadgeChip
import com.nuvio.tv.ui.util.languageCodeToName

@Composable
internal fun rememberPlayerBadges(uiState: PlayerUiState, mediaInfo: PlayerMediaInfo): List<StreamBadge> {
    val filters = uiState.playerBadgeFilters
    if (filters.isEmpty()) return emptyList()
    val language = mediaInfo.audioLanguage
    val text = playbackMatchText(mediaInfo, listOfNotNull(language, language?.let(::languageCodeToName)))
    return remember(text, filters) {
        StreamBadgeMatcher.matchedBadges(listOfNotNull(text.takeIf { it.isNotBlank() }), filters)
            .filter { it.imageURL.isNotBlank() }
    }
}

@Composable
internal fun rememberPlaybackBadgeMediaInfo(viewModel: PlayerViewModel, uiState: PlayerUiState): PlayerMediaInfo {
    var mediaInfo by remember(uiState.currentStreamUrl, uiState.internalPlayerEngine) { mutableStateOf(PlayerMediaInfo()) }
    val selectedAudio = uiState.audioTracks.firstOrNull { it.isSelected }
    LaunchedEffect(
        uiState.showControls,
        uiState.isBuffering,
        uiState.currentStreamUrl,
        uiState.internalPlayerEngine,
        selectedAudio?.index
    ) {
        if (uiState.showControls && !uiState.isBuffering) {
            mediaInfo = viewModel.playerMediaInfo()
        }
    }
    val current = uiState.badgeMediaSnapshot?.forPlayback(uiState.internalPlayerEngine, uiState.currentStreamUrl) ?: mediaInfo
    return current.copy(audioLanguage = current.audioLanguage ?: selectedAudio?.language)
}

@Composable
internal fun PlayerBadges(
    badges: List<StreamBadge>,
    modifier: Modifier = Modifier
) {
    Layout(
        content = { badges.forEach { StreamImportedBadgeChip(badge = it, preferEmbeddedArtwork = true) } },
        modifier = modifier
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        var width = 0
        var visibleCount = 0
        for (placeable in placeables) {
            val next = if (visibleCount == 0) placeable.width else width + gap + placeable.width
            if (next > constraints.maxWidth) break
            width = next
            visibleCount++
        }
        val visible = placeables.take(visibleCount)
        val height = visible.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            visible.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}
