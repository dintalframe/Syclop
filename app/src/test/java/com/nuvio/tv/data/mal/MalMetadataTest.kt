package com.nuvio.tv.data.mal

import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.*
import org.junit.Test

class MalMetadataTest {
    @Test fun `only exact MAL identifiers are accepted`() {
        assertEquals(123L, MalIdentity.id("mal:123"))
        assertEquals(123L, MalIdentity.id("mal:123:12"))
        assertEquals(12, MalIdentity.episode("mal:123:12"))
        listOf("tt123", "mal:0", "mal:-2", "mal:123:0", "mal:123:1:2", "mal:123extra", "mal:9999999999999999999999")
            .forEach { assertNull(it, MalIdentity.id(it)) }
    }

    @Test fun `year comes only from official date precision`() {
        assertEquals(2024, MalIdentity.year("2024-10-01"))
        assertEquals(1998, MalIdentity.year("1998-04"))
        assertEquals(1989, MalIdentity.year("1989"))
        assertNull(MalIdentity.year(null))
        assertNull(MalIdentity.year("Released in 2024"))
        assertNull(MalIdentity.year("0"))
    }

    @Test fun `entry episodes retain MAL identity and local season numbering`() {
        val anime = MalAnime(456, "A series Second Season", totalEpisodes = 12,
            mediaType = "tv", startDate = "2023-04-01")
        val meta = MalMetadataService.toMeta(anime)
        assertEquals("mal:456", meta.id)
        assertEquals(ContentType.SERIES, meta.type)
        assertEquals(12, meta.videos.size)
        assertEquals("mal:456:12", meta.videos.last().id)
        assertTrue(meta.videos.all { it.season == 1 && it.released == null && it.available == null })
        assertNull(meta.imdbId)
        assertNull(meta.imdbRating)
        assertEquals("2023", meta.releaseInfo)
        assertEquals("mal:456:1", meta.behaviorHints?.defaultVideoId)
    }

    @Test fun `movies and unknown episode counts do not invent an episode list`() {
        val movie = MalMetadataService.toMeta(MalAnime(42, "Anime movie", mediaType = "movie", totalEpisodes = 1))
        assertEquals(ContentType.MOVIE, movie.type)
        assertTrue(movie.videos.isEmpty())
        assertEquals("mal:42", movie.behaviorHints?.defaultVideoId)
        val unknown = MalMetadataService.toMeta(MalAnime(43, "Announced series", mediaType = "tv"))
        assertTrue(unknown.videos.isEmpty())
        assertNull(unknown.behaviorHints?.defaultVideoId)
        assertTrue(unknown.description.orEmpty().contains("not yet available"))
    }

    @Test fun `catalog previews retain source and never fabricate cross IDs`() {
        val preview = MalMetadataService.preview(MalAnime(1, "Known entry", startDate = "2000"))
        assertEquals("mal:1", preview.id)
        assertEquals(MalCatalog.BASE_URL, preview.sourceAddonBaseUrl)
        assertNull(preview.imdbId)
        assertEquals("https://myanimelist.net/anime/1", preview.links.single().url)
    }
}
