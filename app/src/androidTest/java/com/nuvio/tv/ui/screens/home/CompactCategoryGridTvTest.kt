package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.key
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
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactCategoryGridTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dpadChangesScopeRestoresPosterAndLoadsOnlyItsCatalog() {
        val section = mutableStateOf(HomeBrowseSection.MOVIES)
        val detail = mutableStateOf<String?>(null)
        val saved = mutableMapOf<HomeBrowseSection, HomeScreenFocusState>()
        val pageRequests = mutableListOf<String>()
        var returning = false
        val catalog = CatalogRow("provider", "Provider", "https://category.example", "scoped", "Scoped catalog",
            ContentType.MOVIE, items = listOf(
                preview("movie-1", "Movie fixture one", ContentType.MOVIE),
                preview("movie-2", "Movie fixture two", ContentType.MOVIE),
                preview("tv-1", "TV fixture one", ContentType.SERIES)
            ), hasMore = true, supportsSkip = true, nextSkip = 10)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme {
                val selected = section.value
                HomeBrowseScaffold(selected, onSelect = { section.value = it }) { _, entry ->
                    val title = detail.value
                    if (title != null) {
                        BackHandler { detail.value = null }
                        Text("Details for $title")
                    } else key(selected) {
                        val projected = projectCategoryTrackingRows(selected, HomeUiState(),
                            listOf(HomeBrowseRules.scoped(selected, catalog)))
                        val row = projected.catalogRows.single()
                        val projection = buildHomeCategoryGrid(selected, projected.catalogRows,
                            listOf(HomeBrowseRow("${selected.name}-page", row.catalogName, row, loading = false)))
                        val request = rememberHomeCategoryEntryRequest(entry, ready = true,
                            isCurrent = { section.value == selected }, consumeReturn = {
                                returning.also { returning = false }
                            })
                        GridHomeContent(
                            uiState = projected.copy(homeLayout = HomeLayout.GRID,
                                gridItems = projection.items, heroSectionEnabled = false, posterLabelsEnabled = true),
                            gridFocusState = saved[selected] ?: HomeScreenFocusState(),
                            posterCardStyle = PosterCardDefaults.Style.copy(width = 104.dp, height = 156.dp),
                            limitCatalogPreview = false,
                            contentEntryRequest = request,
                            onNavigateToDetail = { id, _, _ -> returning = true; detail.value = id },
                            onContinueWatchingClick = {},
                            onNavigateToCatalogSeeAll = { _, _, _ -> error("Category used Home's See All route") },
                            onCatalogAction = { pageRequests += projection.pageRows.getValue(it).key },
                            catalogActionLabel = { "Load more category" },
                            onRemoveContinueWatching = { _, _, _, _ -> },
                            onSaveGridFocusState = { index, offset, key ->
                                saved[selected] = HomeScreenFocusState(verticalScrollIndex = index,
                                    verticalScrollOffset = offset, focusedItemKey = key, hasSavedFocus = true)
                            }
                        )
                    }
                }
            }
        }

        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("Movies").performSemanticsAction(SemanticsActions.RequestFocus)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertPosterFocus("Movie fixture one")
        compose.onNodeWithText("TV fixture one").assertDoesNotExist()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosterFocus("Movie fixture two")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Details for movie-2").assertIsDisplayed()
        press(KeyEvent.KEYCODE_BACK)
        assertPosterFocus("Movie fixture two")
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Movies").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("TV").assertIsFocused()
        compose.onNodeWithText("Movie fixture one").assertDoesNotExist()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertPosterFocus("TV fixture one")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertPosterFocus("Load more category")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(listOf("TV-page"), pageRequests) }
    }

    private fun assertPosterFocus(label: String) {
        compose.onNode(hasClickAction() and hasContentDescription(label)).assertIsFocused()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
    }

    private fun preview(id: String, name: String, type: ContentType) = MetaPreview(
        id = id, type = type, name = name, poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )
}
