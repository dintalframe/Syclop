@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy

@Composable
fun AboutScreen(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.about_subtitle)
    ) {
        AboutSettingsContent(
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions
        )
    }
}

@Composable
fun AboutSettingsContent(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null,
    showAppControls: Boolean = true
) {
    val aboutScrollState = rememberScrollState()
    var firstRowFocused by remember { mutableStateOf(false) }
    val firstRowModifier = Modifier
        .onFocusChanged { firstRowFocused = it.isFocused }
        .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val aboutBringIntoViewSpec = remember(aboutScrollState, defaultBringIntoViewSpec) {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = defaultBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return if (firstRowFocused && offset + aboutScrollState.value + size <= containerSize) {
                    -aboutScrollState.value.toFloat()
                } else {
                    defaultBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.about_title),
            subtitle = stringResource(R.string.about_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = null
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides aboutBringIntoViewSpec) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(aboutScrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineLarge,
                                color = NuvioTheme.colors.TextPrimary,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextSecondary,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))

                            if (showAppControls) {
                                AppRestartSettingsAction(modifier = firstRowModifier)
                                if (AppFeaturePolicy.inAppUpdatesEnabled) {
                                    UpdateChannelSettings(initialFocusRequester = null)
                                }
                            } else if (AppFeaturePolicy.inAppUpdatesEnabled) {
                                CheckForUpdatesSettingsAction(modifier = firstRowModifier)
                            }

                            if (AppFeaturePolicy.supportNuvioEnabled) {
                                SettingsActionRow(
                                    title = stringResource(R.string.settings_menu_supporters),
                                    subtitle = stringResource(R.string.about_supporters_contributors_subtitle),
                                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    onClick = onNavigateToSupportersContributors
                                )
                            }

                            SettingsActionRow(
                                title = stringResource(R.string.about_licenses_attributions),
                                subtitle = stringResource(R.string.about_licenses_attributions_subtitle),
                                trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                modifier = firstRowModifier.takeIf { !showAppControls && !AppFeaturePolicy.inAppUpdatesEnabled }
                                    ?: Modifier,
                                onClick = onNavigateToLicensesAttributions
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = aboutScrollState)
            }
        }
    }
}
