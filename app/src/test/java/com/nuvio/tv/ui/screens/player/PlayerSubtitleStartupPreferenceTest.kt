package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.InternalPlayerEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise the production preference application before a replacement discovers any tracks. */
class PlayerSubtitleStartupPreferenceTest {
    @Test fun `persisted Off disables text before initial selection despite global English`() {
        val f = Fixture(persisted = off)
        f.controller.applySubtitlePreferences("en", null)
        assertEquals(true, f.textDisabled())
    }

    @Test fun `current source handoff Off disables text before initial selection`() {
        val f = Fixture(pending = pending(URL, off))
        f.controller.applySubtitlePreferences("en", null)
        assertEquals(true, f.textDisabled())
    }

    @Test fun `another source handoff Off cannot disable current source subtitles`() {
        val f = Fixture(pending = pending("https://owned.invalid/old.mp4", off))
        f.controller.applySubtitlePreferences("en", null)
        assertEquals(false, f.textDisabled())
    }

    @Test fun `current internal selection takes precedence over older persisted Off`() {
        val internal = PlayerRuntimeController.TrackPreference(subtitle =
            PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                PlayerRuntimeController.RememberedTrackSelection("en", "English", "5")))
        val f = Fixture(persisted = off, pending = pending(URL, internal))
        f.controller.applySubtitlePreferences("en", null)
        assertEquals(false, f.textDisabled())
    }

    @Test fun `global Off still disables text without a restored preference`() {
        val f = Fixture()
        f.controller.applySubtitlePreferences("none", null)
        assertEquals(true, f.textDisabled())
    }

    private class Fixture(persisted: PlayerRuntimeController.TrackPreference? = null,
        pending: PlayerRuntimeController.PendingEngineSwitchTrackPreference? = null) {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        private var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        init {
            val player = mockk<ExoPlayer>(relaxed = true)
            every { controller._exoPlayer } returns player
            every { controller._uiState } returns MutableStateFlow(PlayerUiState())
            every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
            every { controller.currentStreamUrl } returns URL
            every { controller.pendingEngineSwitchTrackPreference } returns pending
            every { controller.persistedTrackPreference } returns persisted
            every { player.trackSelectionParameters } answers { parameters }
            every { player.trackSelectionParameters = any() } answers { parameters = firstArg() }
        }
        fun textDisabled() = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes
    }

    private companion object {
        const val URL = "https://owned.invalid/current.mp4"
        val off = PlayerRuntimeController.TrackPreference(
            subtitle = PlayerRuntimeController.RememberedSubtitleSelection.Disabled)
        fun pending(url: String, preference: PlayerRuntimeController.TrackPreference) =
            PlayerRuntimeController.PendingEngineSwitchTrackPreference(url, preference, InternalPlayerEngine.EXOPLAYER)
    }
}
