package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TraktWatchedHistoryTest {
    @Test fun `completed movies and exact episodes share chronological history without resume fabrication`() {
        val rows = recentTraktWatchedHistory(listOf(movie("tt100", "2026-01-01T00:00:00Z"),
            episode(2, "2026-01-03T00:00:00Z"), episode(1, "2026-01-02T00:00:00Z")))
        assertEquals(listOf("tt200:1:2", "tt200:1:1", "tt100"), rows.map { it.videoId })
        assertEquals(listOf("series", "series", "movie"), rows.map { it.contentType })
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), rows.last().watchedAt)
    }

    @Test fun `rewatches dedupe only the same movie or exact episode at its newest observation`() {
        val rows = recentTraktWatchedHistory(listOf(movie("tt100", "2026-01-01T00:00:00Z"),
            episode(1, "2026-01-02T00:00:00Z"), movie("tt100", "2026-01-04T00:00:00Z"),
            episode(1, "2026-01-03T00:00:00Z"), episode(2, "2026-01-02T00:00:00Z")))
        assertEquals(3, rows.size)
        assertEquals("tt100", rows.first().contentId)
        assertEquals(Instant.parse("2026-01-03T00:00:00Z").toEpochMilli(), rows[1].watchedAt)
        assertEquals(2, rows.count { it.contentId == "tt200" })
    }

    @Test fun `history preserves supplied category metadata and canonical TMDB fallback`() {
        val row = TraktHistoryItemDto(watchedAt = "2026-01-01T00:00:00Z", movie = TraktMovieDto(
            title = "Movie", ids = TraktIdsDto(tmdb = 10), genres = listOf("documentary"),
            certification = "G", country = "us", languages = listOf("en"), year = 2026))
        val actual = recentTraktWatchedHistory(listOf(row)).single()
        assertEquals("tmdb:10", actual.contentId)
        assertEquals(listOf("documentary"), actual.genres)
        assertEquals("G", actual.ageRating)
        assertEquals("us", actual.country)
        assertEquals(listOf("en"), actual.languages)
        assertEquals("2026", actual.releaseInfo)
        assertNull(actual.sourceAddonBaseUrl)
    }

    @Test fun `unknown timestamps identifiers and episode coordinates never acquire fabricated history`() {
        val valid = movie("tt100", "2026-01-01T00:00:00Z")
        assertTrue(recentTraktWatchedHistory(listOf(valid.copy(watchedAt = null),
            valid.copy(watchedAt = "bad-date"), valid.copy(movie = TraktMovieDto(title = "Unknown")),
            episode(0, "2026-01-01T00:00:00Z"),
            episode(1, "2026-01-01T00:00:00Z").copy(episode = TraktEpisodeDto(number = 1)))).isEmpty())
    }

    @Test fun `history bounds the final deduped observation set`() {
        val rows = (1..300).map { number -> movie("tt$number", Instant.ofEpochSecond(number.toLong()).toString()) }
        val result = recentTraktWatchedHistory(rows)
        assertEquals(200, result.size)
        assertEquals("tt300", result.first().contentId)
        assertEquals("tt101", result.last().contentId)
    }

    @Test fun `supplied subgenres retain anime admission and mature exclusions for category consumers`() {
        val movie = movie("tt100", "2026-01-01T00:00:00Z").copy(movie = TraktMovieDto(
            title = "Movie", ids = TraktIdsDto(imdb = "tt100"), genres = listOf("animation"),
            subgenres = listOf("adult", "animation"), certification = "G"))
        val show = episode(1, "2026-01-02T00:00:00Z").copy(show = TraktShowDto(
            title = "Show", ids = TraktIdsDto(imdb = "tt200"), genres = listOf("animation"), subgenres = listOf("anime")))
        val result = recentTraktWatchedHistory(listOf(movie, show))
        assertEquals(listOf("animation", "anime"), result.first().genres)
        assertEquals(listOf("animation", "adult"), result.last().genres)
    }

    private fun movie(id: String, at: String) = TraktHistoryItemDto(watchedAt = at,
        movie = TraktMovieDto(title = "Movie", ids = TraktIdsDto(imdb = id)))
    private fun episode(number: Int, at: String) = TraktHistoryItemDto(watchedAt = at,
        show = TraktShowDto(title = "Show", ids = TraktIdsDto(imdb = "tt200")),
        episode = TraktEpisodeDto(title = "Episode $number", season = 1, number = number))
}
