package com.nuvio.tv.ui.screens.player

import android.content.Context
import com.nuvio.tv.core.player.MpvInfoActiveDecoder
import com.nuvio.tv.core.player.MpvInfoSnapshot
import com.nuvio.tv.core.player.MpvInfoVideoCodec
import com.nuvio.tv.data.local.InternalPlayerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class MpvStreamInfoDataTest {
    @Test fun `MPV info uses native codec dimensions and rates rather than stale Exo video facts`() {
        val controller = controller()
        val view = mockk<NuvioMpvSurfaceView>()
        val actual = MpvInfoSnapshot(videoCodec = MpvInfoVideoCodec.AVC, width = 1280, height = 720,
            sourceFrameRate = 60.0, estimatedFrameRate = 29.97,
            activeDecoder = MpvInfoActiveDecoder.MEDIACODEC_COPY, speed = 1.0, droppedFrames = 7)
        every { controller.mpvView } returns view
        every { view.readInfoSnapshot() } returns actual
        every { controller._exoPlayer } throws AssertionError("MPV must not read the retained Exo player")

        val info = controller.buildStreamInfoData()
        assertEquals("AVC / H.264", info.videoCodec)
        assertEquals(1280, info.videoWidth); assertEquals(720, info.videoHeight)
        assertEquals(60f, info.videoFrameRate)
        assertNull(info.videoBitrate)
        assertEquals(actual, info.mpvSnapshot)
        assertEquals(InternalPlayerEngine.MVP_PLAYER, info.internalPlayerEngine)
    }

    @Test fun `missing MPV native data stays unknown without Exo or cached state fallbacks`() {
        val controller = controller()
        every { controller.mpvView } returns null
        every { controller._exoPlayer } throws AssertionError("MPV must not read stale Exo state")
        val info = controller.buildStreamInfoData()
        assertEquals(MpvInfoSnapshot(), info.mpvSnapshot)
        assertNull(info.videoCodec); assertNull(info.videoWidth); assertNull(info.videoHeight)
        assertNull(info.videoFrameRate); assertNull(info.videoBitrate)
    }

    @Test fun `engine handoff during native read discards snapshot instead of publishing facts for the old engine`() {
        val controller = controller()
        val view = mockk<NuvioMpvSurfaceView>()
        var engine = InternalPlayerEngine.MVP_PLAYER
        every { controller.currentInternalPlayerEngine } answers { engine }
        every { controller.mpvView } returns view
        every { view.readInfoSnapshot() } answers {
            engine = InternalPlayerEngine.EXOPLAYER
            MpvInfoSnapshot(videoCodec = MpvInfoVideoCodec.AVC, width = 1280, height = 720, sourceFrameRate = 60.0)
        }
        val info = controller.buildStreamInfoData()
        assertNull(info.mpvSnapshot)
        assertEquals(InternalPlayerEngine.MVP_PLAYER, info.internalPlayerEngine)
        assertNull(info.videoCodec); assertNull(info.videoWidth); assertNull(info.videoHeight)
        assertNull(info.videoFrameRate); assertNull(info.videoBitrate)
    }

    @Test fun `Exo engine never samples a retained MPV view or shows its diagnostics`() {
        val controller = controller()
        val oldMpv = mockk<NuvioMpvSurfaceView>()
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.mpvView } returns oldMpv
        every { controller._exoPlayer } returns null
        val info = controller.buildStreamInfoData()
        assertNull(info.mpvSnapshot)
        assertEquals("Old HEVC", info.videoCodec)
        assertEquals(3840, info.videoWidth); assertEquals(2160, info.videoHeight)
        assertEquals(24f, info.videoFrameRate); assertEquals(20_000_000, info.videoBitrate)
        verify(exactly = 0) { oldMpv.readInfoSnapshot() }
    }

    @Test fun `source handoff during native read rejects the old file snapshot even with the same engine`() {
        val controller = controller()
        val view = mockk<NuvioMpvSurfaceView>()
        var url = "https://synthetic.invalid/first"
        every { controller.currentStreamUrl } answers { url }
        every { controller.mpvView } returns view
        every { view.readInfoSnapshot() } answers {
            url = "https://synthetic.invalid/second"
            MpvInfoSnapshot(videoCodec = MpvInfoVideoCodec.HEVC, width = 3840, height = 2160)
        }
        val info = controller.buildStreamInfoData()
        assertNull(info.mpvSnapshot)
        assertNull(info.videoCodec); assertNull(info.videoWidth); assertNull(info.videoHeight)
        assertFalse(info.isForPlayback(InternalPlayerEngine.MVP_PLAYER, url))
    }

    @Test fun `cached INFO origin rejects other engines or sources without retaining a signed URL`() {
        val url = "https://synthetic.invalid/file?synthetic-secret=do-not-retain"
        val controller = controller()
        every { controller.currentStreamUrl } returns url
        every { controller.mpvView } returns null
        val info = controller.buildStreamInfoData()
        assertTrue(info.isForPlayback(InternalPlayerEngine.MVP_PLAYER, url))
        assertFalse(info.isForPlayback(InternalPlayerEngine.EXOPLAYER, url))
        assertFalse(info.isForPlayback(InternalPlayerEngine.MVP_PLAYER, "https://synthetic.invalid/other"))
        assertFalse(info.toString().contains("synthetic-secret"))
        assertFalse(info.toString().contains("https://"))
        assertEquals(64, info.mediaOriginKey?.length)
        assertNull(streamInfoMediaOriginKey(null))
        assertNull(streamInfoMediaOriginKey(""))
        assertNull(streamInfoMediaOriginKey("x".repeat(16_385)))
    }

    private fun controller(): PlayerRuntimeController {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        every { controller._uiState } returns MutableStateFlow(PlayerUiState(detectedFrameRate = 24f))
        every { controller.playbackTimeline } returns MutableStateFlow(PlaybackTimelineState())
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.MVP_PLAYER
        every { controller.currentAddonName } returns null
        every { controller.currentAddonLogo } returns null
        every { controller.currentStreamDescription } returns null
        every { controller.currentFilename } returns null
        every { controller.currentVideoSize } returns null
        every { controller.currentVideoWidth } returns 3840
        every { controller.currentVideoHeight } returns 2160
        every { controller.currentVideoBitrate } returns 20_000_000
        every { controller.currentVideoCodec } returns "Old HEVC"
        every { controller.currentStreamUrl } returns "https://synthetic.invalid/video"
        every { controller.serverPlayback.session(any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "Synthetic engine label"
        every { controller.context } returns context
        return controller
    }
}
