package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.NativeDebridSourceCards
import com.nuvio.tv.ui.util.languageCodeToName

@Composable
fun StreamInfoOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    data: StreamInfoData?,
    hudEnabled: Boolean,
    hudButtonShown: Boolean,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hudFocusRequester = remember { FocusRequester() }
    var hudFocused by remember(visible) { mutableStateOf(false) }
    var hudPlaced by remember(visible) { mutableStateOf(false) }

    LaunchedEffect(visible, hudButtonShown, hudPlaced) {
        if (!visible || !hudButtonShown || !hudPlaced) return@LaunchedEffect
        hudFocusRequester.requestFocusAfterFrames(frames = 0)
    }
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        // The scaffold holds focus so the overlay still closes on centre, which leaves nothing
        // pointing at the button; a direction press is the request to reach it, and it also
        // recovers focus if anything else took it while the overlay stayed open.
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            val isDirection = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
            if (!isDirection || hudFocused || !hudButtonShown) return@onPreviewKeyEvent false
            runCatching { hudFocusRequester.requestFocus() }.isSuccess
        },
        dismissOnCenter = !hudButtonShown,
        contentPadding = PaddingValues(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = 36.dp, bottom = 36.dp)
    ) {
        // Someone who never turned the overlay on has no use for a control they cannot interpret.
        if (hudButtonShown) {
            StreamInfoHudButton(
                enabled = hudEnabled,
                onClick = onToggleHud,
                focusRequester = hudFocusRequester,
                onFocusChanged = { hudFocused = it },
                onLaidOut = { hudPlaced = true },
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
        if (data != null) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart),
                verticalArrangement = Arrangement.Bottom
            ) {
                StreamInfoContent(data = data)
            }
        }
    }
}

@Composable
private fun StreamInfoContent(data: StreamInfoData) {
    val mpv = data.mpvSnapshot
    val sourceName = NativeDebridSourceCards.displayText(data.streamName)
    val sourceDescription = NativeDebridSourceCards.displayText(data.streamDescription)
    // Recover vertical space for MPV diagnostics without adding another focus target or scroll trap.
    val sectionGap = if (mpv != null) 4.dp else NuvioTheme.spacing.lg
    // SOURCE section
    val hasSourceInfo = data.addonName != null || sourceName != null
    if (hasSourceInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_source))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!data.addonLogo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(data.addonLogo)
                        .crossfade(true)
                        .build(),
                    contentDescription = data.addonName,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.sm)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            }
            Column {
                if (data.addonName != null) {
                    Text(
                        text = data.addonName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (sourceName != null && sourceName != data.addonName) {
                    Text(
                        text = sourceName.replace("\n", " · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (!sourceDescription.isNullOrBlank()) {
            Text(
                text = sourceDescription.replace("\n", " · "),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }
        if (data.playerEngine != null || data.serverPlayback != null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                InfoItem(label = stringResource(R.string.stream_info_player_engine), value = data.playerEngine)
                InfoItem(
                    label = stringResource(R.string.stream_info_server),
                    value = data.serverPlayback,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        Spacer(modifier = Modifier.height(sectionGap))
    }

    // FILE section
    val hasFileInfo = data.filename != null || data.fileSize != null
    if (hasFileInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_file))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_filename), value = data.filename, modifier = Modifier.weight(1f))
            InfoItem(label = stringResource(R.string.stream_info_size), value = data.fileSize?.let { formatFileSize(it) })
        }
        Spacer(modifier = Modifier.height(sectionGap))
    }

    // VIDEO section
    val unknown = stringResource(R.string.syclop_mpv_unknown)
    val hasVideoInfo = mpv != null || data.videoCodec != null || data.videoWidth != null ||
        data.videoFrameRate != null || data.videoBitrate != null || data.fileBitrate != null
    if (hasVideoInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_video))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.videoCodec ?: unknown.takeIf { mpv != null })
            InfoItem(
                label = stringResource(R.string.stream_info_resolution),
                value = if (data.videoWidth != null && data.videoHeight != null) {
                    formatResolution(data.videoWidth, data.videoHeight)
                } else unknown.takeIf { mpv != null }
            )
            InfoItem(
                label = stringResource(if (mpv != null) R.string.syclop_mpv_source_fps else R.string.stream_info_frame_rate),
                value = data.videoFrameRate?.let { "%.3f fps".format(it) } ?: unknown.takeIf { mpv != null }
            )
            // A container that declares no track bitrate leaves only the whole file rate, which
            // includes audio and overhead, so it is labelled apart rather than shown as the video rate.
            if (data.videoBitrate != null) {
                InfoItem(
                    label = stringResource(R.string.stream_info_bitrate),
                    value = formatBitrate(data.videoBitrate)
                )
            } else {
                InfoItem(
                    label = stringResource(R.string.stream_info_bitrate_file),
                    value = data.fileBitrate?.let { formatBitrate(it) }
                )
            }
        }
        if (mpv != null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(text = stringResource(R.string.syclop_mpv_snapshot), style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                val decoder = when (mpv.activeDecoder) {
                    com.nuvio.tv.core.player.MpvInfoActiveDecoder.SOFTWARE -> stringResource(R.string.syclop_mpv_software)
                    com.nuvio.tv.core.player.MpvInfoActiveDecoder.MEDIACODEC -> "MediaCodec"
                    com.nuvio.tv.core.player.MpvInfoActiveDecoder.MEDIACODEC_COPY -> "MediaCodec (copy)"
                    null -> unknown
                }
                InfoItem(stringResource(R.string.syclop_mpv_active_decoder), decoder)
                InfoItem(stringResource(R.string.syclop_mpv_speed), mpv.speed?.let { "%.2f×".format(it) } ?: unknown)
                InfoItem(stringResource(R.string.syclop_mpv_estimated_fps), mpv.estimatedFrameRate?.let { "%.3f fps".format(it) } ?: unknown)
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                InfoItem(stringResource(R.string.syclop_mpv_frame_drops), "${mpv.droppedFrames ?: unknown} / ${mpv.decoderDroppedFrames ?: unknown}")
                InfoItem(stringResource(R.string.syclop_mpv_cache_buffer), mpv.cachedSeconds?.let { "%.1f s".format(it) } ?: unknown)
                InfoItem(stringResource(R.string.syclop_mpv_waiting_cache), when (mpv.waitingForCache) {
                    true -> stringResource(R.string.syclop_mpv_yes)
                    false -> stringResource(R.string.syclop_mpv_no)
                    null -> unknown
                })
            }
        }
        Spacer(modifier = Modifier.height(sectionGap))
    }

    // AUDIO section
    val hasAudioInfo = data.audioCodec != null || data.audioChannels != null || data.audioLanguage != null || data.audioSampleRate != null
    if (hasAudioInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_audio))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.audioCodec)
            InfoItem(label = stringResource(R.string.stream_info_channels), value = data.audioChannels)
            InfoItem(
                label = stringResource(R.string.stream_info_sample_rate),
                value = data.audioSampleRate?.let { "${it / 1000} kHz" }
            )
            InfoItem(
                label = stringResource(R.string.stream_info_language),
                value = data.audioLanguage?.let { languageCodeToName(it) }
            )
        }
        Spacer(modifier = Modifier.height(sectionGap))
    }

    // SUBTITLE section
    val hasSubtitleInfo = data.subtitleName != null || data.subtitleCodec != null || data.subtitleLanguage != null
    if (hasSubtitleInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_subtitle))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_name), value = data.subtitleName)
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.subtitleCodec)
            InfoItem(
                label = stringResource(R.string.stream_info_language),
                value = data.subtitleLanguage?.let { languageCodeToName(it) }
            )
            InfoItem(label = stringResource(R.string.stream_info_source), value = data.subtitleSource)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun InfoItem(label: String, value: String?, modifier: Modifier = Modifier) {
    if (value == null) return
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun formatFileSize(bytes: Long): String {
    return android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)
}

private fun formatBitrate(bps: Int): String {
    return when {
        bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f kbps".format(bps / 1_000.0)
        else -> "$bps bps"
    }
}

internal fun formatResolution(width: Int, height: Int): String {
    val maxDim = maxOf(width, height)
    val label = when {
        maxDim >= 3600 -> "4K"
        maxDim >= 2400 -> "1440p"
        maxDim >= 1800 -> "1080p"
        maxDim >= 1200 -> "720p"
        maxDim >= 800 -> "480p"
        else -> "${minOf(width, height)}p"
    }
    return "$width × $height ($label)"
}

@Composable
private fun StreamInfoHudButton(
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onLaidOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        // Every direction leads back to this button, so a stray press cannot drop focus onto the
        // transport controls still drawn behind the overlay.
        modifier = modifier
            .focusRequester(focusRequester)
            .onPlaced { onLaidOut() }
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (enabled) NuvioTheme.colors.Secondary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.7f),
            focusedContainerColor = if (enabled) NuvioTheme.colors.Secondary else Color.White,
            focusedContentColor = if (enabled) NuvioTheme.colors.OnSecondary else Color.Black
        ),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (enabled) NuvioTheme.colors.Secondary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
        )
    ) {
        val statusText = if (enabled) {
            stringResource(R.string.diag_value_on)
        } else {
            stringResource(R.string.diag_value_off)
        }
        val hudLabel = stringResource(R.string.stream_info_hud)

        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        if (isFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.FocusRing
                    } else {
                        if (isFocused) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.4f)
                    }
                )
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        Text(
            text = "$hudLabel: $statusText",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isFocused || enabled) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
