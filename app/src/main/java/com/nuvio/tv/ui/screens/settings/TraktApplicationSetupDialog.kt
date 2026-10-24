@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.trakt.TraktAppRegistrationStore
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TraktApplicationSetupViewModel @Inject constructor(
    private val registrationStore: TraktAppRegistrationStore
) : ViewModel() {
    fun current() = registrationStore.current()
    fun save(clientId: String, redirectUri: String): Result<Unit> = runCatching {
        registrationStore.save(clientId, redirectUri)
    }
}

/** Maintainer setup is separate from each viewer's normal device-code account sign-in. */
@Composable
internal fun TraktApplicationSetupDialog(
    onDismiss: () -> Unit,
    viewModel: TraktApplicationSetupViewModel = hiltViewModel()
) {
    val registration = remember { viewModel.current() }
    var clientId by remember { mutableStateOf(registration.clientId) }
    var redirectUri by remember { mutableStateOf(registration.redirectUri) }
    var error by remember { mutableStateOf<String?>(null) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.syclop_trakt_application_setup),
        width = 720.dp,
        suppressFirstKeyUp = false
    ) {
        Text(stringResource(R.string.syclop_trakt_application_setup_description),
            style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        ApplicationSetupField(stringResource(R.string.syclop_trakt_client_id), clientId) { clientId = it }
        ApplicationSetupField(stringResource(R.string.syclop_trakt_redirect_uri), redirectUri) { redirectUri = it }
        Text(stringResource(R.string.syclop_trakt_application_setup_help),
            style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        error?.let { Text(it, color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodySmall) }
        SettingsDialogActionRow {
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(stringResource(R.string.action_clear), onClick = { clientId = ""; error = null })
            SettingsDialogActionButton(stringResource(R.string.action_save), primary = true, onClick = {
                viewModel.save(clientId, redirectUri).fold(onSuccess = { onDismiss() }, onFailure = { error = it.message })
            })
        }
    }
}

@Composable
private fun ApplicationSetupField(label: String, value: String, onValueChange: (String) -> Unit) {
    val inputFocus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextPrimary)
        Card(
            onClick = { inputFocus.requestFocus() },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(Modifier.fillMaxWidth().padding(14.dp)) {
                BasicTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary))
            }
        }
    }
}
