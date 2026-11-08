package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.WatchedHistoryItem
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeWatchedHistoryTvTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun latestWatchedEpisodeIsVisibleAndSelectOpensShowDetails() {
        val showId = "fixture:harbor"
        val showTitle = "The Long Way Back to the Harbor"
        val otherTitle = "The Lighthouse"
        val latest = WatchedHistoryItem(
            contentId = showId, contentType = "series", title = showTitle, watchedAt = 300L,
            videoId = "$showId:2:3", season = 2, episode = 3, episodeTitle = "Back Again"
        )
        val previous = latest.copy(watchedAt = 100L, videoId = "$showId:2:7", episode = 7)
        val other = WatchedHistoryItem(
            contentId = "fixture:lighthouse", contentType = "series", title = otherTitle, watchedAt = 200L,
            videoId = "fixture:lighthouse:1:4", season = 1, episode = 4
        )
        val history = mutableStateOf(listOf(previous, other, latest))
        val mounted = mutableStateOf(false)
        val ready = AtomicBoolean()
        val detailRequests = mutableListOf<Triple<String, String, String>>()
        val focusState = HomeScreenFocusState()

        compose.setContent {
            NuvioTheme {
                HomeBrowseScaffold(HomeBrowseSection.HOME, onSelect = {}) { _, _ ->
                    if (mounted.value) {
                        val observations = history.value
                        val state = remember(observations) {
                            projectHomeWatchedHistory(
                                HomeUiState(
                                    isLoading = false,
                                    layoutPreferencesReady = true,
                                    modernLandscapePostersEnabled = true,
                                    posterLabelsEnabled = false,
                                    continueWatchingEnabled = false,
                                    heroEnrichmentEnabled = false,
                                    focusedPosterBackdropTrailerEnabled = false,
                                    focusedPosterBackdropExpandEnabled = false
                                ),
                                observations
                            )
                        }
                        val prepared = rememberHomeCategoryPresentation(
                            HomeBrowseSection.HOME, 1L, 1L, state.catalogRows, state
                        )
                        SideEffect { ready.set(prepared.current && prepared.presentation.rows.list.isNotEmpty()) }
                        if (prepared.presentation.rows.list.isNotEmpty()) {
                            ModernHomeContent(
                                uiState = state,
                                modernPresentation = prepared.presentation,
                                focusState = focusState,
                                onNavigateToDetail = { id, type, base -> detailRequests += Triple(id, type, base) },
                                onContinueWatchingClick = { error("Watched History started playback") },
                                onRequestTrailerPreview = { _, _, _, _ -> error("Unexpected trailer request") },
                                onLoadMoreCatalog = { _, _, _ -> error("Watched History requested another page") },
                                onRemoveContinueWatching = { _, _, _, _ -> error("Watched History changed resume state") },
                                onSaveFocusState = { _, _, _, _, _, _, _, _ -> }
                            )
                        }
                    }
                }
            }
        }

        val header = compose.onNodeWithText(HomeBrowseSection.HOME.label)
        header.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        header.assertIsFocused()
        compose.runOnIdle { mounted.value = true }
        compose.waitUntil(timeoutMillis = 15_000) { ready.get() }

        val showCard = hasText(showTitle) and hasClickAction()
        val otherCard = hasText(otherTitle) and hasClickAction()
        compose.onAllNodes(showCard).assertCountEquals(1)
        compose.onAllNodes(otherCard).assertCountEquals(1)
        compose.onNode(showCard).assertIsDisplayed()
        compose.onNode(otherCard).assertIsDisplayed()
        compose.onNodeWithText("S02E03", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("S02E07", useUnmergedTree = true).assertDoesNotExist()

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(showCard and isFocused()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(showCard).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(otherCard).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNode(showCard).assertIsFocused()

        compose.runOnIdle {
            assertEquals(emptyList<Triple<String, String, String>>(), detailRequests)
            history.value = history.value + listOf(
                latest.copy(watchedAt = 400L, videoId = "$showId:2:4", episode = 4, episodeTitle = "The Return"),
                other.copy(watchedAt = 500L)
            )
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("S02E04"), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("S02E04", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("S02E03", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(showCard).assertCountEquals(1)
        compose.onNode(showCard).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(listOf(Triple(showId, "series", "")), detailRequests) }
    }

    private fun press(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
}
