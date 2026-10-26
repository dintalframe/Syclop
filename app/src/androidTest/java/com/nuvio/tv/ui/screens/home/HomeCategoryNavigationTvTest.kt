package com.nuvio.tv.ui.screens.home

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real category presentation, entry, header and card focus with synthetic metadata only.
 * No ViewModel, account, repository, image URL, source search or player is created.
 */
@RunWith(AndroidJUnit4::class)
class HomeCategoryNavigationTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun lateHomeBodyCannotStealHeaderAndDownRestoresRememberedCard() {
        val mounted = mutableStateOf(false)
        val ready = AtomicReference(false)
        val requests = AtomicInteger()
        val homeRows = listOf(row("late-home", card("home-first", "First Home fixture card"),
            card("home-second", "Remembered Home fixture card")))
        compose.setContent {
            NuvioTheme {
                HomeBrowseScaffold(HomeBrowseSection.HOME, onSelect = {}) { _, _ ->
                    if (mounted.value) {
                        val state = HomeUiState(catalogRows = homeRows, homeRows = homeRows.map(HomeRow::Catalog),
                            isLoading = false, layoutPreferencesReady = true,
                            modernLandscapePostersEnabled = true, continueWatchingEnabled = false,
                            heroEnrichmentEnabled = false, catalogTypeSuffixEnabled = false,
                            focusedPosterBackdropTrailerEnabled = false, focusedPosterBackdropExpandEnabled = false)
                        val prepared = rememberHomeCategoryPresentation(HomeBrowseSection.HOME, 1L, 1L, homeRows, state)
                        SideEffect { ready.set(prepared.current && prepared.presentation.rows.list.isNotEmpty()) }
                        if (prepared.presentation.rows.list.isNotEmpty()) ModernHomeContent(
                            uiState = state, modernPresentation = prepared.presentation,
                            focusState = HomeScreenFocusState(), // Keep Home's real default automatic entry enabled.
                            onNavigateToDetail = { _, _, _ -> requests.incrementAndGet() },
                            onContinueWatchingClick = { requests.incrementAndGet() },
                            onRequestTrailerPreview = { _, _, _, _ -> requests.incrementAndGet() },
                            onLoadMoreCatalog = { _, _, _ -> requests.incrementAndGet() },
                            onRemoveContinueWatching = { _, _, _, _ -> requests.incrementAndGet() },
                            onSaveFocusState = { _, _, _, _, _, _, _, _ -> }
                        )
                    }
                }
            }
        }
        focusHeader(HomeBrowseSection.HOME)
        // Home's real body appears only after catalog preparation, later than its header.
        compose.runOnIdle { mounted.value = true }
        compose.waitUntil(timeoutMillis = 15_000) { ready.get() }
        compose.waitForIdle()
        assertHeaderFocused(HomeBrowseSection.HOME)
        capture("category-home-late-header")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardFocused("First Home fixture card")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertCardFocused("Remembered Home fixture card")
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertHeaderFocused(HomeBrowseSection.HOME)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardFocused("Remembered Home fixture card")
        capture("category-home-remembered-entry")
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertHeaderFocused(HomeBrowseSection.HOME)
        assertEquals(0, requests.get())
    }

    @Test
    fun lateAnimeCatalogKeepsFocusedCardAndOneUpReturnsToHeader() {
        val ready = row("anime-ready", card("anime-ready", "Ready anime card", anime = true))
        val late = row("anime-late", card("anime-late", "Late anime card", anime = true))
        val fixture = Fixture(HomeBrowseSection.ANIME, mapOf(HomeBrowseSection.ANIME to listOf(ready)))
        show(fixture)
        waitForRows(fixture, HomeBrowseSection.ANIME, listOf("anime-ready"))
        focusHeader(HomeBrowseSection.ANIME)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardFocused("Ready anime card")

        // The earlier request finishes only after a later request's card is already focused.
        compose.runOnIdle { fixture.rows.value = mapOf(HomeBrowseSection.ANIME to listOf(late, ready)) }
        waitForRows(fixture, HomeBrowseSection.ANIME, listOf("anime-ready", "anime-late"))
        assertCardFocused("Ready anime card")
        capture("category-anime-late-arrival")
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertHeaderFocused(HomeBrowseSection.ANIME)
        fixture.assertNoNavigationOrPlaybackRequests()
    }

    @Test
    fun lateKidsGroupKeepsSafeEntryAndOneUpReturnsToHeader() {
        val family = card("kids-family", "Safe family card", rating = "TV-Y")
        val science = card("kids-science", "Safe science card", rating = "TV-G", genres = listOf("Science", "Documentary"))
        val original = row("kids", family)
        assertEquals(KidsBrowseGroup.SHOWS, HomeBrowseRules.kidsGroup(family, original))
        assertEquals(KidsBrowseGroup.SCIENCE, HomeBrowseRules.kidsGroup(science, original))
        val fixture = Fixture(HomeBrowseSection.KIDS, mapOf(HomeBrowseSection.KIDS to listOf(original)))
        show(fixture)
        waitForRows(fixture, HomeBrowseSection.KIDS, listOf(groupedRow("kids", KidsBrowseGroup.SHOWS)))
        focusHeader(HomeBrowseSection.KIDS)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardFocused("Safe family card")

        compose.runOnIdle {
            fixture.rows.value = mapOf(HomeBrowseSection.KIDS to listOf(original.copy(items = listOf(science, family))))
        }
        waitForRows(fixture, HomeBrowseSection.KIDS, listOf(groupedRow("kids", KidsBrowseGroup.SHOWS), groupedRow("kids", KidsBrowseGroup.SCIENCE)))
        assertCardFocused("Safe family card")
        capture("category-kids-late-group")
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertHeaderFocused(HomeBrowseSection.KIDS)
        fixture.assertNoNavigationOrPlaybackRequests()
    }

    @Test
    fun changingCategoriesKeepsHeaderFocusAndUsesNewCategoryRows() {
        val fixture = Fixture(HomeBrowseSection.MOVIES, mapOf(
            HomeBrowseSection.MOVIES to listOf(row("movies", card("movie", "Movie fixture card", type = ContentType.MOVIE))),
            HomeBrowseSection.TV to listOf(row("tv", card("tv", "TV fixture card"))),
            HomeBrowseSection.ANIME to listOf(row("anime", card("anime", "Anime fixture card", anime = true))),
            HomeBrowseSection.DOCUMENTARY to listOf(row("documentary", card("documentary", "Documentary fixture card", genres = listOf("Documentary")))),
            HomeBrowseSection.KIDS to listOf(row("kids", card("kids", "Kids fixture card", rating = "TV-Y")))
        ))
        show(fixture, withSidebarEntry = true)
        waitForRows(fixture, HomeBrowseSection.MOVIES, listOf("movies"))
        focusHeader(HomeBrowseSection.MOVIES)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardFocused("Movie fixture card")
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertHeaderFocused(HomeBrowseSection.MOVIES)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForRows(fixture, HomeBrowseSection.TV, listOf("tv"))
        assertHeaderFocused(HomeBrowseSection.TV)
        compose.onNodeWithText("Movie fixture card").assertDoesNotExist()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardFocused("TV fixture card")
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertHeaderFocused(HomeBrowseSection.TV)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForRows(fixture, HomeBrowseSection.ANIME, listOf("anime"))
        assertHeaderFocused(HomeBrowseSection.ANIME)
        compose.onNodeWithText("TV fixture card").assertDoesNotExist()
        capture("category-change-anime-header")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForRows(fixture, HomeBrowseSection.DOCUMENTARY, listOf("documentary"))
        assertHeaderFocused(HomeBrowseSection.DOCUMENTARY)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForRows(fixture, HomeBrowseSection.KIDS, listOf(groupedRow("kids", KidsBrowseGroup.SHOWS)))
        assertHeaderFocused(HomeBrowseSection.KIDS)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertHeaderFocused(HomeBrowseSection.KIDS)
        fixture.assertNoNavigationOrPlaybackRequests()
    }

    @Test
    fun newRequestRevisionRestoresConfiguredOrderWithoutOldFocus() {
        val ready = row("ready", card("ready", "Original request card", anime = true))
        val configuredFirst = row("configured-first", card("configured-first", "Configured first card", anime = true))
        val fixture = Fixture(HomeBrowseSection.ANIME, mapOf(HomeBrowseSection.ANIME to listOf(ready)))
        show(fixture)
        waitForRows(fixture, HomeBrowseSection.ANIME, listOf("ready"))
        focusHeader(HomeBrowseSection.ANIME)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertCardFocused("Original request card")
        compose.runOnIdle { fixture.rows.value = mapOf(HomeBrowseSection.ANIME to listOf(configuredFirst, ready)) }
        waitForRows(fixture, HomeBrowseSection.ANIME, listOf("ready", "configured-first"))
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertHeaderFocused(HomeBrowseSection.ANIME)

        // An intentional catalog configuration change begins a fresh presentation order.
        compose.runOnIdle { fixture.requestRevision.longValue = 2L }
        waitForRows(fixture, HomeBrowseSection.ANIME, listOf("configured-first", "ready"), revision = 2L)
        assertHeaderFocused(HomeBrowseSection.ANIME)
        compose.onAllNodes(hasText("Original request card") and isFocused()).assertCountEquals(0)
        capture("category-request-order-reset")
        fixture.assertNoNavigationOrPlaybackRequests()
    }

    private data class PreparedRows(
        val section: HomeBrowseSection,
        val revision: Long,
        val current: Boolean,
        val catalogOrder: List<String>
    )

    private class Fixture(initialSection: HomeBrowseSection, initialRows: Map<HomeBrowseSection, List<CatalogRow>>) {
        val section = mutableStateOf(initialSection)
        val rows = mutableStateOf(initialRows)
        val requestRevision = mutableLongStateOf(1L)
        val prepared = AtomicReference<PreparedRows?>(null)
        val navigationRequests = AtomicInteger()
        val trailerRequests = AtomicInteger()

        fun assertNoNavigationOrPlaybackRequests() {
            assertEquals(0, navigationRequests.get())
            assertEquals(0, trailerRequests.get())
        }
    }

    private fun show(fixture: Fixture, withSidebarEntry: Boolean = false) {
        val sidebarEntry = if (withSidebarEntry) FocusRequester() else FocusRequester.Default
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
                    CompositionLocalProvider(LocalContentFocusRequester provides sidebarEntry) {
                        HomeBrowseScaffold(fixture.section.value, onSelect = { fixture.section.value = it }) { _, entry ->
                            val section = fixture.section.value
                            val revision = fixture.requestRevision.longValue
                            key(section, 1L, revision) { CategoryFixtureBody(fixture, section, revision, entry) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CategoryFixtureBody(fixture: Fixture, section: HomeBrowseSection, revision: Long, entry: HomeBrowseEntry) {
        val rows = fixture.rows.value[section].orEmpty()
        val uiState = HomeUiState(
            catalogRows = rows, homeRows = rows.map(HomeRow::Catalog),
            isLoading = false, layoutPreferencesReady = true,
            modernLandscapePostersEnabled = true, continueWatchingEnabled = false,
            heroEnrichmentEnabled = false, catalogTypeSuffixEnabled = false,
            focusedPosterBackdropTrailerEnabled = false, focusedPosterBackdropExpandEnabled = false
        )
        val prepared = rememberHomeCategoryPresentation(section, 1L, revision, rows, uiState)
        val request = rememberHomeCategoryEntryRequest(entry, prepared.presentation.rows.list.isNotEmpty(),
            isCurrent = { true }, consumeReturn = { false })
        SideEffect {
            fixture.prepared.set(PreparedRows(section, revision, prepared.current,
                prepared.presentation.rows.list.map { row ->
                    val group = row.key.substringAfter("::kids:", "")
                    if (group.isEmpty()) row.catalogId.orEmpty() else "${row.catalogId}::kids:$group"
                }))
        }
        if (prepared.presentation.rows.list.isNotEmpty()) {
            ModernHomeContent(
                uiState = uiState, modernPresentation = prepared.presentation,
                focusState = HomeScreenFocusState(), autoFocusOnEntry = false,
                contentEntryRequest = request, inheritPreviousHeroBackdrop = false,
                onNavigateToDetail = { _, _, _ -> fixture.navigationRequests.incrementAndGet() },
                onContinueWatchingClick = { fixture.navigationRequests.incrementAndGet() },
                onRequestTrailerPreview = { _, _, _, _ -> fixture.trailerRequests.incrementAndGet() },
                onLoadMoreCatalog = { _, _, _ -> fixture.navigationRequests.incrementAndGet() },
                onRemoveContinueWatching = { _, _, _, _ -> fixture.navigationRequests.incrementAndGet() },
                onSaveFocusState = { _, _, _, _, _, _, _, _ -> }
            )
        }
    }

    private fun waitForRows(fixture: Fixture, section: HomeBrowseSection, expected: List<String>, revision: Long = 1L) {
        compose.waitUntil(timeoutMillis = 15_000) {
            fixture.prepared.get()?.let { it.section == section && it.revision == revision && it.current && it.catalogOrder == expected } == true
        }
        compose.waitForIdle()
    }

    private fun focusHeader(section: HomeBrowseSection) {
        compose.onNodeWithText(section.label).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        assertHeaderFocused(section)
    }

    private fun assertHeaderFocused(section: HomeBrowseSection) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(section.label) and isFocused()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText(section.label).assertIsFocused()
    }

    private fun assertCardFocused(title: String) {
        val target = hasText(title) and isFocusable()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(target and isFocused()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(target).assertIsDisplayed().assertIsFocused()
    }

    private fun press(code: Int) {
        assertTrue(code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT))
        instrumentation.sendKeyDownUpSync(code)
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

    private fun groupedRow(catalogId: String, group: KidsBrowseGroup) = "$catalogId::kids:${group.name}"

    private fun row(id: String, vararg cards: MetaPreview) = CatalogRow(
        addonId = "category-fixture", addonName = "Category fixture", addonBaseUrl = "https://category.example.invalid",
        catalogId = id, catalogName = "Fixture catalog $id", type = cards.firstOrNull()?.type ?: ContentType.SERIES,
        items = cards.toList(), supportsSkip = false, hasMore = false
    )

    private fun card(
        id: String,
        title: String,
        type: ContentType = ContentType.SERIES,
        anime: Boolean = false,
        rating: String? = null,
        genres: List<String> = emptyList()
    ) = MetaPreview(
        id = "fixture:$id", type = type, name = title,
        poster = null, posterShape = PosterShape.LANDSCAPE, background = null, logo = null,
        description = "Synthetic category navigation fixture.", releaseInfo = "2020", imdbRating = null,
        genres = genres + if (anime) listOf("Anime") else emptyList(), ageRating = rating
    )
}
