package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PersistedTrackPreference
import com.nuvio.tv.data.local.TrackPreferenceDataStore
import com.nuvio.tv.domain.model.Subtitle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.just
import io.mockk.Runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

/** Production restore and persistence functions; only engine/state/storage boundaries are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoredTrackPreferencePersistenceTest {
    @After fun cleanup() {
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTrackSelectionKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerAutomaticSubtitleSyncKt")
    }
    @Test fun `delay-only edit retains audio and disabled subtitles after restore request is consumed`() = runTest {
        val f = Fixture(backgroundScope, PlayerRuntimeController.TrackPreference(audio = english, subtitle = disabled))
        f.controller.applyPersistedTrackPreference(listOf(englishTrack), emptyList())
        assertNull(f.pending)
        f.controller.persistTrackPreference(); runCurrent()
        assertEquals("en", f.saved.single().audioLanguage)
        assertEquals("DISABLED", f.saved.single().subtitleType)
    }

    @Test fun `partial discovery preserves restored audio and still-pending subtitle in a delay-only write`() = runTest {
        val f = Fixture(backgroundScope, PlayerRuntimeController.TrackPreference(audio = english, subtitle = french))
        f.controller.applyPersistedTrackPreference(listOf(englishTrack), emptyList())
        assertNull(f.pending?.audio)
        assertEquals(french, f.pending?.subtitle)
        f.controller.persistTrackPreference(); runCurrent()
        assertEquals("en", f.saved.single().audioLanguage)
        assertEquals("INTERNAL", f.saved.single().subtitleType)
        assertEquals("fr", f.saved.single().subtitleLanguage)
    }

    @Test fun `restored internal subtitle survives later restore calls and persistence`() = runTest {
        val f = Fixture(backgroundScope, PlayerRuntimeController.TrackPreference(subtitle = french))
        val track = TrackInfo(0, "French", "fr", trackId = "sub-fr", isSelected = true)
        f.controller.applyPersistedTrackPreference(emptyList(), listOf(track))
        f.controller.applyPersistedTrackPreference(emptyList(), listOf(track))
        f.controller.persistTrackPreference(); runCurrent()
        assertEquals("INTERNAL", f.saved.single().subtitleType)
        assertEquals("sub-fr", f.saved.single().subtitleTrackId)
    }

    @Test fun `a missing track that was never restored is not resurrected by a later persistence call`() = runTest {
        val f = Fixture(backgroundScope, PlayerRuntimeController.TrackPreference(audio = french.track))
        f.controller.applyPersistedTrackPreference(listOf(englishTrack), emptyList())
        assertNull(f.pending)
        assertNull(f.remembered)
        f.controller.persistTrackPreference(); runCurrent()
        assertNull(f.saved.single().audioLanguage)
    }

    @Test fun `an existing explicit selection remains preferred over a restored field`() = runTest {
        val f = Fixture(backgroundScope, PlayerRuntimeController.TrackPreference(audio = english, subtitle = disabled))
        f.remembered = PlayerRuntimeController.TrackPreference(audio = french.track)
        f.controller.applyPersistedTrackPreference(listOf(englishTrack), emptyList())
        f.controller.persistTrackPreference(); runCurrent()
        assertEquals("fr", f.saved.single().audioLanguage)
        assertEquals("DISABLED", f.saved.single().subtitleType)
    }

    @Test fun `internal to addon fallback persists the addon actually selected`() = runTest {
        val addon = Subtitle("addon-fr", "https://example.invalid/current-fr.srt", "fr", "Synthetic addon", null)
        val f = Fixture(backgroundScope, PlayerRuntimeController.TrackPreference(subtitle = french),
            PlayerUiState(addonSubtitles = listOf(addon), isLoadingAddonSubtitles = false))
        stubAddonEngineBoundary(f)
        f.controller.applyPersistedTrackPreference(emptyList(), listOf(englishTrack))
        assertEquals(addon, f.ui.value.selectedAddonSubtitle)
        assertNull(f.pending)
        f.controller.persistTrackPreference(); runCurrent()
        assertEquals("ADDON", f.saved.single().subtitleType)
        assertEquals(addon.id, f.saved.single().addonSubtitleId)
        assertEquals(addon.url, f.saved.single().addonSubtitleUrl)
    }

    @Test fun `language fallbacks retain current matched track metadata rather than stale target ids`() = runTest {
        val preference = PlayerRuntimeController.TrackPreference(
            audio = PlayerRuntimeController.RememberedTrackSelection("en", "Old English", "old-audio"),
            subtitle = PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                PlayerRuntimeController.RememberedTrackSelection("fr", "Old French", "old-sub", isForcedHint = true)))
        val f = Fixture(backgroundScope, preference)
        f.controller.applyPersistedTrackPreference(
            listOf(TrackInfo(0, "Current English", "en", trackId = "current-audio", isSelected = true)),
            listOf(TrackInfo(0, "Current French", "fr", trackId = "current-sub", isForced = true, isSelected = true)))
        f.controller.persistTrackPreference(); runCurrent()
        assertEquals("current-audio", f.saved.single().audioTrackId)
        assertEquals("Current English", f.saved.single().audioName)
        assertEquals("current-sub", f.saved.single().subtitleTrackId)
        assertEquals("Current French", f.saved.single().subtitleName)
        assertEquals(true, f.saved.single().subtitleIsForced)
    }

    @Test fun `an exact addon restore retains its refreshed URL`() = runTest {
        val addon = Subtitle("addon-fr", "https://example.invalid/refreshed-fr.srt", "fr", "Synthetic addon", null)
        val savedPreference = PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            addon.id, "https://example.invalid/expired-fr.srt", "fr", addon.addonName)
        val f = Fixture(backgroundScope, PlayerRuntimeController.TrackPreference(subtitle = savedPreference),
            PlayerUiState(addonSubtitles = listOf(addon), isLoadingAddonSubtitles = false))
        stubAddonEngineBoundary(f)
        f.controller.applyPersistedTrackPreference(emptyList(), emptyList())
        f.controller.persistTrackPreference(); runCurrent()
        assertEquals("ADDON", f.saved.single().subtitleType)
        assertEquals(addon.url, f.saved.single().addonSubtitleUrl)
    }

    private fun stubAddonEngineBoundary(f: Fixture) {
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTrackSelectionKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerAutomaticSubtitleSyncKt")
        every { f.controller.selectAddonSubtitle(any()) } just Runs
        every { f.controller.maybeRunAutomaticSubtitleSync(any(), any()) } just Runs
    }

    private class Fixture(scope: CoroutineScope, initial: PlayerRuntimeController.TrackPreference,
        initialState: PlayerUiState = PlayerUiState()) {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val ui = MutableStateFlow(initialState)
        var pending: PlayerRuntimeController.TrackPreference? = initial
        var remembered: PlayerRuntimeController.TrackPreference? = null
        val saved = mutableListOf<PersistedTrackPreference>()
        init {
            val store = mockk<TrackPreferenceDataStore>(relaxed = true)
            val player = mockk<ExoPlayer>(relaxed = true)
            var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, initial.subtitle == disabled).build()
            every { controller._uiState } returns ui
            every { controller._exoPlayer } returns player
            every { player.trackSelectionParameters } answers { parameters }
            every { player.trackSelectionParameters = any() } answers { parameters = firstArg() }
            every { controller.scope } returns scope
            every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
            every { controller.pendingEngineSwitchTrackPreference } returns null
            every { controller.persistedTrackPreference } answers { pending }
            every { controller.persistedTrackPreference = any() } answers { pending = firstArg() }
            every { controller.rememberedTrackPreference } answers { remembered }
            every { controller.rememberedTrackPreference = any() } answers { remembered = firstArg() }
            every { controller.contentId } returns "synthetic-content"
            every { controller.currentVideoId } returns null
            every { controller.trackPreferenceDataStore } returns store
            coEvery { store.save("synthetic-content", any()) } answers { saved += secondArg<PersistedTrackPreference>() }
        }
    }
    private companion object {
        val english = PlayerRuntimeController.RememberedTrackSelection("en", "English", "audio-en")
        val englishTrack = TrackInfo(0, "English", "en", trackId = "audio-en", isSelected = true)
        val disabled = PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        val french = PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            PlayerRuntimeController.RememberedTrackSelection("fr", "French", "sub-fr"))
    }
}
