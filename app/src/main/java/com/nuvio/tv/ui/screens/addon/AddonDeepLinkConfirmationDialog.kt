package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonDeepLinkConfirmationDialog(host: String, onCancel: () -> Unit, onAdd: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.addon_link_title),
        subtitle = stringResource(R.string.addon_link_description, host)
    ) {
        LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = onAdd) {
                Text(stringResource(R.string.addon_link_add))
            }
        }
    }
}
