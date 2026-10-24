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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.mal.MalAnime
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
    var callback by remember(state.session?.authorizationUrl) { mutableStateOf("") }
    var query by remember(state.account.scope) { mutableStateOf("") }
    var editing by remember(state.account.scope) { mutableStateOf<MalAnime?>(null) }
    val visibleItems = state.searchResults ?: state.library.items
    DisposableEffect(Unit) { onDispose { viewModel.close() } }
    LaunchedEffect(state.account.connected) { if (state.account.connected) viewModel.refresh() }

    NuvioDialog(onDismiss = dismiss, title = "MyAnimeList", subtitle = state.account.username
        ?: stringResource(R.string.syclop_mal_dialog_summary), width = 780.dp, suppressFirstKeyUp = false) {
        LazyColumn(
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
            val session = state.session
            if (session != null) {
                item("authorization") {
                    val qr = remember(session.authorizationUrl) { QrCodeGenerator.generate(session.authorizationUrl, 512) }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(qr.asImageBitmap(), "MyAnimeList sign-in QR code", modifier = Modifier.size(172.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.syclop_mal_scan), style = NuvioTheme.textStyles.body)
                            Text(if (session.localCallbackListening) stringResource(R.string.syclop_mal_callback_active)
                                else stringResource(R.string.syclop_mal_callback_manual),
                                color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.bodyCompact)
                            SettingsDialogActionButton("Cancel connection", viewModel::cancelConnection)
                        }
                    }
                }
                item("manual_callback") {
                    MalTextField("Returned callback URL (fallback)", callback, { callback = it }, sensitive = true)
                    SettingsDialogActionButton(if (session.completing) "Completing…" else "Use callback URL",
                        { viewModel.submitCallback(callback) }, enabled = !state.busy && !session.completing && callback.isNotBlank())
                }
            } else if (!state.account.connected) {
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
