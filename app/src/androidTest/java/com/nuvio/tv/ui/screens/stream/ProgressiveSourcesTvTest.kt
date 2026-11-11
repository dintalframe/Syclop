package com.nuvio.tv.ui.screens.stream

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.SourceSearchDiagnostics
import com.nuvio.tv.domain.model.SourceSearchReason
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production picker and stable source focus, using only synthetic rows and reason counts. */
@RunWith(AndroidJUnit4::class)
class ProgressiveSourcesTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun laterBetterResultDoesNotStealTheSelectedSourceFocus() {
        val first = source("Selected source", 'a')
        val later = source("Later higher bitrate source", 'b')
        val streams = mutableStateOf(listOf(first))
        val returning = mutableStateOf(false)
        val restoreHandled = AtomicBoolean(false)
        val selections = mutableListOf<Stream>()
        var selectedIndex = 0
        var selectedKey: String? = null
        show(
            streams = { streams.value },
            focusedStreamIndex = { selectedIndex },
            selectedStreamKey = { selectedKey },
            shouldRestoreFocusedStream = { returning.value },
            onRestoreFocusedStreamHandled = { restoreHandled.set(true) },
            onSelected = { selected, key, index ->
                selections += selected
                selectedKey = key
                selectedIndex = index
            }
        )
        compose.onNodeWithText("Selected source").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Selected source").assertIsFocused()
        compose.runOnIdle { streams.value = listOf(later, first) }
        compose.waitForIdle()
        compose.onNodeWithText("Selected source").assertIsFocused()
        compose.onNodeWithText("Later higher bitrate source").assertIsDisplayed()
        capture("progressive-sources-stable-focus")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(first), selections)
            streams.value = listOf(first, later)
            returning.value = true
        }

        compose.waitUntil(timeoutMillis = 5_000) { restoreHandled.get() }
        compose.onNodeWithText("Selected source").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(listOf(first, first), selections) }
    }

    @Test fun emptySearchDisplaysStructuredReasonsWithoutMediaOrAccountData() {
        val diagnostic = SourceSearchDiagnostics(providers = 4, completed = 2, failed = 2,
            complete = true, counts = mapOf(SourceSearchReason.SEASON_PACK_HIDDEN to 12,
                SourceSearchReason.RATE_LIMITED to 1, SourceSearchReason.TIMEOUT to 1))
        val detailsLabel = instrumentation.targetContext.getString(R.string.source_search_details)
        val hideDetailsLabel = instrumentation.targetContext.getString(R.string.source_hide_search_details)
        val retryLabel = instrumentation.targetContext.getString(R.string.stream_retry)
        val noSourcesLabel = instrumentation.targetContext.getString(R.string.stream_no_streams)
        val findingLabel = instrumentation.targetContext.getString(R.string.stream_finding_source)
        val summary = "2/4 providers completed · 1 timed out · 1 rate limited · 12 season packs hidden"
        var retries = 0
        val otherSourceStatus = mutableStateOf(SourceChipStatus.LOADING)
        // The loading chip shimmers indefinitely. Keep control of its frames until it settles.
        compose.mainClock.autoAdvance = false
        show(
            streams = { emptyList() },
            diagnostics = listOf(diagnostic),
            sourceChips = {
                listOf(SourceChipItem("Synthetic sources", SourceChipStatus.SUCCESS),
                    SourceChipItem("Other source", otherSourceStatus.value))
            },
            onRetry = { retries++ }
        )
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText(findingLabel).assertIsDisplayed()
        compose.onNodeWithText(retryLabel).assertDoesNotExist()
        compose.runOnIdle { otherSourceStatus.value = SourceChipStatus.ERROR }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(summary).assertDoesNotExist()
        compose.onNodeWithText(detailsLabel).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(detailsLabel).assertIsFocused()

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(hideDetailsLabel).assertIsFocused()
        compose.onNodeWithText(summary).assertIsDisplayed()
        capture("progressive-sources-empty-diagnostics")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(detailsLabel).assertIsFocused()
        compose.onNodeWithText(summary).assertDoesNotExist()

        compose.onNodeWithText(noSourcesLabel).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(retryLabel).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun show(
        streams: () -> List<Stream>,
        diagnostics: List<SourceSearchDiagnostics> = emptyList(),
        sourceChips: () -> List<SourceChipItem> = { listOf(SourceChipItem("Synthetic sources", SourceChipStatus.SUCCESS)) },
        focusedStreamIndex: () -> Int = { 0 },
        selectedStreamKey: () -> String? = { null },
        shouldRestoreFocusedStream: () -> Boolean = { false },
        onRestoreFocusedStreamHandled: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSelected: (Stream, String, Int) -> Unit = { _, _, _ -> }
    ) {
        compose.setContent {
            NuvioTheme {
                val restoring = shouldRestoreFocusedStream()
                key(restoring) {
                    RightStreamSection(isLoading = false, resumeText = null, error = null,
                        searchDiagnostics = diagnostics, streams = streams(), availableAddons = listOf("Synthetic sources"),
                        sourceChips = sourceChips(),
                        selectedAddonFilter = null, showFileSizeBadges = false, showAddonLogo = false,
                        badgePlacement = StreamBadgePlacement.BOTTOM, onAddonFilterSelected = {}, onRefresh = {},
                        sharedStream = null, partySource = null, onSharedStreamSelected = {}, onStreamSelected = onSelected,
                        focusedStreamIndex = focusedStreamIndex(), selectedStreamKey = selectedStreamKey(),
                        shouldRestoreFocusedStream = restoring,
                        onRestoreFocusedStreamHandled = onRestoreFocusedStreamHandled, onRetry = onRetry, hazeState = null,
                        modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background))
                }
            }
        }
    }

    private fun source(name: String, hash: Char) = Stream(name, "Show.S01E04.mkv", null, null, null,
        hash.toString().repeat(40), null, null, null, "Synthetic sources", null)

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
}
