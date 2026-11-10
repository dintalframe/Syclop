@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.updater.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun UpdateUnknownSourcesDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val settingsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        settingsFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.update_title),
        subtitle = stringResource(R.string.update_unknown_sources),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.focusRequester(settingsFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.update_open_settings))
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.update_close))
            }
        }
    }
}
