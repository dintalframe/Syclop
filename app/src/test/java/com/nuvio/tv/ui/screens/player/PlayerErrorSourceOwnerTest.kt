package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.InternalPlayerEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the production read-only VM accessor without constructing its engine or account graph. */
class PlayerErrorSourceOwnerTest {
    @Test fun `the accessor returns all six current fields rather than original navigation identity`() {
        val f = fixture()

        assertEquals(PlayerProgressWriteOwner(7, "tt-current", "series", "native:cut-A:ep-3", 1, 3),
            f.viewModel.currentPlaybackErrorSourceOwner())
    }

    @Test fun `leaving and returning to a profile cannot capture an old controller identity`() {
        val f = fixture()
        f.activeProfile.value = 8
        f.selectionRevision++
        f.activeProfile.value = 7
        f.selectionRevision++

        assertNull(f.viewModel.currentPlaybackErrorSourceOwner())
        assertNoRepairActions(f)
    }

    @Test fun `a different resolver profile cannot capture source navigation`() {
        val f = fixture()
        f.resolverProfile.value = 8

        assertNull(f.viewModel.currentPlaybackErrorSourceOwner())
        assertNoRepairActions(f)
    }

    @Test fun `a missing native video cannot use the parent progress identity for source navigation`() {
        val f = fixture(currentVideoId = null)

        assertNull(f.viewModel.currentPlaybackErrorSourceOwner())
        assertNoRepairActions(f)
    }

    @Test fun `a blank native video cannot supply source navigation identity`() {
        for (videoId in listOf("", " \t ")) {
            val f = fixture(currentVideoId = videoId)

            assertNull(f.viewModel.currentPlaybackErrorSourceOwner())
            assertNoRepairActions(f)
        }
    }

    @Test fun `rendered finite Exo MP4 offers Retry and current sources without capturing authority`() {
        val f = fixture()

        assertTrue(f.viewModel.canRetryCurrentPlaybackError())
        assertTrue(f.viewModel.canChooseCurrentPlaybackErrorSource())
    }

    @Test fun `Retry remains unavailable outside its rendered finite foreground Exo MP4 subset`() {
        assertRetryDenied("startup before first rendered frame") { every { it.controller.hasRenderedFirstFrame } returns false }
        assertRetryDenied("MPV") { every { it.controller.currentInternalPlayerEngine } returns InternalPlayerEngine.MVP_PLAYER }
        assertRetryDenied("unresolved engine") { every { it.controller.currentInternalPlayerEngine } returns InternalPlayerEngine.AUTO }
        assertRetryDenied("HLS") { every { it.controller.currentStreamMimeType } returns MimeTypes.APPLICATION_M3U8 }
        assertRetryDenied("live") { every { it.player.isCurrentMediaItemLive } returns true }
        assertRetryDenied("torrent") { every { it.controller.isTorrentStream } returns true }
        assertRetryDenied("not seekable") { every { it.player.isCurrentMediaItemSeekable } returns false }
        assertRetryDenied("unknown duration") { every { it.player.duration } returns C.TIME_UNSET }
        assertRetryDenied("zero duration") { every { it.player.duration } returns 0L }
        assertRetryDenied("no current error") { it.ui.value = it.ui.value.copy(error = null) }
        assertRetryDenied("background") { every { it.controller.isInBackground } returns true }
        assertRetryDenied("released engine") { every { it.controller._exoPlayer } returns null }
    }

    @Test fun `incomplete current source destination cannot offer source or Retry labels`() {
        for (missing in listOf("season", "episode", "title")) {
            val f = fixture()
            assertTrue(f.viewModel.canRetryCurrentPlaybackError())
            when (missing) {
                "season" -> every { f.controller.currentSeason } returns null
                "episode" -> every { f.controller.currentEpisode } returns null
                "title" -> f.ui.value = f.ui.value.copy(title = "")
            }

            assertNoRepairActions(f)
        }
    }

    private fun assertRetryDenied(reason: String, change: (Fixture) -> Unit) {
        val f = fixture()
        assertTrue("Coherent supported control for $reason", f.viewModel.canRetryCurrentPlaybackError())
        change(f)
        assertFalse(reason, f.viewModel.canRetryCurrentPlaybackError())
        assertTrue("Current source selection remains available for $reason", f.viewModel.canChooseCurrentPlaybackErrorSource())
    }

    private fun assertNoRepairActions(f: Fixture) {
        assertFalse(f.viewModel.canRetryCurrentPlaybackError())
        assertFalse(f.viewModel.canChooseCurrentPlaybackErrorSource())
    }

    private class Fixture {
        val viewModel = mockk<PlayerViewModel>(relaxed = true)
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val player = mockk<ExoPlayer>(relaxed = true)
        val ui = MutableStateFlow(PlayerUiState(error = "Owned playback interrupted", title = "Owned series"))
        val activeProfile = MutableStateFlow(7)
        val resolverProfile = MutableStateFlow(7)
        var selectionRevision = 3L
    }

    private fun fixture(currentVideoId: String? = "native:cut-A:ep-3"): Fixture {
        val f = Fixture()
        val controller = f.controller
        val profiles = mockk<ProfileManager>(relaxed = true)
        val debrid = mockk<DebridSettingsDataStore>(relaxed = true)
        every { f.viewModel.currentPlaybackErrorSourceOwner() } answers { callOriginal() }
        every { f.viewModel.canRetryCurrentPlaybackError() } answers { callOriginal() }
        every { f.viewModel.canChooseCurrentPlaybackErrorSource() } answers { callOriginal() }
        every { f.viewModel["sourcePanelScopeCurrent"]() } answers { callOriginal() }
        every { controller.uiState } returns f.ui
        every { controller._exoPlayer } returns f.player
        every { controller.hasRenderedFirstFrame } returns true
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.currentStreamMimeType } returns MimeTypes.VIDEO_MP4
        every { controller.isTorrentStream } returns false
        every { controller.isInBackground } returns false
        every { f.player.isCurrentMediaItemSeekable } returns true
        every { f.player.isCurrentMediaItemLive } returns false
        every { f.player.duration } returns 130_000L
        every { controller.progressWriteCoordinator } throws AssertionError("UI availability must not capture progress authority")
        every { controller.profileId } returns 7
        every { controller.contentId } returns "tt-current"
        every { controller.contentType } returns "series"
        every { controller.currentVideoId } returns currentVideoId
        every { controller.currentSeason } returns 1
        every { controller.currentEpisode } returns 3
        every { profiles.activeProfileId } returns f.activeProfile
        every { profiles.activeProfileSelectionRevision } answers { f.selectionRevision }
        every { debrid.activeProfileId } returns f.resolverProfile
        set(f.viewModel, "controller", controller)
        set(f.viewModel, "profileManager", profiles)
        set(f.viewModel, "sourcePanelDebridSettings", debrid)
        set(f.viewModel, "sourcePanelProfileId", 7)
        set(f.viewModel, "sourcePanelSelectionRevision", 3L)
        return f
    }

    private fun set(target: PlayerViewModel, name: String, value: Any) {
        PlayerViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
