package com.nuvio.tv.updater.ui

import androidx.compose.runtime.Composable
import com.nuvio.tv.updater.InstalledUpdateNotes
import com.nuvio.tv.updater.UpdateUiState

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
    content()
    installedNotes?.let { InstalledUpdateNotesDialog(it, onDismissInstalledNotes) }
}
