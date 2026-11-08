package com.nuvio.tv.ui.screens.settings

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The production three-page navigation boundary, with local controls instead of server accounts. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ContentDiscoveryMotionTvTest {
    private class TestMotionScale : MotionDurationScale {
        val factor = mutableFloatStateOf(1f)
        override val scaleFactor: Float get() = factor.floatValue
    }

    private val motion = TestMotionScale()
    @get:Rule val compose = createComposeRule(effectContext = motion)
    private val active = mutableStateOf(true)
    private val requesters = mutableMapOf<String, FocusRequester>()
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var parentBacks = 0

    @Test fun hubServersAndDetailHaveIntermediateFramesAndBackRestoresTheirExistingTargets() {
        show(SettingsUiStyle.CLASSIC)
        val restingLeft = left("hub")
        open("hub")
        advance(64)
        assertTrue(left("servers") > restingLeft)
        assertTrue(left("servers") - restingLeft <= 16f * density())
        val pixel = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()[100, 10]
        assertTrue("An intermediate frame must draw both pages", pixel.red > 0.01f && pixel.blue > 0.01f)
        advance(240)
        compose.onNodeWithTag("action-hub").assertDoesNotExist()
        open("servers")
        advance(64)
        assertTrue("Server detail enters from the end edge", left("detail") > restingLeft)
        advance(240)
        back()
        advance(64)
        assertTrue("Back to servers reverses the glide", left("servers") < restingLeft)
        advance(240)
        compose.onNodeWithTag("action-servers").assertIsFocused()
        back()
        advance(64)
        assertTrue("Back to the hub reverses the glide", left("hub") < restingLeft)
        advance(240)
        compose.onNodeWithTag("action-hub").assertIsFocused()
        assertEquals(0, parentBacks)
        back()
        assertEquals(1, parentBacks)
    }

    @Test fun outgoingPageCannotReclaimFocusAndAnInactiveOuterPaneCannotHandleBack() {
        show(SettingsUiStyle.ZEN)
        open("hub")
        advance(48)
        compose.onNodeWithTag("action-servers").assertIsFocused()
        compose.runOnIdle { requesters.getValue("hub").requestFocus() }
        compose.onNodeWithTag("action-hub").assertIsNotFocused()
        compose.onNodeWithTag("action-servers").assertIsFocused()
        advance(240)
        open("servers")
        advance(240)
        compose.runOnIdle { active.value = false }
        advance(16)
        compose.runOnIdle { requesters.getValue("detail").requestFocus() }
        compose.onNodeWithTag("action-detail").assertIsNotFocused()
        back()
        assertEquals(1, parentBacks)
        compose.runOnIdle { active.value = true }
        advance(16)
        compose.onNodeWithTag("action-detail").assertIsFocused()
        back()
        advance(240)
        compose.onNodeWithTag("action-servers").assertIsFocused()
    }

    @Test fun rapidBackAndForwardContinueFromTheCurrentFrameAndSettleOnTheLatestPage() {
        show(SettingsUiStyle.HORIZON)
        val restingLeft = left("hub")
        open("hub")
        advance(48)
        val beforeReverse = left("hub")
        back()
        advance(16)
        assertTrue("Reversal must not jump to its entry offset", kotlin.math.abs(left("hub") - beforeReverse) < 8f * density())
        open("hub")
        advance(48)
        open("servers")
        advance(240)
        compose.onNodeWithTag("action-detail").assertIsFocused()
        assertEquals(restingLeft, left("detail"), 0.5f)
        compose.onNodeWithTag("action-hub").assertDoesNotExist()
        compose.onNodeWithTag("action-servers").assertDoesNotExist()
    }

    @Test fun rtlMirrorsServerForwardAndBack() {
        show(SettingsUiStyle.HORIZON, LayoutDirection.Rtl)
        val restingLeft = left("hub")
        open("hub")
        advance(64)
        assertTrue(left("servers") < restingLeft)
        advance(240)
        open("servers")
        advance(64)
        assertTrue(left("detail") < restingLeft)
        advance(240)
        back()
        advance(64)
        assertTrue(left("servers") > restingLeft)
    }

    @Test fun zeroAnimationScaleSettlesEachServerPageWithoutLeavingOutgoingControls() {
        motion.factor.floatValue = 0f
        show(SettingsUiStyle.CLASSIC)
        open("hub")
        advance(32)
        compose.onNodeWithTag("action-servers").assertIsFocused()
        compose.onNodeWithTag("action-hub").assertDoesNotExist()
        open("servers")
        advance(32)
        compose.onNodeWithTag("action-detail").assertIsFocused()
        compose.onNodeWithTag("action-servers").assertDoesNotExist()
        back()
        advance(32)
        compose.onNodeWithTag("action-servers").assertIsFocused()
        compose.onNodeWithTag("action-detail").assertDoesNotExist()
    }

    private fun show(style: SettingsUiStyle, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { parentBacks++ }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NuvioTheme(settingsUiStyle = style) {
                    Box(Modifier.size(600.dp, 400.dp).background(Color.Black).testTag("viewport")) {
                        ContentDiscoveryPages(active.value) { page, requester, open, _, _ ->
                            val name = page ?: "hub"
                            requesters[name] = requester
                            Box(Modifier.fillMaxSize().background(when (page) {
                                null -> Color.Red
                                "servers" -> Color.Blue
                                else -> Color.Green
                            })) {
                                Box(Modifier.padding(32.dp).size(12.dp).testTag("marker-$name"))
                                SettingsActionRow(
                                    title = "Navigation fixture: $name",
                                    subtitle = null,
                                    onClick = { open(if (page == null) "servers" else "detail") },
                                    modifier = Modifier.padding(80.dp).testTag("action-$name").focusRequester(requester)
                                )
                            }
                        }
                    }
                }
            }
        }
        advance(240)
        compose.runOnIdle { requesters.getValue("hub").requestFocus() }
    }

    private fun open(page: String) {
        val action = compose.onNodeWithTag("action-$page")
        action.performSemanticsAction(SemanticsActions.RequestFocus)
        action.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        advance(16)
        compose.onNodeWithTag("action-${if (page == "hub") "servers" else "detail"}").assertExists()
    }

    private fun back() {
        compose.runOnIdle { backDispatcher.onBackPressed() }
        advance(16)
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun left(page: String) = compose.onNodeWithTag("marker-$page").fetchSemanticsNode().boundsInRoot.left
    private fun density() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
}
