package com.nuvio.tv.core.trakt

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TraktActionIdentityTest {
    @Test fun `movie with exact IMDb identity can be rated and checked in`() {
        val target = TraktActionIdentity.title(meta("tt1254207", ContentType.MOVIE))!!
        assertTrue(target.canCheckin); assertEquals("tt1254207", target.ids.imdb)
    }
    @Test fun `title alone and MAL ID never become guessed Trakt identities`() {
        assertNull(TraktActionIdentity.title(meta("mal:52991", ContentType.SERIES)))
        assertNull(TraktActionIdentity.title(meta("Frieren", ContentType.SERIES)))
    }
    @Test fun `Cinemeta season episode string is not a verified episode ID`() {
        val show = meta("tt22248376", ContentType.SERIES)
        assertNull(TraktActionIdentity.episode(show, video("tt22248376:1:29")))
    }
    @Test fun `show IMDb ID repeated as episode ID is rejected`() {
        val show = meta("tt22248376", ContentType.SERIES)
        assertNull(TraktActionIdentity.episode(show, video("tt22248376")))
    }
    @Test fun `explicit episode typed ID is preserved without numbering inference`() {
        val target = TraktActionIdentity.episode(meta("tt22248376", ContentType.SERIES), video("trakt:episode:123"))!!
        assertEquals(123, target.ids.trakt); assertEquals(TraktActionKind.EPISODE, target.kind)
        assertNull(TraktActionIdentity.episode(meta("tt22248376", ContentType.SERIES), video("trakt:123")))
    }
    @Test fun `exact show rating does not allow show checkin`() {
        assertFalse(TraktActionIdentity.title(meta("trakt:show:123", ContentType.SERIES))!!.canCheckin)
    }
    private fun meta(id: String, type: ContentType) = Meta(id, type, name = "Fixture", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = emptyList(), runtime = null, director = emptyList(), cast = emptyList(),
        videos = emptyList(), country = null, awards = null, language = null, links = emptyList())
    private fun video(id: String) = Video(id, "Episode", null, null, season = 1, episode = 29, overview = null)
}
