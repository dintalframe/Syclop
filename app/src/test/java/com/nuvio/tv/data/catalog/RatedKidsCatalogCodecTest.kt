package com.nuvio.tv.data.catalog

import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.domain.model.ContentType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class RatedKidsCatalogCodecTest {
    @Test fun `movie requires exact IMDb and explicit G while retaining source certification and public identity`() {
        val record = movie().copy(certification = "G", genres = listOf("family"))
        val preview = RatedKidsCatalogCodec.movie(record, descriptor("family-movies"))!!
        assertEquals("tt1234567", preview.id)
        assertEquals(ContentType.MOVIE, preview.type)
        assertEquals("G", preview.ageRating)
        assertEquals(RatedKidsCatalog.BASE_URL, preview.sourceAddonBaseUrl)
        assertEquals("https://trakt.tv/movies/123", preview.links.single().url)
        for (id in listOf(null, "", "trakt:123", "tt1234567:1:1", "https://private.test", "tt1"))
            assertNull(RatedKidsCatalogCodec.movie(record.copy(ids = TraktIdsDto(trakt = 123, imdb = id)), descriptor("family-movies")))
    }
    @Test fun `advisory missing older and untyped international ratings never enter Kids`() {
        for (rating in listOf(null, "", "PG", "PG-13", "R", "TV-PG", "TV-14", "U", "AL", "6", "7+", "FSK 6", "Unknown", "G/PG")) {
            assertNull(rating, RatedKidsCatalogCodec.movie(movie().copy(certification = rating), descriptor("family-movies")))
            assertNull(rating, RatedKidsCatalogCodec.show(show().copy(certification = rating), descriptor("family-shows")))
        }
        for (rating in listOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G")) {
            assertEquals(rating, RatedKidsCatalogCodec.show(show().copy(certification = rating), descriptor("family-shows"))!!.ageRating)
        }
    }
    @Test fun `local genre proof is required even if server ignored the request filter`() {
        assertNull(RatedKidsCatalogCodec.movie(movie().copy(genres = listOf("drama")), descriptor("family-movies")))
        assertNull(RatedKidsCatalogCodec.show(show().copy(genres = listOf("family", "adult")), descriptor("family-shows")))
        assertNull(RatedKidsCatalogCodec.show(show().copy(genres = listOf("children"), subgenres = listOf("animals")), descriptor("nature-shows")))
        assertNull(RatedKidsCatalogCodec.show(show().copy(genres = listOf("family")), descriptor("documentary-shows")))
    }
    @Test fun `Science needs documentary or educational context and never science fiction or title words`() {
        val fiction = show().copy(title = "Science Lessons", genres = listOf("family", "science-fiction"), subgenres = listOf("technology"))
        assertNull(RatedKidsCatalogCodec.show(fiction, descriptor("science-shows")))
        val fact = fiction.copy(genres = listOf("documentary"), subgenres = listOf("engineering"))
        assertTrue(RatedKidsCatalogCodec.show(fact, descriptor("science-shows"))!!.genres.contains("Science"))
    }
    @Test fun `educational and nature grouping uses explicit batch metadata without fictional animal guesses`() {
        val education = show().copy(title = "Sesame Street fixture", genres = listOf("children"), subgenres = listOf("educational", "puppet"))
        assertTrue(RatedKidsCatalogCodec.show(education, descriptor("education-shows"))!!.genres.contains("Educational"))
        val nature = show().copy(genres = listOf("documentary"), subgenres = listOf("animals"))
        assertTrue(RatedKidsCatalogCodec.show(nature, descriptor("nature-shows"))!!.genres.contains("Nature"))
        val family = show().copy(title = "Bluey-like fixture", genres = listOf("children", "family"), subgenres = listOf("animals", "dog"))
        val grouped = RatedKidsCatalogCodec.show(family, descriptor("family-shows"))!!
        assertFalse(grouped.genres.contains("Nature"))
        assertFalse(grouped.genres.contains("Educational"))
    }
    @Test fun `official public artwork is accepted but private hosts credentials and queries are excluded`() {
        val images = TraktImagesDto(poster = listOf("media.trakt.tv/images/poster.webp"), fanart = listOf("https://media.trakt.tv/images/fanart.webp"))
        val value = RatedKidsCatalogCodec.movie(movie().copy(images = images), descriptor("family-movies"))!!
        assertEquals("https://media.trakt.tv/images/poster.webp", value.poster)
        for (url in listOf("https://private.test/a", "http://media.trakt.tv/a", "https://user:pass@media.trakt.tv/a", "https://media.trakt.tv/a?token=x", "https://media.trakt.tv/a#x"))
            assertNull(RatedKidsCatalogCodec.movie(movie().copy(images = TraktImagesDto(poster = listOf(url))), descriptor("family-movies"))!!.poster)
    }
    @Test fun `Moshi accepts public Trakt optional subgenres and old records remain compatible`() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(TraktShowDto::class.java)
        assertEquals(listOf("educational"), adapter.fromJson("""{"title":"Fixture","subgenres":["educational"]}""")!!.subgenres)
        assertNull(adapter.fromJson("""{"title":"Older fixture"}""")!!.subgenres)
    }
    @Test fun `missing or malformed display fields cannot introduce guessed media or unbounded rows`() {
        assertNull(RatedKidsCatalogCodec.movie(movie().copy(title = "x".repeat(301)), descriptor("family-movies")))
        assertNull(RatedKidsCatalogCodec.movie(movie().copy(title = "bad\u0000title"), descriptor("family-movies")))
        assertNull(RatedKidsCatalogCodec.movie(movie().copy(genres = List(41) { "family" }), descriptor("family-movies")))
        val value = RatedKidsCatalogCodec.movie(movie().copy(runtime = -1, year = -1, overview = "x".repeat(8_001)), descriptor("family-movies"))!!
        assertNull(value.runtime); assertNull(value.releaseInfo); assertNull(value.description)
        assertNull(value.imdbRating) // A Trakt score is not relabelled as an IMDb rating.
    }
    companion object {
        fun descriptor(id: String) = RatedKidsCatalog.DESCRIPTORS.single { it.id == id }
        fun movie() = TraktMovieDto(title = "Family fixture", ids = TraktIdsDto(trakt = 123, imdb = "tt1234567"), certification = "G", genres = listOf("family"))
        fun show() = TraktShowDto(title = "Family fixture", ids = TraktIdsDto(trakt = 124, imdb = "tt2345678"), certification = "TV-Y", genres = listOf("children", "family"))
    }
}
