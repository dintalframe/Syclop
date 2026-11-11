package com.nuvio.tv.data.mal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MalVerifiedEpisodesTest {
    @Test fun `missing catalog count can display only the independently verified episode IDs`() {
        val title = MalMetadataService.toMeta(MalAnime(63367, "An airing title", mediaType = "tv"))
        assertTrue(title.needsVerifiedMalEpisodes())
        val details = title.withVerifiedMalEpisodes(listOf(3, 1, 3, 0, -1, 5000))

        assertEquals(listOf("mal:63367:1", "mal:63367:3"), details.videos.map { it.id })
        assertEquals(listOf(1, 3), details.videos.map { it.episode })
        assertTrue(details.videos.all { it.season == 1 })
        assertEquals("mal:63367:1", details.behaviorHints?.defaultVideoId)
        assertEquals(title.id, details.id)
        assertFalse(details.description.orEmpty().contains("Episode count is not yet available"))
    }

    @Test fun `movies established episode lists and missing verified rows retain their original metadata`() {
        val movie = MalMetadataService.toMeta(MalAnime(10, "A movie", mediaType = "movie"))
        val full = MalMetadataService.toMeta(MalAnime(11, "A series", mediaType = "tv", totalEpisodes = 2))
        val unknown = MalMetadataService.toMeta(MalAnime(12, "An airing series", mediaType = "tv"))

        assertFalse(movie.needsVerifiedMalEpisodes())
        assertFalse(full.needsVerifiedMalEpisodes())
        assertSame(movie, movie.withVerifiedMalEpisodes(listOf(1)))
        assertSame(full, full.withVerifiedMalEpisodes(listOf(1, 3)))
        assertSame(unknown, unknown.withVerifiedMalEpisodes(emptyList()))
        assertFalse(unknown.copy(id = "tt1234").needsVerifiedMalEpisodes())
    }
}
