package com.nuvio.tv.updater.ui

import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.ambient.AmbientIdleController
import com.nuvio.tv.ui.screens.ambient.LocalAmbientIdleController
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.InstalledUpdateNotes
import com.nuvio.tv.updater.UpdateUiState
import com.nuvio.tv.updater.model.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Component fixtures only; no package installation, persisted household state, or network. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTvMaterial3Api::class)
class InstalledUpdateNotesTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    @Test fun installedNotesPrecedeNewOfferWithoutStartingDownload() {
        val fixture = mount("Installed release fixture notes.", newerOffer = true)
        compose.onNodeWithText(label(R.string.update_whats_new)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.update_available)).assertDoesNotExist()
        assertEquals(0, fixture.acknowledgements)
        assertEquals(0, fixture.downloads)

        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(label(R.string.update_whats_new)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.update_later)).assertIsFocused()
        assertEquals(1, fixture.acknowledgements)
        assertEquals(0, fixture.downloads)
    }

    @Test fun remoteCanReachCloseReturnToNotesAndAcknowledgeWithHomeFocusRestored() {
        val fixture = mount("Short installed release fixture notes.")
        compose.onNode(hasScrollAction()).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(label(R.string.update_close)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(hasScrollAction()).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNodeWithText(label(R.string.update_whats_new)).assertDoesNotExist()
        compose.onNodeWithTag("home").assertIsFocused()
        assertEquals(1, fixture.acknowledgements)
        assertEquals(0, fixture.downloads)
    }

    @Test fun longNotesScrollThenBackAcknowledgesWithoutAnUpdateOffer() {
        val fixture = mount((1..60).joinToString("\n\n") { "Installed fixture note $it: remote menus improved." })
        val scroller = compose.onNode(hasScrollAction())
        val before = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val after = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(after > before)
        assertEquals(0, fixture.acknowledgements)
        compose.onNodeWithText(label(R.string.update_close)).assertIsDisplayed()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("home").assertIsFocused()
        assertEquals(1, fixture.acknowledgements)
        assertEquals(0, fixture.downloads)
    }

    @Test fun withheldNotesDoNotInterruptContent() {
        val fixture = mount(null)
        compose.onNodeWithText(label(R.string.update_whats_new)).assertDoesNotExist()
        compose.onNodeWithTag("home").assertIsFocused()
        assertEquals(0, fixture.acknowledgements)
        assertEquals(0, fixture.downloads)
    }

    @Test fun ambientSuspendsNotesWithoutAcknowledgingAndWakeRestoresThem() {
        val ambient = AmbientIdleController()
        val fixture = mount("Installed release fixture notes.", ambient = ambient)
        compose.onNodeWithText(label(R.string.update_whats_new)).assertIsDisplayed()
        compose.runOnIdle { ambient.show() }
        compose.onNodeWithText(label(R.string.update_whats_new)).assertDoesNotExist()
        assertEquals(0, fixture.acknowledgements)
        assertTrue(ambient.isShowing.value)
        compose.runOnIdle { ambient.dismiss() }
        compose.onNodeWithText(label(R.string.update_whats_new)).assertIsDisplayed()
        assertEquals(0, fixture.acknowledgements)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(1, fixture.acknowledgements)
        assertEquals(0, fixture.downloads)
    }

    private fun mount(notes: String?, newerOffer: Boolean = false, ambient: AmbientIdleController? = null): Fixture {
        val fixture = Fixture(notes, newerOffer)
        compose.setContent {
            CompositionLocalProvider(LocalAmbientIdleController provides ambient) {
            NuvioTheme {
                val homeFocus = remember { FocusRequester() }
                UpdateBannerHost(
                    state = fixture.state,
                    onDismissBanner = { fixture.state = fixture.state.copy(showBanner = false) },
                    onDownload = { fixture.downloads++ },
                    onDismissUnknownSources = {}, onOpenUnknownSources = {}, onFeedbackShown = {},
                    installedNotes = fixture.notes,
                    onDismissInstalledNotes = { fixture.acknowledgements++; fixture.notes = null }
                ) {
                    LaunchedEffect(Unit) { homeFocus.requestFocusAfterFrames() }
                    Button(onClick = {}, modifier = Modifier.testTag("home").focusRequester(homeFocus)) { Text("Home") }
                }
            }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private class Fixture(text: String?, newerOffer: Boolean) {
        var notes by mutableStateOf(text?.let { InstalledUpdateNotes("0.1.3", it) })
        var state by mutableStateOf(UpdateUiState(
            showBanner = newerOffer,
            updateBannerEnabled = false,
            update = AppUpdate("v9.9.9", "Newer fixture", "Newer fixture notes", null,
                "fixture.apk", "https://example.invalid/fixture.apk", 1, "ab".repeat(32))
        ))
        var acknowledgements = 0
        var downloads = 0
    }
}
