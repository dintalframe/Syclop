package com.nuvio.tv.ui.screens.addon

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mounts production setup with MainActivity's Hilt owner independently of seeded catalogs. */
@RunWith(AndroidJUnit4::class)
class EssentialAddonSetupScreenTvTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun manualEntryBackRestoresItsActionAndSkipContinues() {
        val skipCalls = AtomicInteger()
        withSetup(onSkip = { skipCalls.incrementAndGet() }) { scenario ->
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.essential_addon_enter_link_on_tv)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText(label(R.string.essential_addon_link_title)).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
            assertEquals(0, skipCalls.get())

            // The keyboard can receive Back before the screen does. Close it first
            // so the key event below tests the setup screen's own Back handler.
            scenario.onActivity { activity ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(timeoutMillis = 5_000) { !isImeVisible(scenario) }
            press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(timeoutMillis = 5_000) {
                !hasText(R.string.essential_addon_link_title)
            }
            compose.onNodeWithText(label(R.string.essential_addon_enter_link_on_tv)).assertIsFocused()
            assertEquals(0, skipCalls.get())

            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(label(R.string.essential_addon_continue_for_now)).assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(timeoutMillis = 5_000) { skipCalls.get() == 1 }
        }
    }

    @Test
    fun phoneQrBackRestoresPhoneAction() {
        withSetup {
            val qrDescription = label(R.string.cd_qr_code)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(timeoutMillis = 5_000) {
                hasQr(qrDescription) ||
                    hasText(R.string.error_network_required) ||
                    hasText(R.string.error_server_ports_unavailable)
            }
            assumeFalse("QR requires a non-loopback IPv4 address on the bench.", hasText(R.string.error_network_required))
            assumeFalse("The setup server could not open a LAN listener.", hasText(R.string.error_server_ports_unavailable))
            compose.onNodeWithContentDescription(qrDescription).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.addon_qr_close)).assertIsFocused()

            press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(timeoutMillis = 5_000) { !hasQr(qrDescription) }
            compose.onNodeWithText(label(R.string.essential_addon_show_qr)).assertIsFocused()
        }
    }

    private fun withSetup(
        onSkip: () -> Unit = {},
        test: (ActivityScenario<MainActivity>) -> Unit
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    NuvioTheme { EssentialAddonSetupScreen(onSkip = onSkip) }
                }
            }
            compose.onNodeWithText(label(R.string.essential_addon_show_qr))
                .assertIsDisplayed().assertIsFocused()
            test(scenario)
        }
    }

    private fun isImeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    private fun hasText(id: Int) = compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty()

    private fun hasQr(description: String) =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }
}
