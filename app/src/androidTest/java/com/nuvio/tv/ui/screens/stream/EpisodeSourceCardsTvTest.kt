package com.nuvio.tv.ui.screens.stream

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
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
import androidx.tv.material3.Text
import com.nuvio.tv.core.debrid.EpisodeSourcePolicy
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production filtering and source cards, with synthetic metadata and no account or media URL. */
@RunWith(AndroidJUnit4::class)
class EpisodeSourceCardsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val episodeName = "Episode S01E04"
    private val packName = "Complete seasons 1-12"

    @Test
    fun completeSeasonPackIsHiddenAndExactEpisodeCardKeepsRemoteFocus() {
        var selected: Stream? = null
        val exact = source(episodeName, "The.Big.Bang.Theory.S01E04.1080p.WEB.mkv")
        val raw = listOf(source(packName, "The.Big.Bang.Theory.S01-S12.Complete.1080p"),
            source("Complete Season 1", "The.Big.Bang.Theory.S01E04.1080p.WEB.mkv"), exact)
        val visible = EpisodeSourcePolicy.filter(raw, episodeRequest = true)
        assertEquals(listOf(exact), visible)
        show(visible) { selected = it }
        compose.onNodeWithText(packName).assertDoesNotExist()
        compose.onNodeWithText("Complete Season 1").assertDoesNotExist()
        compose.onNodeWithText(episodeName).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(episodeName).assertIsFocused()
        capture("episode-source-only-single-episode")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(exact, selected) }
    }

    @Test
    fun seasonPackOnlyResultsStayEmptyWithoutSubstitutingAnotherEpisode() {
        val visible = EpisodeSourcePolicy.filter(listOf(
            source(packName, "The.Big.Bang.Theory.S01-S12.Complete.1080p"),
            source("Season 1 pack", "The.Big.Bang.Theory.Season.1.Complete"),
        ), episodeRequest = true)
        assertEquals(emptyList<Stream>(), visible)
        show(visible) { error("No fixture source may be selected") }
        compose.onNodeWithText(packName).assertDoesNotExist()
        compose.onNodeWithText("Season 1 pack").assertDoesNotExist()
        compose.onNodeWithText("No single-episode candidates in this fixture").assertIsDisplayed()
        capture("episode-source-packs-only-empty")
    }

    private fun show(streams: List<Stream>, onClick: (Stream) -> Unit) {
        compose.setContent {
            NuvioTheme {
                Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Episode source fixture • The Big Bang Theory S01E04", color = NuvioTheme.colors.TextPrimary)
                    if (streams.isEmpty()) {
                        Text("No single-episode candidates in this fixture", color = NuvioTheme.colors.TextPrimary)
                    }
                    streams.forEach { stream ->
                        StreamCard(stream, showFileSizeBadges = false, showAddonLogo = false,
                            badgePlacement = StreamBadgePlacement.BOTTOM, onClick = { onClick(stream) })
                    }
                }
            }
        }
    }

    private fun source(name: String, title: String) = Stream(
        name = name, title = title, description = null, url = null, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = "Synthetic source fixture", addonLogo = null,
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
}
