package com.nuvio.tv.ui.screens.addon

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
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
class AddonDeepLinkConfirmationTvTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    @Test
    fun remoteDefaultsToCancelAndRequiresExplicitAdd() {
        val visible = mutableStateOf(true)
        val additions = AtomicInteger()
        val host = "provider.example"
        compose.setContent {
            NuvioTheme {
                if (visible.value) {
                    AddonDeepLinkConfirmationDialog(
                        host = host,
                        onCancel = { visible.value = false },
                        onAdd = { additions.incrementAndGet(); visible.value = false }
                    )
                }
            }
        }

        val title = compose.onNodeWithText(label(R.string.addon_link_title))
        val cancel = compose.onNodeWithText(label(R.string.action_cancel))
        val add = compose.onNodeWithText(label(R.string.addon_link_add))
        title.assertIsDisplayed()
        compose.onNodeWithText(
            instrumentation.targetContext.getString(R.string.addon_link_description, host)
        ).assertIsDisplayed()
        cancel.assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        title.assertDoesNotExist()
        assertEquals(0, additions.get())

        compose.runOnIdle { visible.value = true }
        cancel.assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        add.assertIsFocused()
        assertEquals(0, additions.get())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        title.assertDoesNotExist()
        assertEquals(1, additions.get())

        compose.runOnIdle { visible.value = true }
        cancel.assertIsFocused()
        press(KeyEvent.KEYCODE_BACK)
        title.assertDoesNotExist()
        assertEquals(1, additions.get())
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }
}
