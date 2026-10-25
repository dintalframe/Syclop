package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production labelled controls without an account, media URL or active player. */
@RunWith(AndroidJUnit4::class)
class PlaybackActionButtonsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val startLabel get() = instrumentation.targetContext.getString(R.string.cw_action_start_from_beginning)
    private val nextLabel get() = instrumentation.targetContext.getString(R.string.next_episode_label)
    private val next = NextEpisodeInfo("fixture:1:2", 1, 2, "Episode 2", null, null,
        "2020-01-01", hasAired = true, available = true, unairedMessage = null)

    @Test
    fun ordinaryRemotePressSelectsEachLabelledActionOnce() {
        var beginningRequests = 0
        var nextRequests = 0
        setActions(PlayerUiState(nextEpisode = next),
            onBeginning = { beginningRequests++ }, onNext = { nextRequests++ })
        compose.onNodeWithText(startLabel).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(startLabel).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle {
            assertEquals(1, beginningRequests)
            assertEquals(0, nextRequests)
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(nextLabel).assertIsDisplayed().assertIsFocused()
        capture("playback-labelled-start-and-next")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle {
            assertEquals(1, beginningRequests)
            assertEquals(1, nextRequests)
        }
    }

    @Test
    fun finalEpisodeKeepsStartFromBeginningWithoutANextAction() {
        setActions(PlayerUiState(nextEpisode = null))
        compose.onNodeWithText(startLabel).assertIsDisplayed()
        compose.onNodeWithText(nextLabel).assertDoesNotExist()
        capture("playback-final-episode-actions")
    }

    @Test
    fun unairedEpisodeDoesNotOfferNext() {
        setActions(PlayerUiState(nextEpisode = next.copy(hasAired = false)))
        compose.onNodeWithText(nextLabel).assertDoesNotExist()
        compose.onNodeWithText(startLabel).assertIsDisplayed()
    }

    @Test
    fun inFlightNextSearchDoesNotOfferAnotherNextAction() {
        setActions(PlayerUiState(nextEpisode = next,
            postPlayMode = PostPlayMode.AutoPlay(next, searching = true)))
        compose.onNodeWithText(nextLabel).assertDoesNotExist()
        compose.onNodeWithText(startLabel).assertIsDisplayed()
    }

    private fun setActions(
        state: PlayerUiState,
        onBeginning: () -> Unit = {},
        onNext: () -> Unit = {}
    ) {
        setContent {
            PlaybackActionButtons(
                showStartFromBeginning = true,
                showNextEpisode = state.canManuallyPlayNextEpisode(),
                onStartFromBeginning = onBeginning,
                onNextEpisode = onNext
            )
        }
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(40.dp),
                    contentAlignment = Alignment.BottomStart) { content() }
            }
        }
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
}
