package com.nuvio.tv.ui.screens.home

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real CW policy, cards, labels, focus and click routing using account-free synthetic progress. */
@RunWith(AndroidJUnit4::class)
class ContinueWatchingPlaybackTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun latestLocalEpisodeReplacesOlderPausedEpisode() {
        val stale = progress(3, 100L, completed = false, remote = true)
        val current = progress(7, 700L, completed = false)
        val rows = mutableStateOf(listOf(stale))
        val clicked = AtomicReference<ContinueWatchingItem?>()
        compose.setContent {
            val snapshot = rows.value
            val cards = remember(snapshot) {
                val inProgress = reconcileContinueWatchingInProgress(snapshot, emptyList())
                mergeContinueWatchingItems(inProgress.map { ContinueWatchingItem.InProgress(it) }, emptyList())
            }
            FixtureRow(cards, clicked)
        }
        assertEpisode(3)
        compose.runOnIdle { rows.value = listOf(stale, current) }
        assertEpisode(7)
        clickEpisode(7)
        val target = clicked.get() as ContinueWatchingItem.InProgress
        assertEquals("tt0898266:1:7", target.progress.videoId)
        assertEquals(480_000L, target.progress.position)
        capture("continue-watching-latest-episode.png")
    }

    @Test
    fun completedLatestEpisodeAdvancesNextUp() {
        val stale = progress(3, 100L, completed = false, remote = true)
        val completed = progress(7, 700L, completed = true)
        val finished = mutableStateOf(false)
        val clicked = AtomicReference<ContinueWatchingItem?>()
        compose.setContent {
            val isFinished = finished.value
            val cards = remember(isFinished) {
                val seeds = if (isFinished) listOf(completed) else emptyList()
                val rows = if (isFinished) listOf(stale, completed) else listOf(stale)
                val inProgress = reconcileContinueWatchingInProgress(rows, seeds)
                val next = if (isFinished) listOf(nextUp(8)) else emptyList()
                mergeContinueWatchingItems(inProgress.map { ContinueWatchingItem.InProgress(it) }, next)
            }
            FixtureRow(cards, clicked)
        }
        assertEpisode(3)
        compose.runOnIdle { finished.value = true }
        assertEpisode(8)
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.cw_next_up), useUnmergedTree = true).assertIsDisplayed()
        clickEpisode(8)
        val target = clicked.get() as ContinueWatchingItem.NextUp
        assertEquals("tt0898266:1:8", target.info.videoId)
        assertEquals(7, target.info.seedEpisode)
        capture("continue-watching-next-up.png")
    }

    @Composable
    private fun FixtureRow(items: List<ContinueWatchingItem>, clicked: AtomicReference<ContinueWatchingItem?>) {
        // Match Home's explicit remembered row state; projection changes must not recreate it.
        val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
        val rowFocusRequester = remember { FocusRequester() }
        val listState = rememberLazyListState()
        val lastFocusedIndex = remember { mutableIntStateOf(-1) }
        val onClick = remember(clicked) { { item: ContinueWatchingItem -> clicked.set(item) } }
        val onRemove = remember { { _: ContinueWatchingItem -> } }
        NuvioTheme {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 56.dp)) {
                ContinueWatchingSection(
                    items = items, onItemClick = onClick, onRemoveItem = onRemove,
                    focusedItemIndex = -1, focusRequesters = focusRequesters,
                    rowFocusRequester = rowFocusRequester, listState = listState,
                    lastFocusedIndexState = lastFocusedIndex
                )
            }
        }
    }

    private fun episodeLabel(episode: Int) = instrumentation.targetContext.getString(R.string.season_episode_format, 1, episode)
    private fun assertEpisode(episode: Int) {
        compose.waitForIdle()
        compose.onNodeWithText(episodeLabel(episode), useUnmergedTree = true).assertIsDisplayed()
    }
    private fun clickEpisode(episode: Int) {
        compose.onNode(hasClickAction() and hasAnyDescendant(hasText(episodeLabel(episode))), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, name).outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
    private fun progress(episode: Int, time: Long, completed: Boolean, remote: Boolean = false) = WatchProgress(
        contentId = "tt0898266", contentType = "series", name = "The Big Bang Theory",
        poster = null, backdrop = null, logo = null, videoId = "tt0898266:1:$episode",
        season = 1, episode = episode, episodeTitle = "Episode $episode",
        position = if (completed) 1_200_000L else 480_000L, duration = 1_200_000L, lastWatched = time,
        source = if (remote) WatchProgress.SOURCE_TRAKT_PLAYBACK else WatchProgress.SOURCE_LOCAL,
        localPlaybackScope = if (remote) null else "account-free-fixture-scope"
    )
    private fun nextUp(episode: Int) = ContinueWatchingItem.NextUp(NextUpInfo(
        contentId = "tt0898266", contentType = "series", name = "The Big Bang Theory",
        poster = null, backdrop = null, logo = null, videoId = "tt0898266:1:$episode",
        season = 1, episode = episode, episodeTitle = "Episode $episode", thumbnail = null,
        lastWatched = 700L, sortTimestamp = 700L, seedSeason = 1, seedEpisode = 7, hasAired = true
    ))
}
