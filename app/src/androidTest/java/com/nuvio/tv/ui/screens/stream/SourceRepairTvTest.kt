package com.nuvio.tv.ui.screens.stream

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridFailure
import com.nuvio.tv.core.debrid.DirectDebridFailureReason
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production source section with a synthetic terminal resolver failure; no account or media work. */
@RunWith(AndroidJUnit4::class)
class SourceRepairTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun sourceFailureStaysVisibleUntilTheViewerChoosesTheRetainedSourceList() {
        val failure = mutableStateOf<DirectDebridFailure?>(DirectDebridFailure(
            DirectDebridFailureReason.WRONG_EPISODE, "realdebrid"))
        var choices = 0
        var settings = 0
        var selected = 0
        show(failure = { failure.value }, onChoose = { choices++; failure.value = null },
            onSettings = { settings++ }, onSelected = { selected++ })
        val message = label(R.string.syclop_resolver_wrong_episode)
        val choose = label(R.string.source_repair_choose_another)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(choose).assertIsFocused()
        compose.onNodeWithText(label(R.string.update_open_settings)).assertDoesNotExist()
        compose.onNodeWithText("Synthetic source").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(5_000L)
        compose.onNodeWithText(message).assertIsDisplayed()

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithText("Synthetic source").assertIsFocused()
        compose.runOnIdle {
            assertEquals(1, choices)
            assertEquals(0, settings)
            assertEquals(0, selected)
        }
    }

    @Test fun onlyRejectedAuthorizationOffersTheActualSettingsDestination() {
        var settings = 0
        var choices = 0
        show(failure = { DirectDebridFailure(DirectDebridFailureReason.AUTH_EXPIRED, "realdebrid") },
            onChoose = { choices++ }, onSettings = { settings++ })
        compose.onNodeWithText(label(R.string.syclop_resolver_auth_expired)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.source_repair_choose_another)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.update_open_settings)).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, settings)
            assertEquals(0, choices)
        }
        compose.onNodeWithText("Synthetic source").assertIsDisplayed()
    }

    private fun show(failure: () -> DirectDebridFailure?, onChoose: () -> Unit,
        onSettings: () -> Unit, onSelected: () -> Unit = {}) {
        val source = Stream(name = "Synthetic source", title = null, description = null, url = null,
            ytId = null, infoHash = "a".repeat(40), fileIdx = null, externalUrl = null,
            behaviorHints = null, addonName = "Fixture", addonLogo = null)
        compose.setContent {
            NuvioTheme {
                RightStreamSection(isLoading = false, resumeText = null, error = null,
                    searchDiagnostics = emptyList(), streams = listOf(source), availableAddons = listOf("Fixture"),
                    sourceChips = listOf(SourceChipItem("Fixture", SourceChipStatus.SUCCESS)),
                    selectedAddonFilter = null, showFileSizeBadges = false, showAddonLogo = false,
                    badgePlacement = StreamBadgePlacement.BOTTOM, onAddonFilterSelected = {}, onRefresh = {},
                    sharedStream = null, partySource = null, onSharedStreamSelected = {},
                    onStreamSelected = { _, _, _ -> onSelected() }, focusedStreamIndex = 0, shouldRestoreFocusedStream = false,
                    onRestoreFocusedStreamHandled = {}, onRetry = {}, hazeState = null,
                    sourceResolutionFailure = failure(), onChooseAnotherSource = onChoose, onOpenSettings = onSettings,
                    modifier = Modifier.fillMaxSize())
            }
        }
    }

    private fun label(id: Int) = instrumentation.targetContext.getString(id)
}
