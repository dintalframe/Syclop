package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackActionAvailabilityTest {
    private val next = NextEpisodeInfo(
        videoId = "episode-2", season = 1, episode = 2, title = "Next",
        thumbnail = null, overview = null, released = null, hasAired = true,
        unairedMessage = null
    )

    @Test
    fun `manual next is available away from the end without enabling autoplay`() {
        assertTrue(PlayerUiState(nextEpisode = next).canManuallyPlayNextEpisode())
    }

    @Test
    fun `last episode and movies have no next action`() {
        assertFalse(PlayerUiState(contentType = "movie").canManuallyPlayNextEpisode())
        assertFalse(PlayerUiState(contentType = "series", currentSeason = 1, currentEpisode = 10).canManuallyPlayNextEpisode())
    }

    @Test
    fun `unaired unavailable and live episodes have no next action`() {
        assertFalse(PlayerUiState(nextEpisode = next.copy(hasAired = false)).canManuallyPlayNextEpisode())
        assertFalse(PlayerUiState(nextEpisode = next.copy(available = false)).canManuallyPlayNextEpisode())
        assertFalse(PlayerUiState(nextEpisode = next, isLive = true).canManuallyPlayNextEpisode())
    }

    @Test
    fun `searching and countdown suppress repeated next requests`() {
        assertFalse(PlayerUiState(nextEpisode = next, postPlayMode = PostPlayMode.AutoPlay(next, searching = true)).canManuallyPlayNextEpisode())
        assertFalse(PlayerUiState(nextEpisode = next, postPlayMode = PostPlayMode.AutoPlay(next, countdownSec = 5)).canManuallyPlayNextEpisode())
    }

    @Test
    fun `absolute anime numbering needs no invented season`() {
        assertTrue(PlayerUiState(nextEpisode = next.copy(season = null)).canManuallyPlayNextEpisode())
    }
}
