package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.util.Base64
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.components.StreamImportedBadgeChip
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses production playback metadata with synthetic formats, no accounts or media URLs. */
@RunWith(AndroidJUnit4::class)
class PlayerTechnicalBadgesTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val reference = PlayerMediaInfo(videoWidth = 3840, videoHeight = 1600,
        hdr = listOf("DV", "HDR10"), audioCodec = "E-AC-3-JOC", audioChannels = 6, audioLanguage = "eng")

    @Test fun unknownMetadataAppearsOnlyAfterFormatsArrive() {
        val info = mutableStateOf(PlayerMediaInfo())
        compose.setContent { NuvioTheme { Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.BottomStart) {
            PlayerTechnicalBadges(info.value)
        } } }
        compose.onNodeWithText("4K").assertDoesNotExist()
        compose.runOnIdle { info.value = reference }
        compose.onNodeWithText("4K").assertIsDisplayed()
        compose.onNodeWithText("Dolby Vision · Atmos").assertIsDisplayed()
        compose.onNodeWithText("English 5.1").assertIsDisplayed()
        capture("player-badges-reference")
    }

    @Test fun formatAndLanguageChangesRemoveStaleBadges() {
        val info = mutableStateOf(reference)
        compose.setContent { NuvioTheme { Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.BottomStart) {
            PlayerTechnicalBadges(info.value)
        } } }
        compose.onNodeWithText("Dolby Vision · Atmos").assertIsDisplayed()
        compose.runOnIdle { info.value = PlayerMediaInfo(videoWidth = 1920, videoHeight = 1080,
            audioCodec = "AAC", audioChannels = 2, audioLanguage = "ja") }
        compose.onNodeWithText("4K").assertDoesNotExist()
        compose.onNodeWithText("Dolby Vision · Atmos").assertDoesNotExist()
        compose.onNodeWithText("1080p").assertIsDisplayed()
        compose.onNodeWithText("Japanese 2.0").assertIsDisplayed()
        capture("player-badges-changed-track")
    }

    @Test fun playerPackDrawsEmbeddedArtworkAndKeepsTextFallback() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="34" height="14" viewBox="0 0 34 14"><rect width="34" height="14" fill="#00ff00"/></svg>"""
        fun embedded(value: String) = "data:image/svg+xml;base64," +
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val artwork = StreamBadge(name = "Editor artwork", imageURL = embedded(svg))
        val invalid = StreamBadge(name = "Unreadable artwork", imageURL = embedded("invalid SVG"))
        compose.setContent {
            NuvioTheme {
                Column(Modifier.fillMaxSize().background(Color.Black).padding(40.dp)) {
                    PlayerBadges(listOf(artwork, invalid))
                    StreamImportedBadgeChip(artwork.copy(name = "Source list label"))
                }
            }
        }
        val image = compose.onNodeWithContentDescription("Editor artwork")
        compose.waitUntil(timeoutMillis = 8_000) {
            val pixels = image.captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.green > 0.8f && center.red < 0.2f && center.blue < 0.2f
        }
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithText("Unreadable artwork").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unreadable artwork").assertIsDisplayed()
        compose.onNodeWithText("Source list label").assertIsDisplayed()
    }

    @Test fun narrowLargeTextLayoutWrapsAllGroups() {
        val title = "Bright video"
        val year = "2026"
        val episode = instrumentation.targetContext.getString(R.string.season_episode_format, 1, 2)
        val state = PlayerUiState(contentName = title, currentSeason = 1, currentEpisode = 2, releaseYear = year)
        compose.setContent { NuvioTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    PlayerControlsBottomLayout(Modifier.testTag("white-playback-controls")) {
                        val titleRights = remember { mutableStateMapOf<String, Float>() }
                        Box(Modifier.width(230.dp)) {
                            PlayerPlaybackMetadataHeader(state, emptyList(), reference, titleRights)
                        }
                        Spacer(Modifier.height(NuvioTheme.spacing.md))
                        PlaybackActionButtons(showStartFromBeginning = true, showNextEpisode = false,
                            onStartFromBeginning = {}, onNextEpisode = {})
                    }
                }
            }
        } }
        val badgeTops = listOf("4K", "Dolby Vision · Atmos", "English 5.1").map { label ->
            compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("The production badge row wraps", badgeTops.max() - badgeTops.min() > 1f)

        val viewport = compose.onNodeWithTag("white-playback-controls")
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        val pixels = viewport.captureToImage().toPixelMap()
        val sampleX = pixels.width - 8 // Right padding, away from text and buttons.
        val oldScrimHeight = 200 * instrumentation.targetContext.resources.displayMetrics.density
        for (label in listOf(title, episode, year)) {
            val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            if (label == title) {
                assertTrue("Headline is above the old 200dp scrim", viewportBounds.bottom - bounds.center.y > oldScrimHeight)
            }
            val backing = pixels[sampleX, (bounds.center.y - viewportBounds.top).toInt()]
            assertTrue("Metadata needs a dark backing on white video: $label", backing.red < 0.34f)
        }
        assertTrue("The fade leaves the upper video untouched", pixels[sampleX, 4].red > 0.98f)
        capture("player-badges-narrow-large-text")
    }

    @Test fun metadataDoesNotInterceptRemoteActionFocus() {
        val start = instrumentation.targetContext.getString(R.string.cw_action_start_from_beginning)
        val next = instrumentation.targetContext.getString(R.string.next_episode_label)
        compose.setContent { NuvioTheme {
            Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(40.dp), contentAlignment = Alignment.BottomStart) {
                Column {
                    PlayerTechnicalBadges(reference)
                    PlaybackActionButtons(showStartFromBeginning = true, showNextEpisode = true,
                        onStartFromBeginning = {}, onNextEpisode = {})
                }
            }
        } }
        compose.onNodeWithText(start).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(start).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        compose.onNodeWithText(next).assertIsFocused()
        capture("player-badges-remote-focus")
    }

    @Test fun exoFormatsRequireExplicitAtmosSignal() {
        fun labels(mime: String) = technicalPlaybackBadges(exoMediaInfo(null,
            Format.Builder().setSampleMimeType(mime).setChannelCount(8).setLanguage("eng").build()))
        assertEquals(listOf("Atmos", "English 7.1"), labels(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals(listOf("TrueHD", "English 7.1"), labels(MimeTypes.AUDIO_TRUEHD))
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
