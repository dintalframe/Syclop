package com.nuvio.tv.ui.screens.stream

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
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
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.SourceSearchDiagnostics
import com.nuvio.tv.domain.model.SourceSearchReason
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
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
        var selected: Stream? = null
        show(streams = { streams.value }, onSelected = { selected = it })
        compose.onNodeWithText("Selected source").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Selected source").assertIsFocused()
        compose.runOnIdle { streams.value = listOf(later, first) }
        compose.waitForIdle()
        compose.onNodeWithText("Selected source").assertIsFocused()
        compose.onNodeWithText("Later higher bitrate source").assertIsDisplayed()
        capture("progressive-sources-stable-focus")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(first, selected) }
    }

    @Test fun emptySearchDisplaysStructuredReasonsWithoutMediaOrAccountData() {
        val diagnostic = SourceSearchDiagnostics(providers = 4, completed = 2, failed = 2,
            complete = true, counts = mapOf(SourceSearchReason.SEASON_PACK_HIDDEN to 12,
                SourceSearchReason.RATE_LIMITED to 1, SourceSearchReason.TIMEOUT to 1))
        show(streams = { emptyList() }, diagnostics = listOf(diagnostic))
        compose.onNodeWithText("Why no sources? 2/4 providers completed · 1 timed out · 1 rate limited · 12 season packs hidden")
            .assertIsDisplayed()
        capture("progressive-sources-empty-diagnostics")
    }

    private fun show(streams: () -> List<Stream>, diagnostics: List<SourceSearchDiagnostics> = emptyList(),
        onSelected: (Stream) -> Unit = {}) {
        compose.setContent {
            NuvioTheme {
                RightStreamSection(isLoading = false, resumeText = null, error = null,
                    searchDiagnostics = diagnostics, streams = streams(), availableAddons = listOf("Synthetic sources"),
                    sourceChips = listOf(SourceChipItem("Synthetic sources", SourceChipStatus.SUCCESS)),
                    selectedAddonFilter = null, showFileSizeBadges = false, showAddonLogo = false,
                    badgePlacement = StreamBadgePlacement.BOTTOM, onAddonFilterSelected = {}, onRefresh = {},
                    sharedStream = null, partySource = null, onSharedStreamSelected = {}, onStreamSelected = onSelected,
                    focusedStreamIndex = 0, shouldRestoreFocusedStream = false,
                    onRestoreFocusedStreamHandled = {}, onRetry = {}, hazeState = null,
                    modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background))
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
