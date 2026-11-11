package com.nuvio.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.ui.theme.NuvioTheme
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production panel geometry and TV focus, without navigation, accounts, images or playback. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SidebarMotionTvTest {
    private class MotionScale : MotionDurationScale {
        val factor = mutableFloatStateOf(1f)
        override val scaleFactor: Float get() = factor.floatValue
    }
    private val motion = MotionScale()
    @get:Rule val compose = createComposeRule(effectContext = motion)
    private val progress = mutableFloatStateOf(1f)
    private val items = listOf(DrawerItem("home", "Home fixture"), DrawerItem("settings", "Settings fixture"))

    @Test fun intermediatePanelKeepsTextSizeAndGlidesFromLeadingEdge() {
        show(LayoutDirection.Ltr)
        val settled = bounds()
        compose.runOnIdle { progress.floatValue = 0.5f }
        val halfway = bounds()
        assertEquals("Opening must not resize the labels", settled.width, halfway.width, 0.5f)
        assertTrue("An opening LTR panel glides from its leading edge", halfway.left < settled.left - 1f)
        compose.runOnIdle { progress.floatValue = 0.75f }
        val reversing = bounds()
        assertTrue("A reversal retains the current visual position", reversing.left > halfway.left && reversing.left < settled.left)
    }

    @Test fun rtlPanelGlidesFromTheRightWithoutResizingText() {
        show(LayoutDirection.Rtl)
        val settled = bounds()
        compose.runOnIdle { progress.floatValue = 0.5f }
        val halfway = bounds()
        assertEquals(settled.width, halfway.width, 0.5f)
        assertTrue("RTL must mirror the leading-edge glide", halfway.left > settled.left + 1f)
    }

    @Test fun remoteFocusMovesImmediatelyAndSettlesWithoutOversizedItems() {
        show(LayoutDirection.Ltr, focusEnabled = true)
        compose.onNodeWithContentDescription("Settings fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val unfocusedWidth = bounds().width
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Home fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithContentDescription("Home fixture").assertIsFocused()
        compose.mainClock.advanceTimeBy(160)
        val focusedWidth = bounds().width
        assertTrue("Remote focus may grow an item by at most 2%", focusedWidth <= unfocusedWidth * 1.021f)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Settings fixture").assertIsFocused()
    }

    @Test fun openingAndRapidReversalRetainIntermediateVisibleFrames() {
        val expanded = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        show(LayoutDirection.Ltr, expanded = expanded)
        val closed = bounds()
        val closedLight = panelLight()
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeBy(96)
        val opening = bounds()
        val openingLight = panelLight()
        assertTrue("Opening must render an intermediate position", opening.left > closed.left + 1f)
        assertTrue("Opening must render an intermediate fade", openingLight < closedLight - 0.02f && openingLight > 0.1f)
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeByFrame()
        val reversed = bounds()
        assertEquals("Reversing must not jump to an endpoint", opening.left, reversed.left, 4f)
        compose.mainClock.advanceTimeBy(64)
        val closing = bounds()
        assertTrue("Closing must move toward the leading edge", closing.left < reversed.left)
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("A second reversal must retain the current position", closing.left, bounds().left, 4f)
        compose.mainClock.advanceTimeBy(300)
        assertTrue(bounds().left > opening.left + 1f)
        assertTrue(panelLight() < openingLight - 0.02f)
    }

    @Test fun disabledMotionSettlesOpeningAndClosingInTheRealPanel() {
        motion.factor.floatValue = 0f
        val expanded = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        show(LayoutDirection.Rtl, expanded = expanded)
        val closed = bounds()
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeBy(32)
        assertTrue(bounds().left < closed.left - 1f)
        assertTrue("The visible panel is fully faded in", panelLight() < 0.1f)
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(closed.left, bounds().left, 0.5f)
        assertTrue("The closed panel is fully faded out", panelLight() > 0.95f)
    }

    @Test fun legacyRemoteFocusUsesTheSameSmallHighlightScale() {
        showLegacy()
        compose.onNodeWithContentDescription("Settings fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val resting = bounds().width
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Home fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithContentDescription("Home fixture").assertIsFocused()
        compose.mainClock.advanceTimeBy(160)
        assertTrue(bounds().width <= resting * 1.021f)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Settings fixture").assertIsFocused()
    }

    @Test fun modernFocusDrawsAnOutlineWithMotionDisabled() {
        motion.factor.floatValue = 0f
        show(LayoutDirection.Ltr, focusEnabled = true)
        assertVisibleFocusOutline("panel-fixture")
    }

    @Test fun legacyFocusDrawsTheSameOutlineWithMotionDisabled() {
        motion.factor.floatValue = 0f
        showLegacy()
        assertVisibleFocusOutline("legacy-panel-fixture")
    }

    private fun assertVisibleFocusOutline(panelTag: String) {
        val focused = compose.onNodeWithContentDescription("Settings fixture")
        focused.performSemanticsAction(SemanticsActions.RequestFocus)
        focused.assertIsFocused()
        val panel = compose.onNodeWithTag(panelTag)
        val origin = panel.fetchSemanticsNode().boundsInRoot.topLeft
        val bounds = focused.fetchSemanticsNode().boundsInRoot
        val pixels = panel.captureToImage().toPixelMap()
        val x = (bounds.center.x - origin.x).toInt()
        val edge = (bounds.top - origin.y).toInt()
        val rows = (edge - 1).coerceAtLeast(0)..(edge + 4).coerceAtMost(pixels.height - 1)
        assertTrue("The focused item must have a bright outline along its top edge",
            rows.any { y -> pixels[x, y].luminance() > 0.75f })
    }

    private fun showLegacy() {
        compose.setContent {
            NuvioTheme {
                Column(Modifier.background(Color(0xFF161618)).testTag("legacy-panel-fixture").padding(12.dp)) {
                    items.forEach { item ->
                        LegacySidebarButton(item.label, null, null, false, true,
                            modifier = Modifier.size(260.dp, 48.dp), onClick = {})
                    }
                }
            }
        }
    }

    private fun panelLight(): Float {
        val pixels = compose.onNodeWithTag("panel-fixture").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height * 9 / 10].luminance()
    }

    private fun bounds(): Rect = compose.onNodeWithContentDescription("Home fixture").fetchSemanticsNode().boundsInRoot

    private fun show(direction: LayoutDirection, focusEnabled: Boolean = false, expanded: State<Boolean>? = null) {
        val requesters = items.associate { it.route to FocusRequester() }
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val animatedProgress = expanded?.let { rememberSidebarExpandProgress(it.value).value }
                    val hazeState = remember { HazeState() }
                    Box(Modifier.size(320.dp, 480.dp).background(Color.White).testTag("panel-fixture")) {
                        ModernSidebarBlurPanel(
                            drawerItems = items,
                            selectedDrawerRoute = "home",
                            keepSidebarFocusDuringCollapse = focusEnabled,
                            sidebarLabelAlpha = 1f,
                            sidebarIconScale = 1f,
                            sidebarExpandProgress = animatedProgress ?: progress.floatValue,
                            isSidebarExpanded = true,
                            sidebarCollapsePending = false,
                            blurEnabled = false,
                            sidebarHazeState = hazeState,
                            panelShape = RoundedCornerShape(20.dp),
                            drawerItemFocusRequesters = requesters,
                            onDrawerItemFocused = {},
                            onDrawerItemClick = {},
                            activeProfileName = "",
                            activeProfileColorHex = "#FFFFFF",
                            activeProfileAvatarImageUrl = null,
                            showProfileSelector = false,
                            onSwitchProfile = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
