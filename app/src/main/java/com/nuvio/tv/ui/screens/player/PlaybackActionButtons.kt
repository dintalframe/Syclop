package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

internal fun PlayerUiState.canManuallyPlayNextEpisode(): Boolean =
    !isLive && nextEpisode?.hasAired == true &&
        !(nextEpisode.released.isNullOrBlank() && nextEpisode.available == false) &&
        (postPlayMode as? PostPlayMode.AutoPlay)?.let {
            !it.searching && it.countdownSec == null
        } != false

/** Text labels keep playback actions discoverable with an ordinary TV remote. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlaybackActionButtons(
    showStartFromBeginning: Boolean,
    showNextEpisode: Boolean,
    onStartFromBeginning: () -> Unit,
    onNextEpisode: () -> Unit,
    modifier: Modifier = Modifier,
    startFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onUpAtTop: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null
) {
    val actionModifier = Modifier
        .focusProperties {
            downFocusRequester?.let { down = it }
            upFocusRequester?.let { up = it }
        }
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                upFocusRequester == null && onUpAtTop != null
            ) {
                onUpAtTop()
                true
            } else false
        }
        .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
    val colors = ButtonDefaults.colors(
        containerColor = Color.White.copy(alpha = 0.12f),
        contentColor = Color.White,
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        if (showStartFromBeginning) {
            Button(
                onClick = onStartFromBeginning,
                modifier = actionModifier.then(
                    if (startFocusRequester != null) Modifier.focusRequester(startFocusRequester) else Modifier
                ),
                colors = colors
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cw_action_start_from_beginning), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (showNextEpisode) {
            Button(onClick = onNextEpisode, modifier = actionModifier, colors = colors) {
                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.next_episode_label), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
