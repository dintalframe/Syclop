@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    AppControlSettingsActionContent(
        modifier = modifier,
        title = stringResource(R.string.syclop_restart_app),
        subtitle = stringResource(R.string.syclop_restart_app_subtitle),
        confirmationTitle = stringResource(R.string.syclop_restart_app_confirm_title),
        confirmationDescription = stringResource(R.string.syclop_restart_app_confirm_description),
        failureMessage = stringResource(R.string.syclop_restart_app_failed),
        icon = Icons.Default.RestartAlt,
        onActivate = onRestart
    )
}

@Composable
internal fun AppShutdownSettingsAction(
    modifier: Modifier = Modifier,
    viewModel: AppShutdownViewModel = hiltViewModel()
) {
    val activity = LocalContext.current.findAppControlActivity()
    AppShutdownSettingsActionContent(
        modifier = modifier,
        onShutdown = { activity?.let(viewModel::shutdown) ?: false }
    )
}

@Composable
internal fun AppShutdownSettingsActionContent(
    modifier: Modifier = Modifier,
    onShutdown: () -> Boolean
) {
    AppControlSettingsActionContent(
        modifier = modifier,
        title = stringResource(R.string.syclop_shutdown_app),
        subtitle = stringResource(R.string.syclop_shutdown_app_subtitle),
        confirmationTitle = stringResource(R.string.syclop_shutdown_app_confirm_title),
        confirmationDescription = stringResource(R.string.syclop_shutdown_app_confirm_description),
        failureMessage = stringResource(R.string.syclop_shutdown_app_failed),
        icon = Icons.Default.PowerSettingsNew,
        onActivate = onShutdown
    )
}

private tailrec fun Context.findAppControlActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findAppControlActivity()
    else -> null
}

@Composable
private fun AppControlSettingsActionContent(
    modifier: Modifier,
    title: String,
    subtitle: String,
    confirmationTitle: String,
    confirmationDescription: String,
    failureMessage: String,
    icon: ImageVector,
    onActivate: () -> Boolean
) {
    val context = LocalContext.current
    val actionFocusRequester = remember { FocusRequester() }
    var showConfirmation by remember { mutableStateOf(false) }
    var actionPending by remember { mutableStateOf(false) }
    var restoreActionFocus by remember { mutableStateOf(false) }

    LaunchedEffect(restoreActionFocus) {
        if (restoreActionFocus) {
            repeat(2) { withFrameNanos { } }
            actionFocusRequester.requestFocus()
            restoreActionFocus = false
        }
    }

    LaunchedEffect(actionPending) {
        if (actionPending) {
            // Detach the confirmation window before replacing or closing the activity task.
            repeat(2) { withFrameNanos { } }
            if (!onActivate()) {
                actionPending = false
                Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
                restoreActionFocus = true
            }
        }
    }

    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        trailingIcon = icon,
        enabled = !actionPending,
        modifier = modifier.focusRequester(actionFocusRequester),
        onClick = { showConfirmation = true }
    )

    if (showConfirmation) {
        AppControlConfirmationDialog(
            title = confirmationTitle,
            description = confirmationDescription,
            actionLabel = title,
            onConfirm = {
                showConfirmation = false
                actionPending = true
            },
            onDismiss = {
                showConfirmation = false
                restoreActionFocus = true
            }
        )
    }
}

@Composable
private fun AppControlConfirmationDialog(
    title: String,
    description: String,
    actionLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocusRequester.requestFocus() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = description
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
            ) { Text(actionLabel) }
        }
    }
}
