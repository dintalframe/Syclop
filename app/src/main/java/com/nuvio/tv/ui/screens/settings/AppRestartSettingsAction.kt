@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun AppRestartSettingsAction(
    modifier: Modifier = Modifier,
    viewModel: AppRestartViewModel = hiltViewModel()
) {
    AppRestartSettingsActionContent(modifier = modifier, onRestart = viewModel::restart)
}

@Composable
internal fun AppRestartSettingsActionContent(
    modifier: Modifier = Modifier,
    onRestart: () -> Boolean
) {
    val context = LocalContext.current
    val failureMessage = stringResource(R.string.syclop_restart_app_failed)
    val actionFocusRequester = remember { FocusRequester() }
    var showConfirmation by remember { mutableStateOf(false) }
    var restartPending by remember { mutableStateOf(false) }
    var restoreActionFocus by remember { mutableStateOf(false) }

    LaunchedEffect(restoreActionFocus) {
        if (restoreActionFocus) {
            repeat(2) { withFrameNanos { } }
            actionFocusRequester.requestFocus()
            restoreActionFocus = false
        }
    }

    LaunchedEffect(restartPending) {
        if (restartPending) {
            // Detach the confirmation window before replacing the activity task.
            repeat(2) { withFrameNanos { } }
            if (!onRestart()) {
                restartPending = false
                Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
                restoreActionFocus = true
            }
        }
    }

    SettingsActionRow(
        title = stringResource(R.string.syclop_restart_app),
        subtitle = stringResource(R.string.syclop_restart_app_subtitle),
        trailingIcon = Icons.Default.RestartAlt,
        enabled = !restartPending,
        modifier = modifier.focusRequester(actionFocusRequester),
        onClick = { showConfirmation = true }
    )

    if (showConfirmation) {
        AppRestartConfirmationDialog(
            onConfirm = {
                showConfirmation = false
                restartPending = true
            },
            onDismiss = {
                showConfirmation = false
                restoreActionFocus = true
            }
        )
    }
}

@Composable
private fun AppRestartConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocusRequester.requestFocus() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.syclop_restart_app_confirm_title),
        subtitle = stringResource(R.string.syclop_restart_app_confirm_description)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).focusRequester(cancelFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.syclop_restart_app)) }
        }
    }
}
