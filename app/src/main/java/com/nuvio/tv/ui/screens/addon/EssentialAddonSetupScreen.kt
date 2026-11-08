package com.nuvio.tv.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EssentialAddonSetupScreen(
    onSkip: () -> Unit,
    viewModel: AddonManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val phoneFocus = remember { FocusRequester() }
    val enterOnTvFocus = remember { FocusRequester() }
    val linkFocus = remember { FocusRequester() }
    var optionsFocus by remember { mutableStateOf(phoneFocus) }
    var showManualEntry by rememberSaveable { mutableStateOf(false) }
    var linkFocused by remember { mutableStateOf(false) }
    val showProviderOptions = showManualEntry && uiState.providerInstallOptions.isNotEmpty()

    val closeManualEntry: () -> Unit = {
        keyboardController?.hide()
        optionsFocus = enterOnTvFocus
        showManualEntry = false
    }
    val installLink: () -> Unit = {
        viewModel.installAddon()
        keyboardController?.hide()
    }

    BackHandler(enabled = showManualEntry && !showProviderOptions, onBack = closeManualEntry)

    LaunchedEffect(showManualEntry, uiState.isQrModeActive, showProviderOptions) {
        if (!uiState.isQrModeActive && !showProviderOptions) {
            withFrameNanos { }
            val requester = if (showManualEntry) linkFocus else optionsFocus
            requester.requestFocus()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    if (showManualEntry) R.string.essential_addon_link_title
                    else R.string.essential_addon_setup_title
                ),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    textDirection = TextDirection.Content
                ),
                color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.Center
            )

            if (showManualEntry) {
                BasicTextField(
                    value = uiState.installUrl,
                    onValueChange = viewModel::onInstallUrlChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .focusRequester(linkFocus)
                        .onFocusChanged { linkFocused = it.isFocused }
                        .border(
                            width = 2.dp,
                            color = if (linkFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.Border,
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { installLink() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary,
                        textDirection = TextDirection.Content
                    ),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (uiState.installUrl.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.addon_install_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextTertiary
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Button(
                    onClick = installLink,
                    enabled = !uiState.isInstalling,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            if (uiState.isInstalling) R.string.addon_installing
                            else R.string.addon_install_btn
                        )
                    )
                }
                Button(onClick = closeManualEntry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_back))
                }
            } else {
                Text(
                    text = stringResource(R.string.essential_addon_setup_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        optionsFocus = phoneFocus
                        viewModel.startQrMode(AddonWebConfigMode.ADDONS_ONLY)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus)
                ) {
                    Text(stringResource(R.string.essential_addon_show_qr))
                }
                Button(
                    onClick = { showManualEntry = true },
                    modifier = Modifier.fillMaxWidth().focusRequester(enterOnTvFocus)
                ) {
                    Text(stringResource(R.string.essential_addon_enter_link_on_tv))
                }
                Button(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    )
                ) {
                    Text(stringResource(R.string.essential_addon_continue_for_now))
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (uiState.isQrModeActive) {
            Popup(
                onDismissRequest = viewModel::stopQrMode,
                properties = PopupProperties(focusable = true, dismissOnClickOutside = false)
            ) {
                QrCodeOverlay(
                    qrBitmap = uiState.qrCodeBitmap,
                    serverUrl = uiState.serverUrl,
                    instruction = stringResource(R.string.addon_qr_addons_only_scan_instruction),
                    onClose = viewModel::stopQrMode,
                    hasPendingChange = uiState.pendingChange != null
                )
            }
        }

        uiState.pendingChange?.let { pending ->
            Popup(
                onDismissRequest = viewModel::rejectPendingChange,
                properties = PopupProperties(focusable = true, dismissOnClickOutside = false)
            ) {
                ConfirmAddonChangesDialog(
                    pendingChange = pending,
                    onConfirm = viewModel::confirmPendingChange,
                    onReject = viewModel::rejectPendingChange
                )
            }
        }

        if (showProviderOptions) {
            ProviderInstallOptionsDialog(
                options = uiState.providerInstallOptions,
                onSelect = viewModel::chooseProviderOption,
                onDismiss = viewModel::dismissProviderOptions
            )
        }

        AddonMessageOverlay(
            message = uiState.transientMessage,
            isError = uiState.transientMessageIsError
        )
    }
}
