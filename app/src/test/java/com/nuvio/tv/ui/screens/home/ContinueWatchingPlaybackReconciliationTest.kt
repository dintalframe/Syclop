package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingPlaybackReconciliationTest {
    @Test
    fun `finishing Big Bang Theory episode seven removes stale episode three pause`() {
        val oldPause = progress(3, 100L, 30L, WatchProgress.SOURCE_TRAKT_PLAYBACK)
        val completed = progress(7, 700L, 100L)
        val result = reconcileContinueWatchingInProgress(listOf(oldPause, completed), listOf(completed))
        val nextUp = nextUp(8)

        assertTrue(result.isEmpty())
        assertEquals(listOf(nextUp), mergeContinueWatchingItems(result.map { ContinueWatchingItem.InProgress(it) }, listOf(nextUp)))
    }

    @Test
    fun `part watched episode seven replaces older episode three`() {
        val oldPause = progress(3, 100L, 30L, WatchProgress.SOURCE_TRAKT_PLAYBACK)
        val current = progress(7, 700L, 40L)

        assertEquals(listOf(current), reconcileContinueWatchingInProgress(listOf(oldPause, current), emptyList()))
    }

    @Test
    fun `newer rewatch survives older completion even for a lower episode number`() {
        val completed = progress(7, 700L, 100L)
        val rewatch = progress(3, 800L, 40L)

        assertEquals(listOf(rewatch), reconcileContinueWatchingInProgress(listOf(completed, rewatch), listOf(completed)))
    }

    @Test
    fun `completion from another show cannot remove the current show pause`() {
        val pause = progress(3, 100L, 40L)
        val completed = progress(7, 700L, 100L).copy(contentId = "other", videoId = "other:1:7")

        assertEquals(listOf(pause), reconcileContinueWatchingInProgress(listOf(pause), listOf(completed)))
    }

    @Test
    fun `completed seed outside recent playback list still removes stale pause`() {
        val pause = progress(3, 100L, 40L, WatchProgress.SOURCE_TRAKT_PLAYBACK)
        val completedSeed = progress(7, 700L, 100L, WatchProgress.SOURCE_TRAKT_HISTORY)

        assertTrue(reconcileContinueWatchingInProgress(listOf(pause), listOf(completedSeed)).isEmpty())
    }

    @Test
    fun `unfinished next up seed cannot discard a legitimate paused episode`() {
        val pause = progress(3, 100L, 40L)
        val uncompleted = progress(7, 700L, 50L)

        assertEquals(listOf(pause), reconcileContinueWatchingInProgress(listOf(pause), listOf(uncompleted)))
    }

    @Test
    fun `account local completion participates in next up instead of losing to stale remote source rank`() {
        val stale = progress(3, 100L, 100L, WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS)
        val latest = progress(7, 700L, 100L).copy(localPlaybackScope = "current-account")

        assertEquals(latest, choosePreferredNextUpSeed(listOf(stale, latest)))
    }

    @Test
    fun `later external completion supersedes account local completion`() {
        val local = progress(7, 700L, 100L).copy(localPlaybackScope = "current-account")
        val external = progress(8, 800L, 100L, WatchProgress.SOURCE_TRAKT_HISTORY)

        assertEquals(external, choosePreferredNextUpSeed(listOf(local, external)))
        assertEquals(external, choosePreferredNextUpSeed(listOf(local, external), false))
    }

    @Test
    fun `latest episode mode honors a newer local rewatch`() {
        val oldCompletion = progress(7, 700L, 100L, WatchProgress.SOURCE_TRAKT_HISTORY)
        val rewatch = progress(3, 800L, 100L).copy(localPlaybackScope = "current-account")

        assertEquals(rewatch, choosePreferredNextUpSeed(listOf(oldCompletion, rewatch), false))
    }

    private fun progress(episode: Int, time: Long, position: Long, source: String = WatchProgress.SOURCE_LOCAL) = WatchProgress(
        contentId = "tt0898266", contentType = "series", name = "The Big Bang Theory",
        poster = null, backdrop = null, logo = null, videoId = "tt0898266:1:$episode",
        season = 1, episode = episode, episodeTitle = null, position = position,
        duration = 100L, lastWatched = time, source = source
    )

    private fun nextUp(episode: Int) = ContinueWatchingItem.NextUp(NextUpInfo(
        contentId = "tt0898266", contentType = "series", name = "The Big Bang Theory",
        poster = null, backdrop = null, logo = null, videoId = "tt0898266:1:$episode",
        season = 1, episode = episode, episodeTitle = null, thumbnail = null,
        lastWatched = 700L, sortTimestamp = 700L, seedSeason = 1, seedEpisode = episode - 1
    ))
}
