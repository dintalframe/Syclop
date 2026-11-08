package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTechnicalBadgesTest {
    @Test fun `cropped UHD and explicit JOC render the reference labels without a badge pack`() {
        val info = PlayerMediaInfo(videoWidth = 3840, videoHeight = 1600,
            hdr = listOf("DV", "HDR10"), audioCodec = "E-AC-3-JOC", audioChannels = 6, audioLanguage = "eng")
        assertEquals(listOf("4K", "Dolby Vision · Atmos", "English 5.1"), technicalPlaybackBadges(info))
    }

    @Test fun `TrueHD and surround channels alone do not assert Atmos`() {
        assertEquals(listOf("TrueHD", "7.1"), technicalPlaybackBadges(
            PlayerMediaInfo(audioCodec = "TrueHD", audioChannels = 8)))
        assertEquals(listOf("Dolby Digital Plus", "5.1"), technicalPlaybackBadges(
            PlayerMediaInfo(audioCodec = "E-AC-3", audioChannels = 6)))
    }

    @Test fun `unknown metadata stays absent instead of inventing defaults`() {
        assertEquals(emptyList<String>(), technicalPlaybackBadges(PlayerMediaInfo()))
        assertEquals(emptyList<String>(), technicalPlaybackBadges(PlayerMediaInfo(
            videoWidth = -1, videoHeight = 1080, audioChannels = -1, audioLanguage = "und")))
    }

    @Test fun `adaptive quality and alternate audio replace all previous facts`() {
        val first = PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160, hdr = listOf("HDR10"),
            audioCodec = "E-AC-3-JOC", audioChannels = 6, audioLanguage = "eng")
        val next = PlayerMediaInfo(videoWidth = 1920, videoHeight = 1080,
            audioCodec = "AAC", audioChannels = 2, audioLanguage = "ja")
        assertEquals(listOf("4K", "HDR10 · Atmos", "English 5.1"), technicalPlaybackBadges(first))
        assertEquals(listOf("1080p", "AAC", "Japanese 2.0"), technicalPlaybackBadges(next))
    }

    @Test fun `snapshot cannot label another source or engine`() {
        val info = PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160)
        val snapshot = PlayerBadgeMediaSnapshot(info, streamInfoMediaOriginKey("https://example.invalid/a"),
            InternalPlayerEngine.EXOPLAYER)
        assertEquals(info, snapshot.forPlayback(InternalPlayerEngine.EXOPLAYER, "https://example.invalid/a"))
        assertNull(snapshot.forPlayback(InternalPlayerEngine.EXOPLAYER, "https://example.invalid/b"))
        assertNull(snapshot.forPlayback(InternalPlayerEngine.MVP_PLAYER, "https://example.invalid/a"))
        assertNull(snapshot.copy(sourceKey = null).forPlayback(InternalPlayerEngine.EXOPLAYER, null))
    }

    @Test fun `explicit audio profile supports Atmos but unknown language stays hidden`() {
        assertEquals(listOf("Atmos", "7.1"), technicalPlaybackBadges(PlayerMediaInfo(
            audioCodec = "TrueHD", audioProfile = "Dolby TrueHD + Dolby Atmos", audioChannels = 8, audioLanguage = "zxx")))
    }
}
