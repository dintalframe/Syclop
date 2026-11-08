package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
internal fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    SettingsTextDialog(
        title = stringResource(R.string.privacy_policy_title),
        text = stringResource(R.string.privacy_policy_body),
        onDismiss = onDismiss
    )
}
