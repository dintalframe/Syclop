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
import androidx.compose.ui.test.assertIsEnabled
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

/** Exercises the actual TV controls with a callback that leaves the test Activity open. */
@RunWith(AndroidJUnit4::class)
class AppShutdownSettingsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val shutdownLabel get() = instrumentation.targetContext.getString(R.string.syclop_shutdown_app)
    private val confirmTitle get() = instrumentation.targetContext.getString(R.string.syclop_shutdown_app_confirm_title)
    private val cancelLabel get() = instrumentation.targetContext.getString(R.string.action_cancel)

    @Test
    fun cancelIsInitiallyFocusedAndReturnsToTheSettingsAction() {
        val shutdowns = AtomicInteger()
        setAction { shutdowns.incrementAndGet(); true }
        openConfirmation()
        compose.onNodeWithText(cancelLabel).assertIsDisplayed().assertIsFocused()
        capture("shutdown-confirmation-cancel-focused")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(shutdownLabel).assertIsEnabled().assertIsFocused()
        assertEquals(0, shutdowns.get())
    }

    @Test
    fun backDismissesConfirmationWithoutShuttingDown() {
        val shutdowns = AtomicInteger()
        setAction { shutdowns.incrementAndGet(); true }
        openConfirmation()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(shutdownLabel).assertIsEnabled().assertIsFocused()
        assertEquals(0, shutdowns.get())
    }

    @Test
    fun confirmationRequestsShutdownOnceAndDisablesTheAction() {
        val shutdowns = AtomicInteger()
        setAction { shutdowns.incrementAndGet(); true }
        openConfirmation()
        compose.onNodeWithText(cancelLabel).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(hasText(shutdownLabel) and hasAnyAncestor(isDialog())).assertIsFocused()
        capture("shutdown-confirmation-shutdown-focused")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(timeoutMillis = 5_000) { shutdowns.get() == 1 }
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(shutdownLabel).assertIsNotEnabled()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, shutdowns.get())
    }

    @Test
    fun failedShutdownRestoresTheActionAndAllowsRetry() {
        val shutdowns = AtomicInteger()
        setAction { shutdowns.incrementAndGet() > 1 }
        openConfirmation()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(timeoutMillis = 5_000) { shutdowns.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(shutdownLabel).assertIsEnabled().assertIsFocused()
        capture("shutdown-failure-action-restored")

        openConfirmation()
        compose.onNodeWithText(cancelLabel).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(timeoutMillis = 5_000) { shutdowns.get() == 2 }
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.onNodeWithText(shutdownLabel).assertIsNotEnabled()
        assertEquals(2, shutdowns.get())
    }

    private fun setAction(onShutdown: () -> Boolean) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(40.dp)) {
                    AppShutdownSettingsActionContent(onShutdown = onShutdown)
                }
            }
        }
    }

    private fun openConfirmation() {
        compose.onNodeWithText(shutdownLabel).assertIsDisplayed()
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
