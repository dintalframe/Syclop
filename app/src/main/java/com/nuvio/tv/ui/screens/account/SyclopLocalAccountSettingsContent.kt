@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun SyclopLocalAccountSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    onManageProfiles: () -> Unit,
    onOpenTracking: () -> Unit,
    onOpenIntegrations: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.syclop_local_profile_description), style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary)
        Text(stringResource(R.string.syclop_local_account_explanation), style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary)
        Button(onClick = onManageProfiles,
            modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier) {
            Text(stringResource(R.string.syclop_manage_local_profiles))
        }
        Button(onClick = onOpenTracking) { Text(stringResource(R.string.syclop_open_tracking_accounts)) }
        Button(onClick = onOpenIntegrations) { Text(stringResource(R.string.syclop_open_streaming_accounts)) }
    }
}
