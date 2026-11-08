package com.nuvio.tv.updater.ui

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.UpdateUiState
import com.nuvio.tv.updater.model.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Component fixture only: v9.9.9 is not a published update and no network is used. */
@RunWith(AndroidJUnit4::class)
class UpdateOfferDetailsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    @Test
    fun offerShowsInstalledAndAvailableVersionsWithNotesAndSafeDefault() {
        val fixture = mount("Fixture patch notes: smoother remote menus.")

        compose.onNodeWithText("${BuildConfig.VERSION_NAME} → 9.9.9", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Fixture patch notes: smoother remote menus.").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.update_later)).assertIsFocused()
        assertEquals(0, fixture.downloads)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(label(R.string.update_available)).assertDoesNotExist()
        assertEquals(1, fixture.dismissals)
        assertEquals(0, fixture.downloads)
    }

    @Test
    fun longPreviewStaysBoundedAndFullNotesScrollThenRestoreNotesFocusWithoutDownloading() {
        val notes = (1..60).joinToString("\n\n") { "Fixture patch note $it: remote navigation improvement." }
        val fixture = mount(notes)
        val preview = compose.onNodeWithText("Fixture patch note 1:", substring = true)
        preview.assertIsDisplayed()
        val bounds = preview.getUnclippedBoundsInRoot()
        assertTrue("Long notes must not push actions off screen", bounds.bottom - bounds.top <= 120.dp)
        compose.onNodeWithText(label(R.string.update_now)).assertIsDisplayed()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.update_now)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.update_release_notes)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val scroller = compose.onNode(hasScrollAction())
        scroller.assertIsFocused()
        val before = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val after = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("D-pad Down must scroll full notes", after > before)
        assertEquals(0, fixture.downloads)

        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(label(R.string.update_release_notes)).assertIsFocused()
        assertEquals(0, fixture.dismissals)
        assertEquals(0, fixture.downloads)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText(label(R.string.update_now)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, fixture.downloads)
    }

    @Test
    fun blankNotesUseReadableFallbackAndBackNeverDownloads() {
        val fixture = mount(" \n\t ")
        compose.onNodeWithText(label(R.string.update_no_release_notes)).assertIsDisplayed()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(label(R.string.update_release_notes)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(label(R.string.update_no_release_notes)).assertIsDisplayed()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(label(R.string.update_release_notes)).assertIsFocused()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(label(R.string.update_available)).assertDoesNotExist()
        assertEquals(1, fixture.dismissals)
        assertEquals(0, fixture.downloads)
    }

    @Test
    fun downloadProgressAndErrorsRemainVisibleWithUpdateDisabled() {
        val fixture = mount("Fixture notes.")
        compose.runOnIdle {
            fixture.state = fixture.state.copy(isDownloading = true, downloadProgress = 0.42f)
        }
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.update_downloading_progress, 42))
            .assertIsDisplayed()
        compose.onNodeWithText(label(R.string.update_now)).assertIsNotEnabled()
        compose.runOnIdle {
            fixture.state = fixture.state.copy(isDownloading = false, errorMessage = "Fixture download failed")
        }
        compose.onNodeWithText("Fixture download failed").assertIsDisplayed()
        assertEquals(0, fixture.downloads)
    }

    private fun mount(notes: String): Fixture {
        val fixture = Fixture(notes)
        compose.setContent {
            NuvioTheme {
                UpdateBannerHost(
                    state = fixture.state,
                    onDismissBanner = {
                        fixture.dismissals++
                        fixture.state = fixture.state.copy(showBanner = false)
                    },
                    onDownload = { fixture.downloads++ },
                    onDismissUnknownSources = {},
                    onOpenUnknownSources = {},
                    onFeedbackShown = {}
                ) {}
            }
        }
        compose.onNodeWithText(label(R.string.update_later)).assertIsFocused()
        return fixture
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private class Fixture(notes: String) {
        var state by mutableStateOf(UpdateUiState(
            showBanner = true,
            update = AppUpdate(
                tag = "v9.9.9",
                title = "Component fixture 9.9.9",
                notes = notes,
                releaseUrl = null,
                assetName = "fixture.apk",
                assetUrl = "https://example.invalid/fixture.apk",
                assetSizeBytes = 1,
                assetSha256 = "ab".repeat(32)
            )
        ))
        var downloads = 0
        var dismissals = 0
    }
}
