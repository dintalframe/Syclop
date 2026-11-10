package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dependent test-only stage: requires only the planned optional addonSubtitlesError parameter
 * and localized subtitle_lookup_failed string before it can compile. Neither is staged here.
 * Actual production overlay, native D-pad dispatch; no repository/media/network.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleLookupRepairTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun failedLookupOffersOneFocusedRetryWithoutClaimingNoResultsOrExposingRawError() {
        val f = show(error = RAW_ERROR)
        compose.onNodeWithText(label(R.string.subtitle_lookup_failed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.subtitle_no_addon)).assertDoesNotExist()
        compose.onNodeWithText(RAW_ERROR).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil { f.events.size == 1 }

        compose.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(PlayerEvent.OnRetry), f.events)
            assertEquals(0, f.trackChanges)
            assertEquals(0, f.disabled)
            assertEquals(0, f.dismissed)
        }
    }

    @Test fun completedEmptyLookupHasNoFailureOrRetry() {
        show(error = null)

        compose.onNodeWithText(label(R.string.subtitle_no_addon)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.subtitle_lookup_failed)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
    }

    @Test fun partialFailureKeepsTheValidSelectedSubtitleVisibleAndOffersRetry() {
        val f = show(error = RAW_ERROR, subtitles = listOf(OWNED_SUBTITLE), selected = OWNED_SUBTITLE)

        compose.onNodeWithText(label(R.string.subtitle_lookup_failed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.subtitle_no_addon)).assertDoesNotExist()
        compose.onNodeWithText("Owned valid addon").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_retry)).assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, f.trackChanges)
            assertEquals(0, f.disabled)
            assertEquals(emptyList<PlayerEvent>(), f.events)
        }
    }

    @Test fun loadingDoesNotOfferAnAdditionalRetryOrAnEmptyResultClaim() {
        show(error = RAW_ERROR, loading = true)

        compose.onNodeWithText(label(R.string.subtitle_loading_addon)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.subtitle_no_addon)).assertDoesNotExist()
        compose.onNodeWithText(RAW_ERROR).assertDoesNotExist()
    }

    private fun show(
        error: String?,
        loading: Boolean = false,
        subtitles: List<Subtitle> = emptyList(),
        selected: Subtitle? = null
    ): Fixture {
        val f = Fixture(loading)
        compose.setContent {
            NuvioTheme {
                SubtitleSelectionOverlay(
                    visible = true, internalTracks = emptyList(), selectedInternalIndex = -1,
                    addonSubtitles = subtitles, selectedAddonSubtitle = selected,
                    subtitleStyle = SubtitleStyleSettings(), subtitleDelayMs = 0,
                    installedSubtitleAddonOrder = listOf("Owned valid addon"),
                    isLoadingAddons = f.loading, addonSubtitlesError = error,
                    onInternalTrackSelected = { f.trackChanges++ },
                    onAddonSubtitleSelected = { f.trackChanges++ },
                    onDisableSubtitles = { f.disabled++ },
                    onEvent = { event ->
                        f.events.add(event)
                        if (event == PlayerEvent.OnRetry) f.loading = true
                    },
                    onDismiss = { f.dismissed++ }
                )
            }
        }
        compose.waitForIdle()
        return f
    }

    private fun label(id: Int): String = instrumentation.targetContext.getString(id)
    private fun press(keyCode: Int) {
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        compose.waitForIdle()
    }
    private class Fixture(initialLoading: Boolean) {
        var loading by mutableStateOf(initialLoading)
        val events = mutableListOf<PlayerEvent>()
        var trackChanges = 0
        var disabled = 0
        var dismissed = 0
    }
    private companion object {
        const val RAW_ERROR = "private.invalid/opaque?token=owned-sentinel"
        val OWNED_SUBTITLE = Subtitle("owned-en", "https://captions.example.invalid/owned.vtt", "en", "Owned valid addon", null)
    }
}
