package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production overlay focus/dispatch regression; no account, media or navigation graph.
 * During RED the mechanical API intentionally retains old buttons and labels.
 * This tests production overlay focus/dispatch; it does not qualify controller recovery.
 */
@RunWith(AndroidJUnit4::class)
class PlayerErrorRepairTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun qualifiedRetryStartsFocusedAndOneCenterDispatchesOnlyRetry() {
        val f = show(retryAvailable = true, sourceDestination = true)
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsDisplayed()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil { f.retries == 1 }

        compose.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, f.retries)
            assertEquals(0, f.exits)
            assertEquals(0, f.mpv)
            assertEquals(0, f.reports)
        }
    }

    @Test fun rightFromRetryChoosesTheSourceExitWithoutRetryOrEngineSwitch() {
        val f = show(retryAvailable = true, sourceDestination = true)
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil { f.exits == 1 }

        compose.runOnIdle {
            assertEquals(0, f.retries)
            assertEquals(1, f.exits)
            assertEquals(0, f.mpv)
            assertEquals(0, f.reports)
        }
    }

    @Test fun anUnqualifiedFileOffersSourceSelectionAsTheFirstAction() {
        val f = show(retryAvailable = false, sourceDestination = true)
        compose.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil { f.exits == 1 }

        compose.runOnIdle {
            assertEquals(1, f.exits)
            assertEquals(0, f.retries)
        }
    }

    @Test fun anUnprovableSourceDestinationUsesGoBackWithoutAFalseSourceLabel() {
        val f = show(retryAvailable = false, sourceDestination = false)
        compose.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.player_go_back)).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil { f.exits == 1 }

        compose.runOnIdle { assertEquals(0, f.retries) }
    }

    @Test fun losingRetryEligibilityMovesFocusToTheRemainingCurrentSourceAction() {
        val f = show(retryAvailable = true, sourceDestination = true)
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()

        compose.runOnIdle { f.retryAvailable = false }

        compose.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil { f.exits == 1 }
        compose.runOnIdle { assertEquals(0, f.retries) }
    }

    @Test fun disabledReportCannotTrapTheDpadBetweenSourceAndExistingMpvAction() {
        val f = show(retryAvailable = true, sourceDestination = true,
            showMpv = true, showReport = true, reportStatus = PlaybackIssueReportStatus.Sending)
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.player_switch_to_mpv)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        // Disabled Sending must not steal focus or dispatch a report.
        compose.onNodeWithText(label(R.string.player_switch_to_mpv)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()

        compose.runOnIdle {
            assertEquals(0, f.retries)
            assertEquals(0, f.exits)
            assertEquals(0, f.mpv)
            assertEquals(0, f.reports)
        }
    }

    @Test fun allFourActionsRemainFullyInsideTheViewportAtFontScaleOnePointThree() {
        show(retryAvailable = true, sourceDestination = true,
            showMpv = true, showReport = true, fontScale = 1.3f,
            layoutDirection = LayoutDirection.Ltr)
        val viewport = compose.onRoot().getUnclippedBoundsInRoot()

        for (id in listOf(R.string.action_retry, R.string.source_repair_choose_another,
            R.string.player_switch_to_mpv, R.string.player_report_issue)) {
            val text = label(id)
            val action = compose.onNode(hasText(text) and hasClickAction()).assertIsDisplayed()
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue("Full action bounds must fit the viewport: $text, $bounds within $viewport",
                bounds.right > bounds.left && bounds.bottom > bounds.top &&
                    bounds.left >= viewport.left && bounds.top >= viewport.top &&
                    bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
        }
    }

    @Test fun rtlLeftTraversesLogicalActionsAndRightReturnsBeforeOneRetryDispatch() {
        val f = show(retryAvailable = true, sourceDestination = true,
            showMpv = true, showReport = true, reportStatus = PlaybackIssueReportStatus.Sending,
            layoutDirection = LayoutDirection.Rtl)
        assertNativeRtlFixture(f)
        compose.onNodeWithText(label(R.string.player_report_issue_sending_button)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText(label(R.string.player_switch_to_mpv)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText(label(R.string.player_switch_to_mpv)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil { f.retries == 1 }

        compose.runOnIdle {
            assertEquals(1, f.retries)
            assertEquals(0, f.exits)
            assertEquals(0, f.mpv)
            assertEquals(0, f.reports)
        }
    }

    private fun show(retryAvailable: Boolean, sourceDestination: Boolean,
        showMpv: Boolean = false, showReport: Boolean = false,
        reportStatus: PlaybackIssueReportStatus = PlaybackIssueReportStatus.Idle,
        fontScale: Float? = null, layoutDirection: LayoutDirection? = null): Fixture {
        val f = Fixture(retryAvailable)
        compose.setContent {
            val nativeView = LocalView.current
            DisposableEffect(nativeView, layoutDirection) {
                val directionView = if (layoutDirection == null) nativeView else
                    requireNotNull(nativeView.parent as? ComposeView) {
                        "Expected the fixture's direct ComposeView parent"
                    }
                val previousDirection = directionView.layoutDirection
                if (layoutDirection != null) {
                    directionView.layoutDirection = if (layoutDirection == LayoutDirection.Rtl)
                        View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                onDispose {
                    if (layoutDirection != null) {
                        directionView.layoutDirection = previousDirection
                    }
                }
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalLayoutDirection provides (layoutDirection ?: LocalLayoutDirection.current)
            ) {
                NuvioTheme {
                    val view = LocalView.current
                    val direction = LocalLayoutDirection.current
                    SideEffect {
                        f.composeView = view
                        f.localDirection = direction
                    }
                    if (f.errorVisible) {
                        ErrorOverlay(
                            // Proposed plain body; final localized key is approved with the design.
                            message = "Playback stopped.",
                            onRetry = if (f.retryAvailable) ({ f.retries++; f.errorVisible = false }) else null,
                            backActionLabel = label(if (sourceDestination)
                                R.string.source_repair_choose_another else R.string.player_go_back),
                            showSwitchToMpvAction = showMpv,
                            onSwitchToMpv = { f.mpv++ },
                            showReportAction = showReport,
                            reportStatus = reportStatus,
                            reportId = null,
                            reportError = null,
                            onReport = { f.reports++ },
                            onBack = { f.exits++; f.errorVisible = false }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return f
    }

    private fun assertNativeRtlFixture(f: Fixture) {
        compose.runOnIdle {
            val view = requireNotNull(f.composeView)
            val ownerDirection = view.javaClass.methods.single {
                it.name == "getLayoutDirection" && it.returnType == LayoutDirection::class.java
            }.invoke(view)
            val evidence = "RTL fixture: local=${f.localDirection}, view=${view.javaClass.name}, " +
                "native=${view.layoutDirection}, owner=$ownerDirection"
            assertEquals(evidence, LayoutDirection.Rtl, ownerDirection)
            assertEquals(evidence, View.LAYOUT_DIRECTION_RTL, view.layoutDirection)
            assertEquals(evidence, LayoutDirection.Rtl, f.localDirection)
        }
    }

    private fun press(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    private class Fixture(available: Boolean) {
        var composeView: View? = null
        var localDirection: LayoutDirection? = null
        var retryAvailable by mutableStateOf(available)
        var errorVisible by mutableStateOf(true)
        @Volatile var retries = 0
        @Volatile var exits = 0
        @Volatile var mpv = 0
        @Volatile var reports = 0
    }
}
