package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.InternalPlayerEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real restore and disable functions; only the native player and controller state are fakes. */
class PlayerSubtitleOffRestoreTest {
    @Test fun `outgoing empty tracks clear cached UI without consuming replacement audio and Off`() {
        val f = Fixture()
        f.ui.value = f.ui.value.copy(
            audioTracks = listOf(TrackInfo(0, "English", "en", "audio-en"),
                TrackInfo(1, "Turkish", "tr", "audio-tr", isSelected = true)),
            selectedAudioTrackIndex = 1,
            subtitleTracks = listOf(TrackInfo(0, "English", "en", "sub-en", isSelected = true)),
            selectedSubtitleTrackIndex = 0)
        f.pending = f.pending?.copy(preference = PlayerRuntimeController.TrackPreference(
            audio = PlayerRuntimeController.RememberedTrackSelection("tr", "Turkish", "audio-tr"),
            subtitle = PlayerRuntimeController.RememberedSubtitleSelection.Disabled))
        f.player.trackSelectionParameters = f.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        every { f.controller.isReleasingPlayer } returns true

        // clearMediaItems emits this on the still-current outgoing Exo during real release.
        f.controller.updateAvailableTracks(Tracks.EMPTY)

        assertTrue("Outgoing cached audio must not acknowledge replacement selection", f.ui.value.audioTracks.isEmpty())
        assertTrue("Outgoing cached text must drain before addon completion can restore", f.ui.value.subtitleTracks.isEmpty())
        assertEquals("tr", f.pending?.preference?.audio?.language)
        assertEquals("audio-tr", f.pending?.preference?.audio?.trackId)
        assertEquals(PlayerRuntimeController.RememberedSubtitleSelection.Disabled, f.pending?.preference?.subtitle)
    }

    @Test fun `pending Off disables TEXT before an empty discovery callback can consume it`() {
        val f = Fixture()

        f.controller.applyPersistedTrackPreference(emptyList(), emptyList())

        assertTrue("Off must disable the replacement selector before tracks are discovered",
            C.TRACK_TYPE_TEXT in f.player.trackSelectionParameters.disabledTrackTypes)
        assertEquals(PlayerRuntimeController.RememberedSubtitleSelection.Disabled, f.pending?.preference?.subtitle)
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)

        // A later callback may acknowledge Off only after the engine is actually disabled.
        f.controller.applyPersistedTrackPreference(emptyList(), emptyList())
        assertNull(f.pending)
    }

    @Test fun `pending Off disables TEXT when discovered subtitle tracks are not selected yet`() {
        val f = Fixture()
        val english = TrackInfo(0, "English", "en", "sub-en", isSelected = false)

        f.controller.applyPersistedTrackPreference(emptyList(), listOf(english))

        assertTrue("Unselected discovery metadata does not mean the TEXT selector is disabled",
            C.TRACK_TYPE_TEXT in f.player.trackSelectionParameters.disabledTrackTypes)
        assertEquals(PlayerRuntimeController.RememberedSubtitleSelection.Disabled, f.pending?.preference?.subtitle)

        f.controller.applyPersistedTrackPreference(emptyList(), listOf(english))
        assertNull(f.pending)
        assertEquals(-1, f.ui.value.selectedSubtitleTrackIndex)
    }

    private class Fixture {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val player = mockk<ExoPlayer>(relaxed = true)
        val ui = MutableStateFlow(PlayerUiState(selectedSubtitleTrackIndex = -1))
        var pending: PlayerRuntimeController.PendingEngineSwitchTrackPreference? =
            PlayerRuntimeController.PendingEngineSwitchTrackPreference(
                streamUrl = URL,
                preference = PlayerRuntimeController.TrackPreference(
                    subtitle = PlayerRuntimeController.RememberedSubtitleSelection.Disabled),
                sourceEngine = InternalPlayerEngine.EXOPLAYER)
        private var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

        init {
            every { controller._uiState } returns ui
            every { controller._exoPlayer } returns player
            every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
            every { controller.currentStreamUrl } returns URL
            every { controller.pendingEngineSwitchTrackPreference } answers { pending }
            every { controller.pendingEngineSwitchTrackPreference = any() } answers { pending = firstArg() }
            every { controller.persistedTrackPreference } returns null
            every { controller.exoSubtitleViewRef } returns null
            every { player.trackSelectionParameters } answers { parameters }
            every { player.trackSelectionParameters = any() } answers { parameters = firstArg() }
            assertFalse(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        }
    }

    private companion object {
        const val URL = "https://playback.example.invalid/granted/selected.mp4"
    }
}
