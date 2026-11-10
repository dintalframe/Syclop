package com.nuvio.tv.updater.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.updater.InstalledUpdateNotes
import com.nuvio.tv.updater.UpdateUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateBannerHost(
    state: UpdateUiState,
    onDismissBanner: () -> Unit,
    onDownload: () -> Unit,
    onDismissUnknownSources: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onFeedbackShown: () -> Unit,
    automaticCheckReady: Boolean = false,
    onAutomaticCheck: suspend () -> Unit = {},
    installedNotes: InstalledUpdateNotes? = null,
    onDismissInstalledNotes: () -> Unit = {},
    content: @Composable () -> Unit
) {
    if (automaticCheckReady) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val check = rememberUpdatedState(onAutomaticCheck)
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Let the navigation shell draw before starting the optional network request.
                repeat(2) { withFrameNanos {} }
                check.value()
            }
        }
    }
    val context = LocalContext.current
    LaunchedEffect(state.feedbackMessage) {
        val message = state.feedbackMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onFeedbackShown()
    }

    content()

    if (installedNotes != null) {
        InstalledUpdateNotesDialog(installedNotes, onDismissInstalledNotes)
        return
    }
    if (!state.showBanner) return
    if (state.showUnknownSourcesDialog) {
        UpdateUnknownSourcesDialog(onOpenSettings = onOpenUnknownSources, onDismiss = onDismissUnknownSources)
        return
    }
    val update = state.update ?: return
    val laterFocus = remember(update.tag) { FocusRequester() }
    val notesFocus = remember(update.tag) { FocusRequester() }
    var offerFocus by remember(update.tag) { mutableStateOf(laterFocus) }
    var showNotes by remember(update.tag) { mutableStateOf(false) }
    if (showNotes) {
        UpdateReleaseNotesDialog(title = stringResource(R.string.update_release_notes),
            subtitle = update.title, notes = update.notes, onDismiss = { showNotes = false })
        return
    }
    NuvioDialog(
        onDismiss = onDismissBanner,
        title = stringResource(R.string.update_available),
        subtitle = stringResource(
            R.string.update_version_range,
            BuildConfig.VERSION_NAME,
            update.tag.removePrefix("v")
        ),
        width = 560.dp
    ) {
        LaunchedEffect(update.tag) { offerFocus.requestFocusAfterFrames() }
        Text(
            text = update.notes.trim().ifBlank { stringResource(R.string.update_no_release_notes) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        if (state.isDownloading) {
            val progress = state.downloadProgress
            Text(if (progress == null) stringResource(R.string.update_preparing_download)
                else stringResource(R.string.update_downloading_progress, (progress * 100).roundToInt()))
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismissBanner, modifier = Modifier.focusRequester(laterFocus)) {
                Text(stringResource(R.string.update_later))
            }
            Button(onClick = onDownload, enabled = !state.isDownloading) {
                Text(stringResource(R.string.update_now))
            }
            Button(
                onClick = { offerFocus = notesFocus; showNotes = true },
                modifier = Modifier.focusRequester(notesFocus)
            ) {
                Text(stringResource(R.string.update_release_notes))
            }
        }
    }
}
