package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class HomeContinueWatchingEntryTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun classicEntersContinueWatchingFromFreshHome() = verifyEntry(HomeLayout.CLASSIC)
    @Test fun gridEntersContinueWatchingFromFreshHome() = verifyEntry(HomeLayout.GRID)
    @Test fun modernEntersContinueWatchingFromFreshHome() = verifyEntry(HomeLayout.MODERN)

    private fun verifyEntry(layout: HomeLayout) {
        val hero = MetaPreview(
            id = "tt100001", type = ContentType.MOVIE, name = "Featured fixture", poster = null,
            posterShape = PosterShape.POSTER, background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
        )
        val resume = ContinueWatchingItem.InProgress(WatchProgress(
            contentId = "tt100002", contentType = "movie", name = "Continue fixture", poster = null,
            backdrop = null, logo = null, videoId = "tt100002", season = null, episode = null,
            episodeTitle = null, position = 90_000, duration = 240_000, lastWatched = 1L
        ))
        val state = HomeUiState(homeLayout = layout, heroItems = listOf(hero), heroCatalogKeys = listOf("featured"),
            gridItems = listOf(GridItem.Hero(listOf(hero))), continueWatchingItems = listOf(resume),
            isLoading = false, layoutPreferencesReady = true)
        val mounted = mutableStateOf(false)
        val selected = mutableListOf<String>()
        val watch: (ContinueWatchingItem) -> Unit = { item ->
            if (item == resume) selected += "resume"
        }
        val openDetails: (String, String, String) -> Unit = { id, _, _ -> selected += id }
        val initialFocus = HomeScreenFocusState()
        compose.setContent {
            NuvioTheme {
                HomeBrowseScaffold(HomeBrowseSection.HOME, onSelect = {}) { _, _ ->
                    if (mounted.value) when (layout) {
                        HomeLayout.CLASSIC -> ClassicHomeContent(
                            uiState = state, posterCardStyle = PosterCardDefaults.Style, focusState = initialFocus,
                            trailerPreviewUrls = emptyMap(), trailerPreviewAudioUrls = emptyMap(),
                            onNavigateToDetail = openDetails, onContinueWatchingClick = watch,
                            onNavigateToCatalogSeeAll = { _, _, _ -> }, onRemoveContinueWatching = { _, _, _, _ -> },
                            onRequestTrailerPreview = {}, onSaveFocusState = { _, _, _, _, _, _, _, _ -> }
                        )
                        HomeLayout.GRID -> GridHomeContent(
                            uiState = state, gridFocusState = initialFocus, onNavigateToDetail = openDetails,
                            onContinueWatchingClick = watch, onNavigateToCatalogSeeAll = { _, _, _ -> },
                            onRemoveContinueWatching = { _, _, _, _ -> }, onSaveGridFocusState = { _, _, _ -> }
                        )
                        HomeLayout.MODERN -> {
                            val context = LocalContext.current
                            val presentation = remember(context) {
                                buildModernHomePresentation(ModernHomePresentationInput(
                                    homeRows = emptyList(), catalogRows = emptyList(), continueWatchingItems = listOf(resume),
                                    upcomingItems = emptyList(), useLandscapePosters = false, showCatalogTypeSuffix = false,
                                    showFullReleaseDate = false, showImdbRatings = false, localeTag = "en"
                                ), ModernCarouselRowBuildCache(), context)
                            }
                            ModernHomeContent(
                                uiState = state, modernPresentation = presentation, focusState = initialFocus,
                                onNavigateToDetail = openDetails, onContinueWatchingClick = watch,
                                onRequestTrailerPreview = { _, _, _, _ -> }, onLoadMoreCatalog = { _, _, _ -> },
                                onRemoveContinueWatching = { _, _, _, _ -> }, onSaveFocusState = { _, _, _, _, _, _, _, _ -> }
                            )
                        }
                    }
                }
            }
        }
        val home = compose.onNodeWithText("Home")
        home.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { mounted.value = true }
        compose.mainClock.advanceTimeBy(350)
        home.assertIsFocused()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(350)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf("resume"), selected) }
    }
}
