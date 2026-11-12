package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactGridHomeTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun catalogLeadsAndBackRestoresReorderedPoster() {
        val titles = (1..18).map { preview("grid-$it", "Grid title $it") }
        val featured = preview("featured", "Featured fixture")
        val resume = ContinueWatchingItem.InProgress(WatchProgress(
            contentId = "resume", contentType = "movie", name = "Continue fixture", poster = null,
            backdrop = null, logo = null, videoId = "resume", season = null, episode = null,
            episodeTitle = null, position = 90_000, duration = 240_000, lastWatched = 1L
        ))
        val provider = "https://catalog.example"
        val catalog = "fixture"
        fun home(items: List<MetaPreview>) = HomeUiState(
            homeLayout = HomeLayout.GRID,
            heroItems = listOf(featured),
            heroCatalogKeys = listOf("featured"),
            continueWatchingItems = listOf(resume),
            gridItems = listOf(
                GridItem.Hero(listOf(featured)),
                GridItem.SectionDivider("Fixture catalog", catalog, provider, "provider", "movie")
            ) + items.map { GridItem.Content(it, provider, catalog, "Fixture catalog", "provider") },
            isLoading = false,
            layoutPreferencesReady = true,
            posterLabelsEnabled = true
        )
        val state = mutableStateOf(home(titles))
        val detail = mutableStateOf<String?>(null)
        val opened = mutableListOf<Triple<String, String, String>>()
        var saved = HomeScreenFocusState()

        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme {
                HomeBrowseScaffold(HomeBrowseSection.HOME, onSelect = {}) { _, _ ->
                    val selected = detail.value
                    if (selected == null) {
                        GridHomeContent(
                            uiState = state.value,
                            gridFocusState = saved,
                            onNavigateToDetail = { id, type, addon ->
                                opened += Triple(id, type, addon)
                                detail.value = id
                            },
                            posterCardStyle = PosterCardDefaults.Style.copy(width = 126.dp, height = 189.dp),
                            onContinueWatchingClick = {},
                            onNavigateToCatalogSeeAll = { _, _, _ -> },
                            onRemoveContinueWatching = { _, _, _, _ -> },
                            onSaveGridFocusState = { index, offset, key ->
                                saved = HomeScreenFocusState(
                                    verticalScrollIndex = index, verticalScrollOffset = offset,
                                    focusedItemKey = key, hasSavedFocus = key != null
                                )
                            }
                        )
                    } else {
                        BackHandler { detail.value = null }
                        Text("Details for $selected")
                    }
                }
            }
        }

        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("Grid title 2").assertIsDisplayed()
        compose.onNodeWithText("Grid title 8").assertIsDisplayed()
        compose.onNodeWithText("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(hasClickAction() and hasContentDescription("Grid title 2")).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Details for grid-2").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(Triple("grid-2", "movie", provider)), opened)
            state.value = home(titles.filterNot { it.id == "grid-2" } + titles[1])
        }
        key(KeyEvent.KEYCODE_BACK)
        compose.onNode(hasClickAction() and hasContentDescription("Grid title 2")).assertIsFocused()
    }

    private fun key(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
    }

    private fun preview(id: String, name: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = name,
        poster = "android.resource://${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/drawable/syclop_primary_head_512",
        posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )
}
