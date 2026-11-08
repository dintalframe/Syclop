package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePreviewEnrichmentTvTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun startupPreviewSurvivesSameTitleFocusAndRejectsLateDifferentTitle() {
        val first = card("first", "First fixture title", "First catalog synopsis.")
        val second = card("second", "Second fixture title", "Second catalog synopsis.")
        val lateFirst = first.copy(description = "Late first enriched synopsis.")
        val enrichedSecond = second.copy(description = "Second enriched synopsis.")
        val rows = listOf(
            CatalogRow(
                addonId = "preview-fixture",
                addonName = "Preview fixture",
                addonBaseUrl = "https://preview.example.invalid",
                catalogId = "owned-preview",
                catalogName = "Owned preview fixture",
                type = ContentType.SERIES,
                items = listOf(first, second),
                supportsSkip = false,
                hasMore = false
            )
        )
        val state = HomeUiState(
            catalogRows = rows,
            homeRows = rows.map(HomeRow::Catalog),
            isLoading = false,
            layoutPreferencesReady = true,
            modernLandscapePostersEnabled = true,
            continueWatchingEnabled = false,
            heroEnrichmentEnabled = true,
            catalogTypeSuffixEnabled = false,
            focusedPosterBackdropTrailerEnabled = false,
            focusedPosterBackdropExpandEnabled = false
        )
        val mounted = mutableStateOf(false)
        val ready = AtomicBoolean()
        val focusState = HomeScreenFocusState()
        val pendingId = mutableStateOf<String?>(null)
        val completed = mutableStateOf<Map<String, MetaPreview>>(emptyMap())
        val lastRequestedId = AtomicReference<String?>()

        compose.setContent {
            NuvioTheme {
                HomeBrowseScaffold(HomeBrowseSection.HOME, onSelect = {}) { _, _ ->
                    if (mounted.value) {
                        val prepared = rememberHomeCategoryPresentation(
                            HomeBrowseSection.HOME, 1L, 1L, rows, state
                        )
                        SideEffect { ready.set(prepared.current && prepared.presentation.rows.list.isNotEmpty()) }
                        if (prepared.presentation.rows.list.isNotEmpty()) {
                            ModernHomeContent(
                                uiState = state,
                                modernPresentation = prepared.presentation,
                                focusState = focusState,
                                enrichingItemId = pendingId.value,
                                enrichedPreviews = completed.value,
                                onNavigateToDetail = { _, _, _ -> error("Unexpected detail navigation") },
                                onContinueWatchingClick = { error("Unexpected playback") },
                                onRequestTrailerPreview = { _, _, _, _ -> error("Unexpected trailer request") },
                                onLoadMoreCatalog = { _, _, _ -> },
                                onRemoveContinueWatching = { _, _, _, _ -> },
                                onItemFocus = { item ->
                                    pendingId.value = item.id
                                    lastRequestedId.set(item.id)
                                },
                                onSaveFocusState = { _, _, _, _, _, _, _, _ -> }
                            )
                        }
                    }
                }
            }
        }

        val header = compose.onNodeWithText(HomeBrowseSection.HOME.label, substring = false)
        header.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        header.assertIsFocused()
        compose.runOnIdle { mounted.value = true }
        compose.waitUntil(timeoutMillis = 15_000) { ready.get() }
        awaitHero(first)
        header.assertIsFocused()
        assertNull(lastRequestedId.get())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocusAndPending(first, lastRequestedId)
        // No enrichment has completed. Entering the first card keeps its startup preview.
        awaitHero(first)
        compose.runOnIdle {
            assertEquals(first.id, pendingId.value)
            assertEquals(emptyMap<String, MetaPreview>(), completed.value)
        }

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocusAndPending(second, lastRequestedId)
        awaitHeroGone(first)
        hero(second).assertDoesNotExist()

        // Finish the earlier request while the second request remains held.
        compose.runOnIdle { completed.value = mapOf(first.id to lateFirst) }
        compose.waitForIdle()
        hero(first).assertDoesNotExist()
        hero(lateFirst).assertDoesNotExist()
        hero(second).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(second.id, pendingId.value)
            completed.value = completed.value + (second.id to enrichedSecond)
            pendingId.value = null
        }
        awaitHero(enrichedSecond)
        assertCardFocused(second)
        hero(lateFirst).assertDoesNotExist()
    }

    private fun card(id: String, title: String, synopsis: String) = MetaPreview(
        id = "fixture:$id",
        type = ContentType.SERIES,
        name = title,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = synopsis,
        releaseInfo = "2020",
        imdbRating = null,
        genres = emptyList()
    )

    private fun hero(item: MetaPreview) =
        compose.onNodeWithText(checkNotNull(item.description), substring = false)

    private fun awaitHero(item: MetaPreview) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(checkNotNull(item.description), substring = false))
                .fetchSemanticsNodes().size == 1
        }
        hero(item).assertIsDisplayed()
    }

    private fun awaitHeroGone(item: MetaPreview) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(checkNotNull(item.description), substring = false))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitFocusAndPending(item: MetaPreview, lastRequestedId: AtomicReference<String?>) {
        compose.waitUntil(timeoutMillis = 10_000) { lastRequestedId.get() == item.id }
        assertCardFocused(item)
    }

    private fun assertCardFocused(item: MetaPreview) {
        compose.onNode(hasText(item.name) and isFocusable() and isFocused())
            .assertIsDisplayed()
            .assertIsFocused()
    }

    private fun press(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }
}
