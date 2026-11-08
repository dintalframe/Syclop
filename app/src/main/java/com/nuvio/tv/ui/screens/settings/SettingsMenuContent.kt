package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

private data class SettingsMenuLabel(@param:StringRes val title: Int, @param:StringRes val subtitle: Int,
    val icon: ImageVector)

/** Only the menu changes: each action opens the same existing page and settings model. */
@Composable
internal fun SettingsMenuContent(
    category: SettingsCategory,
    entries: List<SettingsCategory>,
    preferredFocusCategory: SettingsCategory?,
    initialFocusRequester: FocusRequester?,
    onOpen: (SettingsCategory) -> Unit
) {
    val heading = when (category) {
        SettingsCategory.ACCOUNTS -> R.string.settings_menu_connected_services to R.string.settings_menu_connected_services_description
        SettingsCategory.APP -> R.string.settings_menu_app to R.string.settings_menu_app_description
        else -> R.string.settings_advanced to R.string.settings_menu_advanced_description
    }
    val focusTarget = settingsMenuFocusTarget(entries, preferredFocusCategory)
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }
    val privacyFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        SettingsDetailHeader(title = stringResource(heading.first), subtitle = stringResource(heading.second))
        SettingsGroupCard(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxSize()) {
                key(category, focusTarget) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = entries.indexOf(focusTarget).coerceAtLeast(0))
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        items(entries, key = { it.name }) { entry ->
                            val label = settingsMenuLabel(entry)
                            SettingsActionRow(title = stringResource(label.title), subtitle = stringResource(label.subtitle),
                                leadingIcon = label.icon, onClick = { onOpen(entry) },
                                modifier = if (entry == focusTarget && initialFocusRequester != null)
                                    Modifier.focusRequester(initialFocusRequester) else Modifier)
                        }
                        if (category == SettingsCategory.APP) {
                            item("privacy-policy") {
                                SettingsActionRow(
                                    title = stringResource(R.string.privacy_policy_title),
                                    subtitle = null,
                                    leadingIcon = Icons.Default.Info,
                                    onClick = { showPrivacyPolicy = true },
                                    modifier = Modifier.focusRequester(privacyFocusRequester)
                                )
                            }
                            item("app-controls-heading") {
                                SettingsSectionLabel(stringResource(R.string.syclop_app_controls))
                            }
                            item("local-backup") { BackupSettingsSection() }
                            item("restart-app") { AppRestartSettingsAction() }
                            item("shutdown-app") { AppShutdownSettingsAction() }
                        }
                    }
                }
            }
        }
    }
    if (category == SettingsCategory.APP && showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = {
            showPrivacyPolicy = false
            scope.launch {
                withFrameNanos { }
                runCatching { privacyFocusRequester.requestFocus() }
            }
        })
    }
}

private fun settingsMenuLabel(category: SettingsCategory): SettingsMenuLabel = when (category) {
    SettingsCategory.TRACKING -> SettingsMenuLabel(R.string.settings_menu_connected_accounts, R.string.settings_menu_connected_accounts_description, Icons.Default.Sync)
    SettingsCategory.LIBRARY -> SettingsMenuLabel(R.string.settings_menu_tracking_preferences, R.string.settings_menu_tracking_preferences_description, Icons.Default.Sync)
    SettingsCategory.ACCOUNT -> SettingsMenuLabel(
        if (BuildConfig.LOCAL_ONLY_BUILD) R.string.syclop_local_profile_title else R.string.settings_menu_syclop_account,
        if (BuildConfig.LOCAL_ONLY_BUILD) R.string.syclop_local_account_subtitle else R.string.settings_menu_syclop_account_description,
        Icons.Default.Person)
    SettingsCategory.PROFILES -> SettingsMenuLabel(R.string.settings_profiles, R.string.settings_menu_profiles_description, Icons.Default.People)
    SettingsCategory.WATCH_PARTY -> SettingsMenuLabel(R.string.party_title, R.string.settings_menu_watch_party_description, Icons.Rounded.Groups)
    SettingsCategory.CONTENT_DISCOVERY -> SettingsMenuLabel(R.string.settings_menu_sources_tools, R.string.settings_menu_sources_tools_description, Icons.Default.Explore)
    SettingsCategory.STREAMING_SERVICES -> SettingsMenuLabel(R.string.settings_menu_debrid_services, R.string.settings_menu_streaming_services_description, Icons.Default.CloudDownload)
    SettingsCategory.PLAYBACK -> SettingsMenuLabel(R.string.settings_playback, R.string.settings_menu_playback_description, Icons.Rounded.PlayArrow)
    SettingsCategory.APPEARANCE -> SettingsMenuLabel(R.string.settings_menu_theme, R.string.settings_menu_theme_description, Icons.Default.Palette)
    SettingsCategory.LAYOUT -> SettingsMenuLabel(R.string.settings_menu_layout, R.string.settings_menu_layout_description, Icons.Default.GridView)
    SettingsCategory.ADVANCED -> SettingsMenuLabel(R.string.settings_menu_system, R.string.settings_menu_system_description, Icons.Default.Settings)
    SettingsCategory.INTEGRATION -> SettingsMenuLabel(R.string.settings_menu_integrations, R.string.settings_menu_integrations_description, Icons.Default.Link)
    SettingsCategory.PLUGINS -> SettingsMenuLabel(R.string.plugin_title, R.string.settings_content_discovery_plugins_subtitle, Icons.Default.Power)
    SettingsCategory.DEBUG -> SettingsMenuLabel(R.string.settings_debug, R.string.settings_menu_debug_description, Icons.Default.BugReport)
    else -> SettingsMenuLabel(R.string.settings_menu_about_updates, R.string.settings_menu_about_updates_description, Icons.Default.Info)
}
