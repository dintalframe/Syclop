package com.nuvio.tv.ui.screens.detail

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Account-free checks of the production detail hero; no source search or playback is started. */
@RunWith(AndroidJUnit4::class)
class PlaybackStartActionsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val startLabel get() = instrumentation.targetContext.getString(R.string.cw_action_start_from_beginning)

    @Test
    fun movieResumeAndStartFromBeginningAreSeparateRemoteActions() {
        verifyResumeActions(series = false, snapshot = "playback-movie-resume-and-start")
    }

    @Test
    fun episodeResumeAndStartFromBeginningAreSeparateRemoteActions() {
        verifyResumeActions(series = true, snapshot = "playback-episode-resume-and-start")
    }

    @Test
    fun unstartedMovieDoesNotOfferARedundantStartFromBeginningAction() {
        val meta = fixtureMeta(series = false)
        setContent {
            hero(meta, nextToWatch = NextToWatch(null, false, meta.id, null, null, "Play"))
        }
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithText(startLabel).assertDoesNotExist()
    }

    @Test
    fun unavailableEpisodeDisablesBothPlaybackActions() {
        val meta = fixtureMeta(series = true)
        var requests = 0
        setContent {
            hero(meta, resume(meta), isPlayEnabled = false,
                onResume = { requests++ }, onBeginning = { requests++ })
        }
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.playback_unavailable))
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(startLabel).assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    private fun verifyResumeActions(series: Boolean, snapshot: String) {
        val meta = fixtureMeta(series)
        val progress = resume(meta)
        var resumeRequests = 0
        var beginningRequests = 0
        setContent {
            hero(meta, progress, onResume = { resumeRequests++ }, onBeginning = { beginningRequests++ })
        }
        compose.onNodeWithText(progress.displayText).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(progress.displayText).assertIsFocused()
        compose.onNodeWithText(startLabel).assertIsDisplayed()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle {
            assertEquals(1, resumeRequests)
            assertEquals(0, beginningRequests)
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(startLabel).assertIsFocused()
        capture(snapshot)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle {
            assertEquals(1, resumeRequests)
            assertEquals(1, beginningRequests)
        }
    }

    @Composable
    private fun hero(
        meta: Meta,
        nextToWatch: NextToWatch,
        isPlayEnabled: Boolean = true,
        onResume: () -> Unit = {},
        onBeginning: () -> Unit = {}
    ) {
        HeroContentSection(
            meta = meta,
            nextEpisode = meta.videos.firstOrNull(),
            nextToWatch = nextToWatch,
            onPlayClick = onResume,
            isPlayEnabled = isPlayEnabled,
            onStartFromBeginning = onBeginning,
            isInLibrary = false,
            onToggleLibrary = {},
            onLibraryLongPress = {},
            isMovieWatched = false,
            isMovieWatchedPending = false,
            onToggleMovieWatched = {}
        )
    }

    private fun fixtureMeta(series: Boolean) = Meta(
        id = "fixture:playback-actions",
        type = if (series) ContentType.SERIES else ContentType.MOVIE,
        name = if (series) "Playback test series" else "Playback test movie",
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = "An account-free fixture for TV playback actions.",
        releaseInfo = "2020", imdbRating = null, genres = emptyList(), runtime = "60 min",
        director = emptyList(), cast = emptyList(),
        videos = if (series) listOf(Video("fixture:1:2", "Episode 2", "2020-01-01", null,
            season = 1, episode = 2, overview = null)) else emptyList(),
        country = null, awards = null, language = null, links = emptyList()
    )

    private fun resume(meta: Meta): NextToWatch {
        val series = meta.type == ContentType.SERIES
        val videoId = if (series) "fixture:1:2" else meta.id
        val progress = WatchProgress(meta.id, if (series) "series" else "movie", meta.name,
            null, null, null, videoId, if (series) 1 else null, if (series) 2 else null,
            if (series) "Episode 2" else null, 900_000, 3_600_000, 100)
        return NextToWatch(progress, true, videoId, progress.season, progress.episode,
            if (series) "Resume S1 E2" else "Resume")
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) { content() }
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
