@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.mal.MalAnime
import com.nuvio.tv.data.mal.MalAuthorizationSession
import com.nuvio.tv.data.mal.MalListStatus
import com.nuvio.tv.data.mal.MalListEdit
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/** An independent MAL account/list UI; no changes to Trakt, Simkl or their selected sources. */
@Composable
internal fun MalSettingsSection(viewModel: MalSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(state.account.scope.profileId) { showing = false; viewModel.close() }
    SettingsActionRow(
        title = stringResource(R.string.syclop_mal_name),
        subtitle = state.account.username ?: stringResource(R.string.syclop_mal_summary),
        value = if (state.account.connected) "Connected" else "Connect",
        onClick = { showing = true; viewModel.open() }
    )
    if (showing) MalAccountDialog(state, viewModel) { showing = false; viewModel.close() }
}

@Composable
private fun MalAccountDialog(state: MalSettingsUiState, viewModel: MalSettingsViewModel, dismiss: () -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    var clientId by remember(state.account.configuration.clientId) { mutableStateOf(state.account.configuration.clientId) }
    var redirect by remember(state.account.configuration.redirectUri) {
        mutableStateOf(state.account.configuration.redirectUri.ifBlank(viewModel::suggestedRedirect))
    }
    var query by remember(state.account.scope) { mutableStateOf("") }
    var editing by remember(state.account.scope) { mutableStateOf<MalAnime?>(null) }
    val visibleItems = state.searchResults ?: state.library.items
    DisposableEffect(Unit) { onDispose { viewModel.close() } }
    LaunchedEffect(state.account.connected) { if (state.account.connected) viewModel.refresh() }

    NuvioDialog(onDismiss = dismiss, title = "MyAnimeList", subtitle = if (state.session != null)
        "Connect your account with your phone." else state.account.username
        ?: stringResource(R.string.syclop_mal_dialog_summary), width = 780.dp, suppressFirstKeyUp = false) {
        val authorization = state.session
        if (authorization != null) {
            MalAuthorizationPane(authorization, state, viewModel)
        } else LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 370.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.account.configuration.clientId.isNotBlank()) item("native_catalogs") {
                Text("MyAnimeList is available in Home and Search. Connected accounts can also select MyAnimeList as the Library source. Each entry keeps its own MAL episode numbering; resume positions remain local.",
                    style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
            }
            item("status") {
                state.error?.let { Text(it, color = NuvioTheme.colors.Error, style = NuvioTheme.textStyles.bodyCompact) }
                val message = state.library.message ?: state.notice
                message?.let { Text(it, color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.bodyCompact) }
            }
            if (!state.account.connected) {
                item("connect") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.account.configuration.clientId.isBlank()) {
                            Text(stringResource(R.string.syclop_mal_registration_needed),
                                style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
                        }
                        SettingsDialogActionButton("Connect MyAnimeList", viewModel::connect, primary = true,
                            enabled = !state.busy && state.account.configuration.clientId.isNotBlank())
                    }
                }
            }
            if (state.account.connected) {
                item("account_actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsDialogActionButton(if (state.library.loading) "Loading…" else "Refresh anime list",
                            viewModel::refresh, enabled = !state.busy && !state.library.loading)
                        SettingsDialogActionButton("Disconnect MyAnimeList", viewModel::signOut, enabled = !state.busy)
                    }
                }
                item("automatic") {
                    SettingsActionRow("Sync completed episodes", stringResource(R.string.syclop_mal_known_ids_only),
                        value = if (state.account.automaticProgress) "On" else "Off",
                        onClick = { viewModel.setAutomaticProgress(!state.account.automaticProgress) })
                }
                item("search") {
                    MalTextField("Search MyAnimeList to add or edit an anime", query, { query = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsDialogActionButton("Search", { viewModel.search(query) }, enabled = !state.busy && query.trim().length >= 3)
                        if (state.searchResults != null) SettingsDialogActionButton("Back to my list", { query = ""; viewModel.search("") })
                    }
                }
                item("list_title") {
                    Text(if (state.searchResults == null) "My anime list (${state.library.items.size})" else "Search results",
                        style = NuvioTheme.textStyles.sectionTitle)
                }
                if (visibleItems.isEmpty() && !state.library.loading) item("empty") {
                    Text("No anime entries. Search to add one, or refresh your list.",
                        color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.bodyCompact)
                }
                items(visibleItems, key = { "mal_${it.id}" }) { anime ->
                    SettingsActionRow(title = anime.title,
                        subtitle = "${anime.status.label} · ${anime.watchedEpisodes}/${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} episodes · MAL ${anime.id}",
                        value = "Edit", onClick = { editing = anime })
                }
            }
            item("advanced") {
                SettingsActionRow("Advanced application setup", "For the Syclop app developer; ordinary users only connect their accounts.",
                    value = if (advanced) "Hide" else "Show", onClick = { advanced = !advanced })
            }
            if (advanced) item("developer_form") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.syclop_mal_developer_instructions),
                        style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
                    MalTextField("MAL native application client ID", clientId, { clientId = it })
                    MalTextField("Exact registered redirect URI", redirect, { redirect = it })
                    SettingsDialogActionButton("Save application setup", { viewModel.configure(clientId, redirect) }, enabled = !state.busy)
                    Text(stringResource(R.string.syclop_mal_redirect_guidance), style = NuvioTheme.textStyles.bodyCompact,
                        color = NuvioTheme.colors.TextSecondary)
                }
            }
            item("close") { SettingsDialogActionButton("Close", dismiss) }
        }
    }
    editing?.let { anime ->
        MalProgressDialog(anime, state.busy, onSave = { edit ->
            viewModel.update(anime, edit, state.account.scope) { editing = null }
        }, onDismiss = { editing = null })
    }
}

/** A fixed pane keeps the entire QR visible; no lazy-list focus can scroll it away. */
@Composable
private fun MalAuthorizationPane(
    session: MalAuthorizationSession,
    state: MalSettingsUiState,
    viewModel: MalSettingsViewModel
) {
    var manual by remember(session.authorizationUrl) { mutableStateOf(false) }
    var callback by remember(session.authorizationUrl) { mutableStateOf("") }
    val firstAction = remember(session.authorizationUrl) { FocusRequester() }
    val secondAction = remember(session.authorizationUrl) { FocusRequester() }
    val qr = remember(session.authorizationUrl) { QrCodeGenerator.generate(session.authorizationUrl, 512) }

    LaunchedEffect(session.authorizationUrl, manual) {
        // Wait until the replacement pane's focus targets have been attached.
        withFrameNanos { }
        firstAction.requestFocus()
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 370.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!manual) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(qr.asImageBitmap(), "MyAnimeList sign-in QR code", modifier = Modifier.size(172.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Scan with your phone, sign in, and approve Syclop.", style = NuvioTheme.textStyles.bodyCompact)
                    Text(if (session.localCallbackListening)
                        "Use the same home network. On your phone, tap Return to Syclop after approval."
                        else "After approval, choose Manual fallback to enter the returned link.",
                        style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
                }
            }
            Text(if (session.completing) "Completing connection…" else "Keep this screen open. The code expires after five minutes.",
                style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
        } else {
            Text("Copy the complete authorization link from your phone's return page, then enter it below.",
                style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
            MalTextField("Returned callback URL", callback, { callback = it }, sensitive = true)
            SettingsDialogActionButton(if (session.completing) "Completing…" else "Use callback URL",
                { viewModel.submitCallback(callback) }, enabled = !state.busy && !session.completing && callback.isNotBlank())
        }
        state.error?.let {
            Text(it, color = NuvioTheme.colors.Error, style = NuvioTheme.textStyles.bodyCompact, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MalAuthorizationAction(if (manual) "Back to QR code" else "Cancel", {
                if (manual) manual = false else viewModel.cancelConnection()
            }, Modifier.focusRequester(firstAction).focusProperties {
                left = firstAction
                right = secondAction
                if (!manual) { up = firstAction; down = firstAction }
            })
            MalAuthorizationAction(if (manual) "Cancel" else "Manual fallback", {
                if (manual) viewModel.cancelConnection() else manual = true
            }, Modifier.focusRequester(secondAction).focusProperties {
                left = firstAction
                right = secondAction
                if (!manual) { up = secondAction; down = secondAction }
            })
        }
    }
}

@Composable
private fun MalAuthorizationAction(text: String, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary)) {
        Text(text, maxLines = 1)
    }
}

@Composable
private fun MalProgressDialog(item: MalAnime, busy: Boolean, onSave: (MalListEdit) -> Unit, onDismiss: () -> Unit) {
    var status by remember(item.id) { mutableStateOf(item.status) }
    var watched by remember(item.id) { mutableStateOf(item.watchedEpisodes.toString()) }
    var score by remember(item.id) { mutableStateOf(item.score) }
    var rewatching by remember(item.id) { mutableStateOf(item.isRewatching) }
    var timesRewatched by remember(item.id) { mutableStateOf(item.timesRewatched.toString()) }
    var rewatchValue by remember(item.id) { mutableStateOf(item.rewatchValue) }
    val count = watched.toIntOrNull()
    val rewatchCount = timesRewatched.toIntOrNull()
    val edit = if (count != null && rewatchCount != null) MalListEdit.changes(item, status, count, score,
        rewatching, rewatchCount, rewatchValue) else null
    val valid = count != null && count >= 0 && (item.totalEpisodes <= 0 || count <= item.totalEpisodes) &&
        rewatchCount != null && rewatchCount in 0..100_000 && edit?.fields()?.isNotEmpty() == true
    NuvioDialog(onDismiss, item.title, subtitle = "MyAnimeList entry ${item.id} · ${item.totalEpisodes.takeIf { it > 0 } ?: "Unknown"} total episodes",
        width = 740.dp, suppressFirstKeyUp = false) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("status_options") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MalListStatus.entries.forEach { option ->
                    SettingsDialogActionButton(option.label, { status = option
                        if (option == MalListStatus.COMPLETED && item.totalEpisodes > 0) watched = item.totalEpisodes.toString()
                    }, primary = status == option, enabled = !busy)
                }
            }
            }
            item("watched") {
            MalTextField("Episodes watched", watched, { value -> watched = value.filter(Char::isDigit).take(5) }, numeric = true)
            }
            item("rating") {
                SettingsActionRow("My rating", "0 means not rated", value = if (score == 0) "Not rated" else "$score / 10",
                    onClick = { score = (score + 1) % 11 })
            }
            item("rewatching") {
                SettingsActionRow("Rewatching", "This flag does not reset episodes or change the status automatically.",
                    value = if (rewatching) "On" else "Off", onClick = { rewatching = !rewatching })
            }
            item("rewatch_count") {
                MalTextField("Times rewatched", timesRewatched, { value -> timesRewatched = value.filter(Char::isDigit).take(6) }, numeric = true)
            }
            item("rewatch_value") {
                SettingsActionRow("Rewatch value", "0 means not set; MyAnimeList accepts 0–5", value = rewatchValue.toString(),
                    onClick = { rewatchValue = (rewatchValue + 1) % 6 })
            }
            item("preservation") {
            Text("Only values changed here are sent to MyAnimeList. Tags, comments, priority, other anime and other accounts are preserved.",
                style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
            }
            item("actions") {
            SettingsDialogActionRow {
                SettingsDialogActionButton("Cancel", onDismiss)
                SettingsDialogActionButton(if (busy) "Saving…" else "Save changes", { edit?.let(onSave) },
                    primary = true, enabled = !busy && valid)
            }
            }
        }
    }
}

@Composable
private fun MalTextField(label: String, value: String, change: (String) -> Unit, sensitive: Boolean = false, numeric: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        BasicTextField(value = value, onValueChange = change, singleLine = true,
            textStyle = NuvioTheme.textStyles.bodyCompact.copy(color = NuvioTheme.colors.TextPrimary),
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Ascii),
            visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().background(NuvioTheme.colors.Field, RoundedCornerShape(10.dp))
                .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp)).padding(12.dp))
    }
}
