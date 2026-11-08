package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizeEpisodeTitle

@Composable
internal fun PlayerControlCaption(label: String?) {
    Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
        if (label != null) {
            Text(
                text = label,
                modifier = Modifier.semantics { hideFromAccessibility() },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun PlayerControlsBottomLayout(
    modifier: Modifier = Modifier,
    onColumnPositioned: (LayoutCoordinates) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val controlsHeight = remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier.fillMaxSize().drawWithCache {
            val measuredHeight = controlsHeight.intValue.toFloat()
            val fadeLimit = 72.dp.toPx()
            val scrimHeight = maxOf(200.dp.toPx(), measuredHeight + fadeLimit).coerceAtMost(size.height)
            if (scrimHeight <= 0f) {
                onDrawBehind { }
            } else {
                val top = size.height - scrimHeight
                // Keep the fade above the controls: a taller linear fade would still
                // leave the headline almost unprotected on a white frame.
                val fade = minOf(fadeLimit, (scrimHeight - measuredHeight).coerceAtLeast(0f))
                val dark = Color.Black.copy(alpha = 0.72f)
                val bottom = Color.Black.copy(alpha = 0.8f)
                val brush = if (fade > 0f) {
                    Brush.verticalGradient(
                        0f to Color.Transparent, fade / scrimHeight to dark, 1f to bottom,
                        startY = top, endY = size.height
                    )
                } else {
                    Brush.verticalGradient(listOf(dark, bottom), startY = top, endY = size.height)
                }
                onDrawBehind {
                    drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, scrimHeight))
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onGloballyPositioned {
                    controlsHeight.intValue = it.size.height
                    onColumnPositioned(it)
                }
                .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
            content = content
        )
    }
}

@Composable
internal fun PlayerPlaybackMetadataHeader(
    uiState: PlayerUiState,
    playerBadges: List<StreamBadge>,
    playbackBadgeMediaInfo: PlayerMediaInfo,
    titleRights: MutableMap<String, Float>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val displayName = if (uiState.currentEpisode != null) {
            uiState.contentName ?: uiState.title
        } else {
            uiState.title
        }

        Text(
            text = displayName,
            modifier = Modifier.reportRightEdge("name", titleRights),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (uiState.currentEpisode != null) {
            val seasonEpisodeCode = if (uiState.currentSeason == null) {
                stringResource(R.string.playback_episode_number, uiState.currentEpisode)
            } else {
                stringResource(R.string.season_episode_format, uiState.currentSeason, uiState.currentEpisode)
            }
            val appContext = LocalContext.current
            val localizedEpisodeTitle = uiState.currentEpisodeTitle
                ?.takeIf { it.isNotBlank() }
                ?.localizeEpisodeTitle(appContext)
            val episodeInfo = if (localizedEpisodeTitle != null) {
                "$seasonEpisodeCode • $localizedEpisodeTitle"
            } else {
                seasonEpisodeCode
            }
            Text(
                text = episodeInfo,
                modifier = Modifier.reportRightEdge("episode", titleRights),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (playerBadges.isEmpty()) {
            PlayerTechnicalBadges(
                info = playbackBadgeMediaInfo,
                modifier = Modifier
                    .padding(vertical = NuvioTheme.spacing.xs)
                    .reportRightEdge("technicalBadges", titleRights)
            )
        }

        if (playerBadges.isNotEmpty()) {
            PlayerBadges(
                badges = playerBadges,
                modifier = Modifier
                    .padding(vertical = NuvioTheme.spacing.xs)
                    .reportRightEdge("badges", titleRights)
            )
        }

        if (!uiState.releaseYear.isNullOrBlank()) {
            Text(
                text = uiState.releaseYear.orEmpty(),
                modifier = Modifier.reportRightEdge("year", titleRights),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f)
            )
        }
    }
}

@Composable
private fun Modifier.reportRightEdge(key: String, rights: MutableMap<String, Float>): Modifier {
    DisposableEffect(key, rights) {
        onDispose { rights.remove(key) }
    }
    return onGloballyPositioned { rights[key] = it.boundsInWindow().right }
}
