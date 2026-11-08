package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mal.MalCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedHistoryItem
import org.junit.Assert.*
import org.junit.Test

class HomeTrackingRowRulesTest {
    @Test fun `Home retains all original observations including repeated watches and exact playback payloads`() {
        val history = listOf(history(), history().copy(watchedAt = 2000), history("mal:7", "series"))
        val progress = listOf(progress(), nextUp())
        assertSame(history, HomeTrackingRowRules.history(HomeBrowseSection.HOME, history))
        assertSame(progress, HomeTrackingRowRules.continueWatching(HomeBrowseSection.HOME, progress))
    }

    @Test fun `Movies and TV use media types and exclude explicitly classified Anime`() {
        val movie = history()
        val show = history("tt100002", "tv")
        val animeMovie = history("mal:7", "movie")
        val animeShow = history("kitsu:8", "series")
        val all = listOf(movie, show, animeMovie, animeShow)
        assertEquals(listOf(movie), HomeTrackingRowRules.history(HomeBrowseSection.MOVIES, all))
        assertEquals(listOf(show), HomeTrackingRowRules.history(HomeBrowseSection.TV, all))
        assertEquals(listOf(animeMovie, animeShow), HomeTrackingRowRules.history(HomeBrowseSection.ANIME, all))
    }

    @Test fun `history category filtering preserves repeated occurrences and original episode identity`() {
        val first = history("tt100002", "series").copy(videoId = "tt100002:1:2", season = 1, episode = 2)
        val again = first.copy(watchedAt = 3000)
        val scoped = HomeTrackingRowRules.history(HomeBrowseSection.TV, listOf(first, history(), again))
        assertEquals(listOf(first, again), scoped)
        assertSame(first, scoped[0])
        assertSame(again, scoped[1])
        assertEquals("tt100002:1:2", scoped[0].videoId)
    }

    @Test fun `unknown custom types never borrow a known movie or child certificate`() {
        val unknown = history("tt100001", "video").copy(ageRating = "G", genres = listOf("Family"))
        val metadata = listOf(meta().copy(ageRating = "G", genres = listOf("Anime", "Documentary")))
        for (section in listOf(HomeBrowseSection.MOVIES, HomeBrowseSection.TV, HomeBrowseSection.ANIME, HomeBrowseSection.KIDS)) {
            assertTrue(section.name, HomeTrackingRowRules.history(section, listOf(unknown), metadata).isEmpty())
        }
    }

    @Test fun `Animation country language and title words do not imply Anime or Documentary`() {
        val item = history().copy(title = "Anime Documentary", genres = listOf("Animation"),
            country = "jp", languages = listOf("ja"))
        assertEquals(listOf(item), HomeTrackingRowRules.history(HomeBrowseSection.MOVIES, listOf(item)))
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.ANIME, listOf(item)).isEmpty())
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.DOCUMENTARY, listOf(item)).isEmpty())
        assertEquals(listOf(item.copy(genres = listOf("Documentary"))),
            HomeTrackingRowRules.history(HomeBrowseSection.DOCUMENTARY, listOf(item.copy(genres = listOf("Documentary")))))
    }

    @Test fun `verified IMDb siblings carry explicit native Anime provenance without rewriting IDs`() {
        val item = history("tt100002", "series")
        val native = meta("mal:42", ContentType.SERIES).copy(imdbId = "tt100002",
            sourceAddonBaseUrl = MalCatalog.BASE_URL)
        assertEquals(listOf(item), HomeTrackingRowRules.history(HomeBrowseSection.ANIME, listOf(item), listOf(native)))
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.TV, listOf(item), listOf(native)).isEmpty())
        assertEquals("tt100002", item.contentId)
    }

    @Test fun `explicit Anime origin on a history item is retained by category admission`() {
        val item = history().copy(sourceAddonBaseUrl = MalCatalog.BASE_URL)
        assertEquals(listOf(item), HomeTrackingRowRules.history(HomeBrowseSection.ANIME, listOf(item)))
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.MOVIES, listOf(item)).isEmpty())
    }

    @Test fun `known IMDb episode aliases and TV series types share category metadata only`() {
        val item = progress("imdb:tt100002:1:2", "tv")
        val metadata = listOf(meta("tt100002", ContentType.SERIES).copy(ageRating = "TV-Y7", genres = listOf("Documentary")))
        val scoped = HomeTrackingRowRules.continueWatching(HomeBrowseSection.KIDS, listOf(item), metadata)
        assertEquals(listOf(item), scoped)
        assertSame(item, scoped.single())
        assertEquals(listOf(item), HomeTrackingRowRules.continueWatching(HomeBrowseSection.DOCUMENTARY, listOf(item), metadata))
    }

    @Test fun `media type namespace and exact custom IDs prevent unrelated metadata admission`() {
        val show = progress("tmdb:123", "series")
        val otherNamespace = meta("trakt:123", ContentType.SERIES).copy(ageRating = "G", genres = listOf("Anime"))
        val otherType = meta("tmdb:123", ContentType.MOVIE).copy(ageRating = "G", genres = listOf("Anime"))
        val custom = progress("custom:one:2", "series")
        val customParent = meta("custom:one", ContentType.SERIES).copy(ageRating = "G", genres = listOf("Anime"))
        val metadata = listOf(otherNamespace, otherType, customParent)
        for (section in listOf(HomeBrowseSection.KIDS, HomeBrowseSection.ANIME)) {
            assertTrue(HomeTrackingRowRules.continueWatching(section, listOf(show, custom), metadata).isEmpty())
        }
    }

    @Test fun `Kids requires explicit supported certificate and rejects mature or conflicting metadata`() {
        val family = history().copy(genres = listOf("Family", "Children", "Animation"))
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.KIDS, listOf(family)).isEmpty())
        val rated = family.copy(ageRating = "G")
        assertEquals(listOf(rated), HomeTrackingRowRules.history(HomeBrowseSection.KIDS, listOf(rated)))
        for (rating in listOf("PG", "TV-14", "Unknown", "7", "U")) {
            assertTrue(rating, HomeTrackingRowRules.history(HomeBrowseSection.KIDS, listOf(family.copy(ageRating = rating))).isEmpty())
        }
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.KIDS,
            listOf(rated.copy(genres = listOf("Family", "Adult")))).isEmpty())
        val mature = meta().copy(ageRating = "G", genres = listOf("Mature"))
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.KIDS, listOf(rated), listOf(mature)).isEmpty())
        val conflicting = meta().copy(ageRating = "R")
        assertTrue(HomeTrackingRowRules.history(HomeBrowseSection.KIDS, listOf(rated), listOf(conflicting)).isEmpty())
    }

    @Test fun `Continue Watching cannot enter Kids without verified rating metadata`() {
        val inProgress = progress()
        val upcoming = nextUp()
        assertTrue(HomeTrackingRowRules.continueWatching(HomeBrowseSection.KIDS, listOf(inProgress, upcoming)).isEmpty())
        assertEquals(listOf(inProgress), HomeTrackingRowRules.continueWatching(HomeBrowseSection.KIDS,
            listOf(inProgress, upcoming), listOf(meta().copy(ageRating = "G"))))
    }

    @Test fun `Next Up explicit genres share Documentary and Anime filters without losing coordinates`() {
        val documentary = nextUp().copy(info = nextUp().info.copy(genres = listOf("Documentary")))
        val anime = nextUp().copy(info = nextUp().info.copy(contentId = "anilist:42"))
        assertEquals(listOf(documentary), HomeTrackingRowRules.continueWatching(HomeBrowseSection.DOCUMENTARY, listOf(documentary, anime)))
        assertEquals(listOf(anime), HomeTrackingRowRules.continueWatching(HomeBrowseSection.ANIME, listOf(documentary, anime)))
        assertEquals(2, anime.info.episode)
    }

    private fun history(id: String = "tt100001", type: String = "movie") = WatchedHistoryItem(
        contentId = id, contentType = type, title = "Fixture", watchedAt = 1000, videoId = id
    )

    private fun meta(id: String = "tt100001", type: ContentType = ContentType.MOVIE) = MetaPreview(
        id = id, type = type, name = "Fixture", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    private fun progress(id: String = "tt100001", type: String = "movie") = ContinueWatchingItem.InProgress(
        WatchProgress(id, type, "Fixture", null, null, null, "$id:1:2", 1, 2, "Episode", 10, 100, 1000)
    )

    private fun nextUp() = ContinueWatchingItem.NextUp(NextUpInfo(
        contentId = "tt100002", contentType = "series", name = "Fixture", poster = null,
        backdrop = null, logo = null, videoId = "tt100002:1:2", season = 1, episode = 2,
        episodeTitle = "Episode", thumbnail = null, lastWatched = 1000, sortTimestamp = 1000
    ))
}
