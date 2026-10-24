@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.settings

import android.content.ActivityNotFoundException
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.core.backup.LocalBackupFileNames
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun BackupSettingsSection(viewModel: BackupSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalBackupFileNames.MIME_TYPE)) { uri -> if (uri != null) viewModel.export(uri) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.inspect(uri) }
    SettingsActionRow("Local backup and restore", "Profiles, supported settings, Library, watched history and resume progress. Account credentials are excluded.",
        value = "Manage", onClick = { visible = true; viewModel.open() })
    if (!visible) return
    NuvioDialog(onDismiss = { if (!state.busy) visible = false }, title = "Local backup and restore",
        subtitle = "Files stay on this TV or attached storage. No cloud backup service is used.", width = 820.dp, suppressFirstKeyUp = false) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("scope") {
                Text("Portable files include local profiles, supported appearance/player preferences, local Library, watched history and resume positions. Only Cinemeta, OpenSubtitles and Magnify Full public provider URLs are included. Accounts, private provider URLs, plug-ins, PINs, custom image URLs and playback diagnostics are excluded. Backup files contain viewing history; keep them private.",
                    style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
            }
            item("status") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.notice?.let { Text(it, style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary) }
                    state.error?.let { Text(it, style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.Error) }
                    if (state.busy) Text("Working…", style = NuvioTheme.textStyles.bodyCompact)
                }
            }
            if (state.pending) item("queued") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A restore or Undo is queued. It applies before accounts and settings open at the next cold app start.", style = NuvioTheme.textStyles.bodyCompact)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsDialogActionButton("Close app, then reopen", { Process.killProcess(Process.myPid()) }, enabled = !state.busy, primary = true)
                        SettingsDialogActionButton("Cancel queued operation", viewModel::cancel, enabled = !state.busy)
                    }
                }
            } else {
                item("export") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Create a portable backup", style = NuvioTheme.textStyles.sectionTitle)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingsDialogActionButton("Choose local destination", {
                                try { exportPicker.launch(viewModel.suggestedName()) } catch (_: ActivityNotFoundException) { viewModel.pickerUnavailable() }
                            }, enabled = !state.busy)
                            SettingsDialogActionButton("Save on this TV", { viewModel.export() }, enabled = !state.busy)
                        }
                    }
                }
                item("import") {
                    SettingsDialogActionButton("Choose local backup file", {
                        try { importPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                        catch (_: ActivityNotFoundException) { viewModel.pickerUnavailable() }
                    }, enabled = !state.busy)
                }
                items(state.files, key = { it.uri.toString() }) { file ->
                    SettingsActionRow(file.name, "Local Downloads/SyclopBackups", value = "Preview", onClick = { if (!state.busy) viewModel.inspect(file.uri) })
                }
                if (state.canUndo) item("undo") {
                    SettingsDialogActionButton("Undo previous restore", { confirmUndo = true }, enabled = !state.busy)
                }
            }
        }
    }
    val backup = state.preview
    if (backup != null) NuvioDialog(onDismiss = viewModel::dismissPreview, title = "Replace local data?", subtitle = "No changes have been made yet.", width = 760.dp, suppressFirstKeyUp = false) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 370.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Backup: ${backup.createdAt}\nProfiles: ${backup.profiles.joinToString { it.name }}\n${backup.stores.size} supported settings/data stores.", style = NuvioTheme.textStyles.bodyCompact) }
            item { Text("This replaces current profiles, local settings, Library and progress. All service accounts are disconnected, and private providers and plug-ins must be added again. Public application registrations stay on this TV. A private Undo snapshot retains the previous state and encrypted account connections on this installation; it is never included in portable exports.",
                style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsDialogActionButton("Queue restore", viewModel::confirmRestore, primary = true, enabled = !state.busy)
                SettingsDialogActionButton("Cancel", viewModel::dismissPreview, enabled = !state.busy)
            } }
        }
    }
    if (confirmUndo) NuvioDialog(onDismiss = { confirmUndo = false }, title = "Undo previous restore?", width = 720.dp, suppressFirstKeyUp = false) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("The private snapshot replaces current local data and restores the account connections from before the previous restore. This runs on the next cold app start. It cannot restore data after uninstalling Syclop or clearing app storage.", style = NuvioTheme.textStyles.bodyCompact)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsDialogActionButton("Queue Undo", { confirmUndo = false; viewModel.queueUndo() }, enabled = !state.busy)
                SettingsDialogActionButton("Cancel", { confirmUndo = false })
            }
        }
    }
}
