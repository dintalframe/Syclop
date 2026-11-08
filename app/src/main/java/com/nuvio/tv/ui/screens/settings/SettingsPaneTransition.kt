package com.nuvio.tv.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal data class SettingsPane(
    val category: SettingsCategory,
    val integrationSection: IntegrationSettingsSection = IntegrationSettingsSection.Hub
)

@Composable
internal fun SettingsPaneTransition(
    targetState: SettingsPane,
    modifier: Modifier = Modifier,
    content: @Composable (SettingsPane, Boolean) -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val distance = with(LocalDensity.current) { 16.dp.roundToPx() }
    val focusManager = LocalFocusManager.current
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            val order = visibleSettingsCategories()
            val fromRail = settingsRailCategory(initialState.category)
            val toRail = settingsRailCategory(targetState.category)
            val forward = when {
                fromRail != toRail -> order.indexOf(toRail) > order.indexOf(fromRail)
                initialState.category == targetState.category ->
                    targetState.integrationSection.ordinal > initialState.integrationSection.ordinal
                initialState.category == toRail -> true
                targetState.category == fromRail -> false
                else -> {
                    val siblings = settingsSubcategories(fromRail, true, false, true, true)
                    siblings.indexOf(targetState.category) > siblings.indexOf(initialState.category)
                }
            }
            val toStart = forward != isRtl
            (slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) {
                if (toStart) distance else -distance
            } + fadeIn(tween(180))).togetherWith(
                slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) {
                    if (toStart) -distance else distance
                } + fadeOut(tween(180))
            )
        },
        label = "settingsDetailTransition"
    ) { pane ->
        val active = pane == targetState
        var paneHasFocus by remember { mutableStateOf(false) }
        SideEffect {
            // Release outgoing focus before incoming effects run, including on a rapid reversal.
            if (!active && paneHasFocus) focusManager.clearFocus(force = true)
        }
        Box(
            Modifier
                .onFocusChanged { paneHasFocus = it.hasFocus }
                .focusProperties { onEnter = { if (!active) cancelFocusChange() } }
                .focusGroup()
        ) {
            content(pane, active)
        }
    }
}
