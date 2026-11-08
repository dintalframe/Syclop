package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production pane animation and real focus nodes, driven by the Compose frame clock. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsMotionTvTest {
    private class TestMotionScale : MotionDurationScale {
        val factor = mutableFloatStateOf(1f)
        override val scaleFactor: Float get() = factor.floatValue
    }

    private val motion = TestMotionScale()
    @get:Rule val compose = createComposeRule(effectContext = motion)
    private val parent = SettingsPane(SettingsCategory.ADVANCED_GROUP)
    private val child = SettingsPane(SettingsCategory.INTEGRATION)
    private val nested = SettingsPane(SettingsCategory.INTEGRATION, IntegrationSettingsSection.Tmdb)
    private val latest = SettingsPane(SettingsCategory.APP)
    private val playback = SettingsPane(SettingsCategory.PLAYBACK)
    private val selected = mutableStateOf(parent)
    private val requesters = listOf(parent, child, nested, latest, playback).associateWith { FocusRequester() }

    @Test fun classicHasAnIntermediateFadeAndSmallGlide() {
        show(SettingsUiStyle.CLASSIC)
        val settledLeft = markerLeft(parent)
        select(child)
        advance(64)
        val displacement = markerLeft(child) - settledLeft
        assertTrue("Incoming pane must be between its entry and resting positions", displacement > 0f)
        assertTrue("Glide must stay small on a wide pane", displacement <= 16f * density())
        val pixel = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()[100, 100]
        assertTrue("Intermediate drawing must blend outgoing red and incoming blue", pixel.red > 0.01f && pixel.blue > 0.01f)
        advance(240)
        assertEquals(settledLeft, markerLeft(child), 0.5f)
        compose.onNodeWithTag(tag(parent)).assertDoesNotExist()
    }

    @Test fun zenOutgoingDescendantsRejectEvenAnExplicitStaleFocusRequest() {
        show(SettingsUiStyle.ZEN)
        compose.onNodeWithTag(tag(parent)).assertIsFocused()
        select(child)
        advance(48)
        compose.onNodeWithTag(tag(child)).assertIsFocused()
        compose.runOnIdle { requesters.getValue(parent).requestFocus() }
        compose.onNodeWithTag(tag(parent)).assertIsNotFocused()
        compose.onNodeWithTag(tag(child)).assertIsFocused()
    }

    @Test fun horizonRapidReplacementAndReversalSettleOnlyOnTheLatestPane() {
        show(SettingsUiStyle.HORIZON)
        val settledLeft = markerLeft(parent)
        select(child)
        advance(48)
        val beforeReverse = markerLeft(parent)
        select(parent)
        advance(16)
        assertTrue("Reversing must continue from the current drawing", kotlin.math.abs(markerLeft(parent) - beforeReverse) < 8f * density())
        select(nested)
        advance(32)
        select(latest)
        advance(320)
        compose.onNodeWithTag(tag(latest)).assertIsFocused()
        assertEquals(settledLeft, markerLeft(latest), 0.5f)
        listOf(parent, child, nested).forEach { compose.onNodeWithTag(tag(it)).assertDoesNotExist() }
    }

    @Test fun integrationBackReversesDirectionAndMenuRestoresItsOriginalAction() {
        show(SettingsUiStyle.CLASSIC, realMenu = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.settings_menu_integrations)).assertIsFocused()
        select(child)
        advance(240)
        val settledLeft = markerLeft(child)
        select(nested)
        advance(64)
        assertTrue(markerLeft(nested) > settledLeft)
        select(child)
        advance(240)
        select(nested)
        advance(240)
        select(child)
        advance(64)
        assertTrue("Back to the integration hub enters from the start edge", markerLeft(child) < settledLeft)
        advance(240)
        select(parent)
        advance(240)
        compose.onNodeWithText(context.getString(R.string.settings_menu_integrations)).assertIsFocused()
    }

    @Test fun rtlMirrorsNestedForwardAndBack() {
        selected.value = child
        show(SettingsUiStyle.HORIZON, direction = LayoutDirection.Rtl)
        val settledLeft = markerLeft(child)
        select(nested)
        advance(64)
        assertTrue("RTL forward enters from the left", markerLeft(nested) < settledLeft)
        advance(240)
        select(child)
        advance(64)
        assertTrue("RTL Back enters from the right", markerLeft(child) > settledLeft)
    }

    @Test fun railAndSiblingDirectionFollowMenuOrder() {
        selected.value = child
        show(SettingsUiStyle.CLASSIC)
        val settledLeft = markerLeft(child)
        select(playback)
        advance(64)
        assertTrue("An earlier sibling enters from the start edge", markerLeft(playback) < settledLeft)
        advance(240)
        select(latest)
        advance(64)
        assertTrue("An earlier rail group enters from the start edge", markerLeft(latest) < settledLeft)
    }

    @Test fun zeroAnimationScaleSettlesWithoutLeavingFocusableOutgoingContent() {
        motion.factor.floatValue = 0f
        show(SettingsUiStyle.CLASSIC)
        select(nested)
        advance(32)
        compose.onNodeWithTag(tag(nested)).assertIsFocused()
        compose.onNodeWithTag(tag(parent)).assertDoesNotExist()
        assertFalse(compose.mainClock.autoAdvance)
    }

    private fun show(
        style: SettingsUiStyle,
        direction: LayoutDirection = LayoutDirection.Ltr,
        realMenu: Boolean = false
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NuvioTheme(settingsUiStyle = style) {
                    Box(Modifier.size(600.dp, 400.dp).background(Color.Black).testTag("viewport")) {
                        SettingsPaneTransition(selected.value, Modifier.fillMaxSize()) { pane, active ->
                            val requester = requesters.getValue(pane)
                            LaunchedEffect(active) {
                                if (active) {
                                    // Match the screen's wait for lazy menu rows to attach their requester.
                                    if (realMenu && pane == parent) delay(120)
                                    requester.requestFocus()
                                }
                            }
                            if (realMenu && pane == parent) {
                                SettingsMenuContent(
                                    category = SettingsCategory.ADVANCED_GROUP,
                                    entries = listOf(SettingsCategory.PLAYBACK, SettingsCategory.INTEGRATION),
                                    preferredFocusCategory = SettingsCategory.INTEGRATION,
                                    initialFocusRequester = requester.takeIf { active },
                                    onOpen = { selected.value = SettingsPane(it) }
                                )
                            } else {
                                Box(Modifier.fillMaxSize().background(if (pane == parent) Color.Red else Color.Blue)) {
                                    Box(Modifier.padding(32.dp).size(12.dp).testTag("marker-${tag(pane)}"))
                                    // A descendant group catches a gate that only disables its immediate child.
                                    Box(Modifier.padding(80.dp).focusGroup()) {
                                        Box(Modifier.size(60.dp).testTag(tag(pane)).focusRequester(requester).focusable())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        advance(240)
    }

    private fun select(pane: SettingsPane) {
        compose.runOnIdle { selected.value = pane }
        advance(16)
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun markerLeft(pane: SettingsPane) =
        compose.onNodeWithTag("marker-${tag(pane)}").fetchSemanticsNode().boundsInRoot.left

    private fun density() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    private fun tag(pane: SettingsPane) = "${pane.category}-${pane.integrationSection}"
}
