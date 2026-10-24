package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.player.ExternalPlayerCatalog
import com.nuvio.tv.core.player.ExternalPlayerChoiceRules
import com.nuvio.tv.data.local.PlayerSettings

@Composable
internal fun ExternalPlayerSelectionRow(settings: PlayerSettings, onOpenDialog: (PlaybackDialog) -> Unit) {
    val context = LocalContext.current
    val installed = remember(context, settings.externalPlayerPackage) { ExternalPlayerCatalog.installed(context) }
    val selected = installed.firstOrNull { it.packageName == settings.externalPlayerPackage }
    val name = selected?.label ?: settings.externalPlayerPackage?.let { ExternalPlayerChoiceRules.knownLabel(it) ?: it }
    SettingsActionRow(
        title = stringResource(R.string.playback_external_app),
        subtitle = stringResource(R.string.playback_external_app_sub),
        value = when {
            name == null -> stringResource(R.string.playback_external_system)
            selected == null -> stringResource(R.string.playback_external_unavailable, name)
            else -> name
        },
        onClick = { onOpenDialog(PlaybackDialog.EXTERNAL_APP) }
    )
}

@Composable
internal fun ExternalPlayerSelectionDialog(selectedPackage: String?, onSelected: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // This dialog is recreated on each opening, so returning from installing an app refreshes the list.
    val installed = remember(context) { ExternalPlayerCatalog.installed(context) }
    val savedMissing = selectedPackage != null && installed.none { it.packageName == selectedPackage }
    val options = buildList<SettingsPickerOption<String?>> {
        add(SettingsPickerOption(null, stringResource(R.string.playback_external_system), stringResource(R.string.playback_external_system_sub)))
        installed.forEach { app -> add(SettingsPickerOption(app.packageName, app.label, stringResource(R.string.playback_external_progress_note))) }
    }
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.playback_external_app),
        subtitle = stringResource(if (savedMissing) R.string.playback_external_missing_note else R.string.playback_external_installed_note),
        options = options,
        selectedValue = selectedPackage,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 480.dp,
        maxHeight = 320.dp
    )
}
