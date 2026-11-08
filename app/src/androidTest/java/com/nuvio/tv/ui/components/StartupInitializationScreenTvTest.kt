package com.nuvio.tv.ui.components

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.inspector.WindowInspector
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.core.initialization.InitializationFailure
import com.nuvio.tv.core.initialization.InitializationPhase
import com.nuvio.tv.core.initialization.InitializationProvider
import com.nuvio.tv.core.initialization.InitializationStage
import com.nuvio.tv.core.initialization.InitializationStageStatus
import com.nuvio.tv.core.initialization.InitializationStep
import com.nuvio.tv.core.initialization.StartupInitializationState
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production gate and input boundary with no accounts, network, navigation or imports. */
@RunWith(AndroidJUnit4::class)
class StartupInitializationScreenTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val blocked = mutableStateOf(false)
    private val phase = mutableStateOf(InitializationPhase.CHECKING)
    private val stages = mutableStateOf<List<InitializationStage>>(emptyList())
    private val homeFocus = FocusRequester()
    private val homeActions = AtomicInteger()
    private val homeBacks = AtomicInteger()
    private val continues = AtomicInteger()
    private val retries = AtomicInteger()

    @Test fun lateBackgroundFocusCannotEscapeInitializationGate() {
        setGate()
        focusHome()
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText(CONTINUE).assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText(HOME).assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        var accepted = true
        compose.runOnIdle { accepted = homeFocus.requestFocus() }
        assertFalse("A mounted Home request must not enter the blocked background", accepted)
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        assertEquals(0, homeActions.get())
        capture("startup-gate-background-blocked")
    }

    @Test fun retryContinueAndBackKeepTheirActionsAndRestoreHomeFocus() {
        setGate()
        focusHome()
        compose.runOnIdle { phase.value = InitializationPhase.NEEDS_ATTENTION; blocked.value = true }
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(RETRY).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, retries.get())
        assertEquals(0, continues.get())
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        capture("startup-gate-actions")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, continues.get())
        assertEquals(0, homeActions.get())
        compose.onNodeWithText(CONTINUE).assertDoesNotExist()
        compose.onNodeWithText(HOME).assertIsDisplayed().assertIsFocused()
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        press(KeyEvent.KEYCODE_BACK)
        // Platform Back can arrive after Compose has become idle. Wait for the real
        // callback, and fail immediately if the mounted background receives it instead.
        compose.waitUntil(timeoutMillis = 5_000) { continues.get() >= 2 || homeBacks.get() > 0 }
        assertEquals(2, continues.get())
        assertEquals(0, homeBacks.get())
        compose.onNodeWithText(HOME).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, homeActions.get())
        capture("startup-gate-resumed-home")
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun repeatedGateTransitionsKeepTheSameNativeWindowRoots() {
        setGate()
        focusHome()
        var originalRoots = emptySet<android.view.View>()
        compose.runOnIdle { originalRoots = WindowInspector.getGlobalWindowViews().toSet() }
        assertTrue(originalRoots.isNotEmpty())
        repeat(4) {
            compose.runOnIdle { blocked.value = true }
            compose.onNodeWithText(CONTINUE).assertIsFocused()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.runOnIdle { assertEquals(originalRoots, WindowInspector.getGlobalWindowViews().toSet()) }
            compose.runOnIdle { blocked.value = false }
            compose.onNodeWithText(HOME).assertIsFocused()
            compose.runOnIdle { assertEquals(originalRoots, WindowInspector.getGlobalWindowViews().toSet()) }
        }
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        compose.runOnIdle { assertEquals(originalRoots, WindowInspector.getGlobalWindowViews().toSet()) }
        capture("startup-gate-same-window")
    }

    @Test fun retryThatRemovesFocusedActionKeepsContinueUsableUntilHomeReturns() {
        setGate(retryStartsImport = true)
        focusHome()
        compose.runOnIdle { phase.value = InitializationPhase.NEEDS_ATTENTION; blocked.value = true }
        repeat(3) { attempt ->
            compose.onNodeWithText(CONTINUE).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText(RETRY).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(attempt + 1, retries.get())
            compose.onNodeWithText(RETRY).assertDoesNotExist()
            compose.onNodeWithText(CONTINUE).assertIsFocused()
            compose.runOnIdle { phase.value = InitializationPhase.IMPORTING }
            var accepted = true
            compose.runOnIdle { accepted = homeFocus.requestFocus() }
            assertFalse("An import retry must keep background Home blocked", accepted)
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText(CONTINUE).assertIsFocused()
            assertEquals(0, homeActions.get())
            compose.runOnIdle { phase.value = InitializationPhase.NEEDS_ATTENTION }
        }
        // Account/profile work can also replace the error actions without a click.
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(RETRY).assertIsFocused()
        compose.runOnIdle { phase.value = InitializationPhase.CHECKING }
        compose.onNodeWithText(RETRY).assertDoesNotExist()
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, continues.get())
        compose.onNodeWithText(CONTINUE).assertDoesNotExist()
        compose.onNodeWithText(HOME).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, homeActions.get())
        capture("startup-gate-retry-focus-restored")
    }

    @Test fun providerProgressChangesKeepContinueFocusedAndShowOnlyObservedStages() {
        setGate()
        focusHome()
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText("Checking connected accounts").assertIsDisplayed()
        compose.onNodeWithText("Trakt", substring = true).assertDoesNotExist()
        compose.onNodeWithText(CONTINUE).assertIsFocused()

        val trakt = InitializationStage(InitializationProvider.TRAKT, InitializationStageStatus.READING,
            step = InitializationStep.HISTORY)
        compose.runOnIdle {
            phase.value = InitializationPhase.IMPORTING
            stages.value = listOf(trakt)
        }
        compose.onNodeWithText("Preparing connected accounts").assertIsDisplayed()
        compose.onNodeWithText("Trakt · Reading watch history and resume positions").assertIsDisplayed()
        compose.onNodeWithText("Simkl", substring = true).assertDoesNotExist()
        compose.onNodeWithText(CONTINUE).assertIsFocused()

        compose.runOnIdle { stages.value = listOf(trakt.copy(step = InitializationStep.LIBRARY)) }
        compose.onNodeWithText("Trakt · Reading lists and library").assertIsDisplayed()
        compose.onNodeWithText(CONTINUE).assertIsFocused()

        compose.runOnIdle {
            phase.value = InitializationPhase.NEEDS_ATTENTION
            stages.value = listOf(
                InitializationStage(InitializationProvider.TRAKT, InitializationStageStatus.COMPLETE,
                    count = 2, truncated = true),
                InitializationStage(InitializationProvider.SIMKL, InitializationStageStatus.FAILED,
                    failure = InitializationFailure.TIMEOUT),
                InitializationStage(InitializationProvider.MAL, InitializationStageStatus.COMPLETE, count = 1)
            )
        }
        compose.onNodeWithText("Some accounts need attention").assertIsDisplayed()
        compose.onNodeWithText("Trakt · 2 entries read · Partial history or list").assertIsDisplayed()
        compose.onNodeWithText("Simkl · Timed out").assertIsDisplayed()
        compose.onNodeWithText("MyAnimeList · 1 entry read").assertIsDisplayed()
        compose.onNodeWithText(CONTINUE).assertIsFocused()
        compose.onNodeWithText(RETRY).assertIsDisplayed()
        capture("startup-gate-provider-stages")
    }

    private fun setGate(retryStartsImport: Boolean = false) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    StartupInitializationContent(blocked.value) {
                        BackHandler { homeBacks.incrementAndGet() }
                        Button(onClick = { homeActions.incrementAndGet() }, modifier = Modifier.focusRequester(homeFocus)) {
                            Text(HOME)
                        }
                    }
                    if (blocked.value) StartupInitializationScreen(StartupInitializationState(1, 1, phase.value, stages.value),
                        onRetry = {
                            retries.incrementAndGet()
                            if (retryStartsImport) phase.value = InitializationPhase.CHECKING
                        },
                        onContinue = { continues.incrementAndGet(); blocked.value = false })
                }
            }
        }
    }

    private fun focusHome() {
        compose.onNodeWithText(HOME).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(HOME).assertIsFocused()
    }

    private fun press(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private companion object {
        const val HOME = "Home action"
        const val CONTINUE = "Continue with saved data"
        const val RETRY = "Retry failed imports"
    }
}
