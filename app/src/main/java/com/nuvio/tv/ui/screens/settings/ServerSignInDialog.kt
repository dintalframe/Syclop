@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerTransportPolicy
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

internal data class ServerSignInRequest(
    val provider: ServerProvider,
    val address: String = "",
    val username: String = ""
)

@Composable
internal fun ServerSignInDialog(
    request: ServerSignInRequest,
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.signIn.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(request.address) }
    var username by remember { mutableStateOf(request.username) }
    var password by remember { mutableStateOf("") }
    var confirmLocalHttp by remember(address, username, password) { mutableStateOf(false) }
    val provider = request.provider
    val canConnect = !state.connecting && address.isNotBlank() && username.isNotBlank()
    val connect = {
        if (canConnect) {
            if (ServerTransportPolicy.localHttpHost(address) != null) {
                confirmLocalHttp = true
            } else {
                viewModel.connect(provider, address, username, password) { connection ->
                    password = ""
                    onConnected(connection)
                }
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.resetSignIn() }

    NuvioDialog(
        onDismiss = { if (!state.connecting) onDismiss() },
        title = stringResource(R.string.servers_sign_in_title, provider.displayName),
        subtitle = stringResource(R.string.servers_sign_in_subtitle, provider.displayName),
        width = 640.dp
    ) {
        SignInField(label = stringResource(R.string.servers_address)) {
            InputField(
                value = address,
                onValueChange = { address = it },
                placeholder = stringResource(R.string.servers_address_hint),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SignInField(label = stringResource(R.string.servers_username)) {
            InputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.servers_username),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SignInField(label = stringResource(R.string.servers_password)) {
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.servers_password),
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = connect,
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.error?.let { failure ->
            Text(
                text = failure.signInMessage(provider),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = !state.connecting
            )
            SettingsDialogActionButton(
                text = stringResource(if (state.connecting) R.string.servers_connecting else R.string.servers_connect),
                onClick = connect,
                primary = true,
                enabled = canConnect
            )
        }
    }
    if (confirmLocalHttp) {
        ServerLocalHttpConfirmationDialog(
            host = ServerTransportPolicy.localHttpHost(address).orEmpty(),
            onCancel = { confirmLocalHttp = false },
            onAllow = {
                confirmLocalHttp = false
                viewModel.connect(provider, address, username, password, localHttpApproved = true) { connection ->
                    password = ""
                    onConnected(connection)
                }
            }
        )
    }
}

@Composable
internal fun ServerLocalHttpConfirmationDialog(host: String, onCancel: () -> Unit, onAllow: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.servers_local_http_title),
        subtitle = stringResource(R.string.servers_local_http_description, host)
    ) {
        LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = onAllow) {
                Text(stringResource(R.string.servers_local_http_allow))
            }
        }
    }
}

@Composable
private fun SignInField(label: String, field: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        field()
    }
}

@Composable
private fun ServerFailure.signInMessage(provider: ServerProvider): String = when (this) {
    ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_error_auth)
    ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
    ServerFailure.UNSUPPORTED -> stringResource(R.string.servers_error_unsupported, provider.displayName, provider.minimumVersion)
    ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_forbidden)
    ServerFailure.INSECURE_CONNECTION -> stringResource(R.string.servers_error_insecure_connection)
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> stringResource(R.string.servers_error_failed)
}
