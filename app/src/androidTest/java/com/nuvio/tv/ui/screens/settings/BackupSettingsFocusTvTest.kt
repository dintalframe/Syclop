package com.nuvio.tv.ui.screens.settings

import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.MainActivity
import com.nuvio.tv.core.backup.PortableBackup
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSettingsFocusTvTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun restorePreviewFocusesCancelAndOnePressDismissesIt() {
        withBackupDialog { state ->
            compose.runOnIdle {
                state.value = state.value.copy(preview = PortableBackup(
                    "2026-11-11T00:00:00Z", "0.1.3", emptyList(), emptyList()
                ))
            }
            compose.onNodeWithText("Replace local data?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Replace local data?").assertDoesNotExist()
            assertFalse(state.value.pending)
        }
    }

    @Test
    fun undoConfirmationFocusesCancelAndOnePressDismissesIt() {
        withBackupDialog { state ->
            compose.runOnIdle { state.value = state.value.copy(canUndo = true) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Undo previous restore"))
            focus("Undo previous restore")
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Undo previous restore?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Undo previous restore?").assertDoesNotExist()
            assertFalse(state.value.pending)
        }
    }

    private fun withBackupDialog(test: (MutableStateFlow<BackupSettingsUiState>) -> Unit) {
        require(BuildConfig.LOCAL_ONLY_BUILD && Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: MutableStateFlow<BackupSettingsUiState>
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[BackupSettingsViewModel::class.java]
                val field = BackupSettingsViewModel::class.java.getDeclaredField("mutableState").apply {
                    isAccessible = true
                }
                @Suppress("UNCHECKED_CAST")
                state = field.get(viewModel) as MutableStateFlow<BackupSettingsUiState>
                activity.setContent { NuvioTheme { Box(Modifier.fillMaxSize()) { BackupSettingsSection(viewModel) } } }
            }
            focus("Local backup and restore")
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(timeoutMillis = 5_000) { !state.value.busy }
            assertFalse(state.value.pending)
            test(state)
        }
    }

    private fun focus(label: String) {
        compose.onNodeWithText(label).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }
}
