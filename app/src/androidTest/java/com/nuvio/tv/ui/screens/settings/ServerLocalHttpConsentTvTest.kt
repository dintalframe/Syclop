package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerLocalHttpConsentTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun cancelAndBackKeepLocalHttpBlockedUntilAllowIsSelected() {
        val visible = mutableStateOf(true)
        val approvals = AtomicInteger()
        compose.setContent {
            NuvioTheme {
                if (visible.value) ServerLocalHttpConfirmationDialog(
                    host = "192.168.1.10",
                    onCancel = { visible.value = false },
                    onAllow = { approvals.incrementAndGet(); visible.value = false }
                )
            }
        }
        val title = compose.onNodeWithText(label(R.string.servers_local_http_title))
        val cancel = compose.onNodeWithText(label(R.string.action_cancel))
        cancel.assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        title.assertDoesNotExist()
        assertEquals(0, approvals.get())

        compose.runOnIdle { visible.value = true }
        cancel.assertIsFocused()
        press(KeyEvent.KEYCODE_BACK)
        title.assertDoesNotExist()
        assertEquals(0, approvals.get())

        compose.runOnIdle { visible.value = true }
        cancel.assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.servers_local_http_allow)).assertIsFocused()
        assertEquals(0, approvals.get())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        title.assertDoesNotExist()
        assertEquals(1, approvals.get())
    }

    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }
}
