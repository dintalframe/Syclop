package com.nuvio.tv.ui.screens.settings

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the real restart confirmation with an injected callback; the test process stays alive. */
@RunWith(AndroidJUnit4::class)
class AppRestartSettingsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val restartLabel get() = instrumentation.targetContext.getString(R.string.syclop_restart_app)
    private val confirmTitle get() = instrumentation.targetContext.getString(R.string.syclop_restart_app_confirm_title)
    private val cancelLabel get() = instrumentation.targetContext.getString(R.string.action_cancel)

    @Test
    fun cancelIsInitiallyFocusedAndReturnsToTheSettingsAction() {
        val restarts = AtomicInteger()
        setAction { restarts.incrementAndGet(); true }
        openConfirmation()
        compose.onNodeWithText(cancelLabel).assertIsDisplayed().assertIsFocused()
        capture("restart-confirmation-cancel-focused")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(restartLabel).assertIsFocused()
        assertEquals(0, restarts.get())
    }

    @Test
    fun rightThenCenterConfirmsExactlyOneRestartRequest() {
        val restarts = AtomicInteger()
        setAction { restarts.incrementAndGet(); true }
        openConfirmation()
        compose.onNodeWithText(cancelLabel).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(hasText(restartLabel) and hasAnyAncestor(isDialog())).assertIsFocused()
        capture("restart-confirmation-restart-focused")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(timeoutMillis = 5_000) { restarts.get() == 1 }
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(restartLabel).assertIsNotEnabled()
        assertEquals(1, restarts.get())
    }

    @Test
    fun backDismissesConfirmationWithoutRestarting() {
        val restarts = AtomicInteger()
        setAction { restarts.incrementAndGet(); true }
        openConfirmation()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(restartLabel).assertIsFocused()
        assertEquals(0, restarts.get())
    }

    private fun setAction(onRestart: () -> Boolean) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(40.dp)) {
                    AppRestartSettingsActionContent(onRestart = onRestart)
                }
            }
        }
    }

    private fun openConfirmation() {
        compose.onNodeWithText(restartLabel).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(confirmTitle).assertIsDisplayed()
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
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
}
