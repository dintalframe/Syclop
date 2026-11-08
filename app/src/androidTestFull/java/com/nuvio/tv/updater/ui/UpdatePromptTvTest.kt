package com.nuvio.tv.updater.ui

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.UpdateUiState
import com.nuvio.tv.updater.model.AppUpdate
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTvMaterial3Api::class)
class UpdatePromptTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val later get() = instrumentation.targetContext.getString(R.string.update_later)
    private val updateNow get() = instrumentation.targetContext.getString(R.string.update_now)

    @Test
    fun laterAndBackDismissAndRestoreHomeFocus() {
        val fixture = mount()
        showPrompt(fixture)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        assertDismissed(fixture, expectedDismissals = 1)

        showPrompt(fixture)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertDismissed(fixture, expectedDismissals = 2)
    }

    @Test
    fun updateBeginsOnlyAfterExplicitRemoteSelection() {
        val fixture = mount()
        showPrompt(fixture)
        compose.runOnIdle { assertEquals(0, fixture.downloads) }

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(updateNow).assertIsFocused()
        compose.runOnIdle { assertEquals(0, fixture.downloads) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, fixture.downloads) }
    }

    @Test
    fun automaticCheckRunsOnlyWhenReadyAndResumed() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        var ready by mutableStateOf(false)
        val checks = AtomicInteger()
        var downloads = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UpdateBannerHost(
                    state = UpdateUiState(),
                    onDismissBanner = {},
                    onDownload = { downloads++ },
                    onDismissUnknownSources = {},
                    onOpenUnknownSources = {},
                    onFeedbackShown = {},
                    automaticCheckReady = ready,
                    onAutomaticCheck = { checks.incrementAndGet() }
                ) {}
            }
        }

        fun assertCounts(expected: Int) {
            if (expected > 0) compose.waitUntil(timeoutMillis = 5_000) { checks.get() >= expected }
            compose.runOnIdle {
                assertEquals(expected, checks.get())
                assertEquals(0, downloads)
            }
        }

        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            ready = true
        }
        assertCounts(0)

        compose.runOnIdle { ready = false }
        assertCounts(0)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        assertCounts(0)

        compose.runOnIdle { ready = true }
        assertCounts(1)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        assertCounts(1)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        assertCounts(2)
    }

    private fun mount(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            NuvioTheme {
                val homeFocus = remember { FocusRequester() }
                UpdateBannerHost(
                    state = fixture.state,
                    onDismissBanner = {
                        fixture.dismissals++
                        fixture.state = fixture.state.copy(showBanner = false)
                    },
                    onDownload = {
                        fixture.downloads++
                        fixture.state = fixture.state.copy(isDownloading = true)
                    },
                    onDismissUnknownSources = {},
                    onOpenUnknownSources = {},
                    onFeedbackShown = {}
                ) {
                    LaunchedEffect(Unit) { homeFocus.requestFocusAfterFrames() }
                    Button(onClick = {}, modifier = Modifier.testTag("home").focusRequester(homeFocus)) {
                        Text("Home")
                    }
                }
            }
        }
        compose.onNodeWithTag("home").assertIsFocused()
        return fixture
    }

    private fun showPrompt(fixture: Fixture) {
        compose.runOnIdle { fixture.state = fixture.state.copy(showBanner = true) }
        compose.onNodeWithText(later).assertIsFocused()
    }

    private fun assertDismissed(fixture: Fixture, expectedDismissals: Int) {
        compose.onNodeWithText(later).assertDoesNotExist()
        compose.onNodeWithTag("home").assertIsFocused()
        compose.runOnIdle {
            assertEquals(expectedDismissals, fixture.dismissals)
            assertEquals(0, fixture.downloads)
        }
    }

    private class Fixture {
        var state by mutableStateOf(UpdateUiState(update = AppUpdate(
            tag = "v0.2.0",
            title = "Syclop 0.2.0",
            notes = "",
            releaseUrl = null,
            assetName = "syclop-arm64-release.apk",
            assetUrl = "https://github.com/dintalframe/Syclop/releases/download/v0.2.0/syclop-arm64-release.apk",
            assetSizeBytes = 1024,
            assetSha256 = "ab".repeat(32)
        )))
        var dismissals = 0
        var downloads = 0
    }
}
