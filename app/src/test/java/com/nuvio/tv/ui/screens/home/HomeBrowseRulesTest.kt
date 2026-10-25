package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mal.MalCatalog
import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class HomeBrowseRulesTest {
    @Test fun `Movies TV and Anime are disjoint item-level destinations`() {
        val movie = item("tt1", ContentType.MOVIE)
        val tv = item("tt2", ContentType.SERIES)
        val animeMovie = item("mal:5", ContentType.MOVIE)
        val animeSeries = item("mal:6", ContentType.SERIES)
        val rows = row(listOf(movie, tv, animeMovie, animeSeries))
        assertEquals(listOf(movie), HomeBrowseRules.scoped(HomeBrowseSection.MOVIES, rows).items)
        assertEquals(listOf(tv), HomeBrowseRules.scoped(HomeBrowseSection.TV, rows).items)
        assertEquals(listOf(animeMovie, animeSeries), HomeBrowseRules.scoped(HomeBrowseSection.ANIME, rows).items)
    }
    @Test fun `Animation Japanese language country and title words do not imply Anime`() {
        val item = item("tt3", ContentType.MOVIE).copy(name = "Anime Story", genres = listOf("Animation"), language = "ja", country = "Japan")
        assertFalse(HomeBrowseRules.isAnime(item))
        assertTrue(HomeBrowseRules.matches(HomeBrowseSection.MOVIES, item))
    }
    @Test fun `only explicit anime identifiers types or genre establish Anime`() {
        for (id in listOf("mal:3", "kitsu:4", "anilist:5", "anidb:6", "mal:7:2")) assertTrue(HomeBrowseRules.isAnime(item(id, ContentType.SERIES)))
        assertFalse(HomeBrowseRules.isAnime(item("mal:fake", ContentType.SERIES)))
        assertTrue(HomeBrowseRules.isAnime(item("custom", ContentType.UNKNOWN).copy(rawType = "Anime")))
        assertTrue(HomeBrowseRules.isAnime(item("tt4", ContentType.SERIES).copy(genres = listOf("Anime"))))
    }
    @Test fun `native MAL mixed-format row is entirely Anime and never leaks to TV`() {
        val mixed = row(listOf(item("mal:1", ContentType.MOVIE), item("mal:2", ContentType.SERIES)))
            .copy(addonId = MalCatalog.ADDON_ID, addonBaseUrl = MalCatalog.BASE_URL)
        assertEquals(2, HomeBrowseRules.scoped(HomeBrowseSection.ANIME, mixed).items.size)
        assertTrue(HomeBrowseRules.scoped(HomeBrowseSection.MOVIES, mixed).items.isEmpty())
        assertTrue(HomeBrowseRules.scoped(HomeBrowseSection.TV, mixed).items.isEmpty())
    }
    @Test fun `scoping preserves source catalog raw type cursor and exact IDs`() {
        val original = row(listOf(item("tt1", ContentType.MOVIE), item("tt2", ContentType.SERIES)))
            .copy(rawType = "CustomSeries", currentPage = 3, nextSkip = 400, hasMore = true)
        val scoped = HomeBrowseRules.scoped(HomeBrowseSection.MOVIES, original)
        assertEquals(original.copy(items = listOf(original.items.first())), scoped)
    }
    @Test fun `request keys distinguish same catalog ID across type URL and query case without exposing URL`() {
        val descriptor = CatalogDescriptor(ContentType.MOVIE, id = "top", name = "Top")
        val first = HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon(), descriptor)!!
        val other = HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon().copy(baseUrl = "https://catalog.test/Private?config=X"), descriptor)!!
        assertNotEquals(first.key, other.key)
        assertTrue(first.key.matches(Regex("[a-f0-9]{64}")))
        assertFalse(first.key.contains("catalog"))
    }
    @Test fun `native MAL catalogs can populate Anime even when not shown on mixed Home`() {
        val catalog = CatalogDescriptor(ContentType.SERIES, id = "ranking", name = "MAL", showInHome = false, hasExplicitShowInHome = true)
        val native = addon().copy(id = MalCatalog.ADDON_ID, baseUrl = MalCatalog.BASE_URL)
        assertNotNull(HomeBrowseRules.request(HomeBrowseSection.ANIME, native, catalog))
        assertNull(HomeBrowseRules.request(HomeBrowseSection.TV, native, catalog))
        assertNull(HomeBrowseRules.request(HomeBrowseSection.MOVIES, native, catalog.copy(type = ContentType.MOVIE)))
        assertFalse(catalog.showInHome)
    }
    @Test fun `disabled addons and full or short required-search catalogs are not browsed`() {
        val catalog = CatalogDescriptor(ContentType.MOVIE, id = "search", name = "Search")
        assertNull(HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon().copy(enabled = false), catalog))
        assertNull(HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon(), catalog.copy(extraRequired = listOf("search"))))
        assertNull(HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon(), catalog.copy(extra = listOf(CatalogExtra("search", isRequired = true)))))
    }
    @Test fun `required preference without explicit default is not silently chosen`() {
        val required = CatalogDescriptor(ContentType.MOVIE, id = "genre", name = "Genre", extra = listOf(CatalogExtra("genre", isRequired = true, options = listOf("Action", "Drama"))))
        assertNull(HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon(), required))
        val supplied = required.copy(extra = listOf(required.extra.single().copy(defaultValue = "Drama")))
        assertEquals(mapOf("genre" to "Drama"), HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon(), supplied)!!.extraArgs)
    }
    @Test fun `explicit Anime genre query is allowed but Animation is not guessed`() {
        val catalog = CatalogDescriptor(ContentType.SERIES, id = "genre", name = "Genre", extra = listOf(CatalogExtra("genre", options = listOf("Animation", "Anime"))))
        val request = HomeBrowseRules.request(HomeBrowseSection.ANIME, addon(), catalog)!!
        assertEquals("Anime", request.extraArgs["genre"])
        val returned = row(listOf(item("tt9", ContentType.SERIES))).copy(extraArgs = request.extraArgs)
        assertEquals(returned.items, HomeBrowseRules.scoped(HomeBrowseSection.ANIME, returned).items)
        assertNull(HomeBrowseRules.request(HomeBrowseSection.ANIME, addon(), catalog.copy(extra = listOf(CatalogExtra("genre", options = listOf("Animation"))))))
    }
    @Test fun `mixed provider IMDb and anime prefixes do not hide its normal movie catalogs`() {
        val addon = addon().copy(idPrefixes = listOf("tt", "mal:"))
        val catalog = CatalogDescriptor(ContentType.MOVIE, id = "all", name = "All")
        assertNotNull(HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon, catalog))
        assertNotNull(HomeBrowseRules.request(HomeBrowseSection.ANIME, addon, catalog))
    }
    @Test fun `unknown custom types and channels do not silently become TV or Movies`() {
        for (type in listOf(ContentType.CHANNEL, ContentType.UNKNOWN)) {
            assertFalse(HomeBrowseRules.matches(HomeBrowseSection.MOVIES, item("custom", type)))
            assertFalse(HomeBrowseRules.matches(HomeBrowseSection.TV, item("custom", type)))
        }
    }
    @Test fun `reserved skip default never overrides the requested page cursor`() {
        val descriptor = CatalogDescriptor(ContentType.MOVIE, id = "top", name = "Top",
            extra = listOf(CatalogExtra("skip", defaultValue = "0")))
        assertFalse(HomeBrowseRules.request(HomeBrowseSection.MOVIES, addon(), descriptor)!!.extraArgs.containsKey("skip"))
    }
    @Test fun `profile URL barrier preserves path query case and terminal query slash`() {
        val exact = addon().copy(baseUrl = "https://catalog.test/Case?config=AbC/")
        assertTrue(HomeBrowseRules.allowed(exact, listOf("https://catalog.test/Case/manifest.json?config=AbC/")))
        assertFalse(HomeBrowseRules.allowed(exact, listOf("https://catalog.test/case?config=abc/")))
        assertFalse(HomeBrowseRules.allowed(exact, listOf("https://catalog.test/Case?config=AbC")))
        assertFalse(HomeBrowseRules.allowed(exact.copy(enabled = false), listOf(exact.baseUrl)))
    }
    @Test fun `Documentary requires explicit genres and preserves movie and TV browse membership`() {
        val doc = item("ttdoc", ContentType.MOVIE).copy(genres = listOf("Documentary"))
        val guessed = item("ttguess", ContentType.SERIES).copy(name = "The Documentary", description = "A documentary")
        assertTrue(HomeBrowseRules.matches(HomeBrowseSection.DOCUMENTARY, doc))
        assertTrue(HomeBrowseRules.matches(HomeBrowseSection.MOVIES, doc))
        assertFalse(HomeBrowseRules.matches(HomeBrowseSection.DOCUMENTARY, guessed))
        assertTrue(HomeBrowseRules.matches(HomeBrowseSection.DOCUMENTARY, guessed, row(listOf(guessed)).copy(extraArgs = mapOf("genre" to "Docuseries"))))
    }
    @Test fun `Documentary requests explicit offered genre without changing source identity`() {
        val descriptor = CatalogDescriptor(ContentType.SERIES, id = "genre", name = "Genre",
            extra = listOf(CatalogExtra("genre", isRequired = true, options = listOf("Drama", "Documentary"), defaultValue = "Drama")))
        val request = HomeBrowseRules.request(HomeBrowseSection.DOCUMENTARY, addon(), descriptor)!!
        assertEquals(mapOf("genre" to "Documentary"), request.extraArgs)
        assertEquals(descriptor, request.catalog)
        assertNull(HomeBrowseRules.request(HomeBrowseSection.DOCUMENTARY, addon(), descriptor.copy(type = ContentType.CHANNEL)))
    }
    @Test fun `Kids through seven accepts narrow explicit ratings and rejects advisory missing and older ratings`() {
        for (rating in listOf("G", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G")) {
            assertTrue(rating, HomeBrowseRules.matches(HomeBrowseSection.KIDS, item("safe", ContentType.MOVIE).copy(ageRating = rating)))
        }
        for (rating in listOf(null, "", "Unknown", "PG", "TV-PG", "PG-13", "TV-14", "R", "18", "8+", "C8", "7/10", "G / PG", "FSK 12", "U", "AL", "TP", "L", "FSK 0", "FSK 6", "0", "6+", "7")) {
            assertFalse(rating ?: "missing", HomeBrowseRules.matches(HomeBrowseSection.KIDS,
                item("unsafe", ContentType.MOVIE).copy(ageRating = rating, genres = listOf("Animation", "Family", "Kids"))))
        }
    }
    @Test fun `Kids educational grouping uses explicit genres with no title or Animation guess`() {
        val safe = item("safe", ContentType.SERIES).copy(ageRating = "TV-G")
        assertEquals(KidsBrowseGroup.SCIENCE, HomeBrowseRules.kidsGroup(safe.copy(genres = listOf("Science", "Documentary"))))
        assertEquals(KidsBrowseGroup.NATURE, HomeBrowseRules.kidsGroup(safe.copy(genres = listOf("Nature", "Documentary"))))
        assertEquals(KidsBrowseGroup.EDUCATION, HomeBrowseRules.kidsGroup(safe.copy(genres = listOf("Educational"))))
        assertEquals(KidsBrowseGroup.DOCUMENTARY, HomeBrowseRules.kidsGroup(safe.copy(genres = listOf("Documentary"))))
        assertEquals(KidsBrowseGroup.SHOWS, HomeBrowseRules.kidsGroup(safe.copy(name = "Science Lessons", genres = listOf("Science Fiction", "Animation"))))
        assertEquals(KidsBrowseGroup.SHOWS, HomeBrowseRules.kidsGroup(safe.copy(name = "Bluey-like fixture", genres = listOf("Children", "Family", "Animals"))))
        assertEquals(KidsBrowseGroup.SHOWS, HomeBrowseRules.kidsGroup(safe.copy(genres = listOf("Science", "Technology"))))
        assertEquals(KidsBrowseGroup.NATURE, HomeBrowseRules.kidsGroup(safe.copy(genres = listOf("Animals", "Documentary"))))
        assertEquals(KidsBrowseGroup.MOVIES, HomeBrowseRules.kidsGroup(safe.copy(type = ContentType.MOVIE)))
        assertNull(HomeBrowseRules.kidsGroup(safe.copy(ageRating = null, genres = listOf("Educational"))))
        assertNull(HomeBrowseRules.kidsGroup(safe.copy(genres = listOf("Educational", "Adult"))))
        assertNull(HomeBrowseRules.kidsGroup(safe.copy(type = ContentType.CHANNEL, genres = listOf("Educational"))))
        assertNull(HomeBrowseRules.kidsGroup(safe.copy(type = ContentType.UNKNOWN, rawType = "video", genres = listOf("Educational"))))
    }
    @Test fun `native Simkl mixed prefixes never make Anime request its non Anime descriptors`() {
        val simkl = addon().copy(id = com.nuvio.tv.data.simkl.catalog.SimklPublicCatalog.ADDON_ID,
            baseUrl = com.nuvio.tv.data.simkl.catalog.SimklPublicCatalog.BASE_URL, idPrefixes = listOf("mal:", "tt"))
        val descriptor = CatalogDescriptor(ContentType.SERIES, id = "tv-week", name = "Simkl Trending TV")
        assertNull(HomeBrowseRules.request(HomeBrowseSection.ANIME, simkl, descriptor))
        assertNotNull(HomeBrowseRules.request(HomeBrowseSection.ANIME, simkl, descriptor.copy(rawType = "anime", id = "anime-week")))
    }
    private fun item(id: String, type: ContentType) = MetaPreview(id = id, type = type, name = "Fixture", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = emptyList())
    private fun row(items: List<MetaPreview>) = CatalogRow("fixture", "Fixture", "https://catalog.test", "top", "Top", ContentType.SERIES, items = items)
    private fun addon() = Addon("fixture", "Fixture", version = "1", description = null, logo = null,
        baseUrl = "https://catalog.test/private?config=x", catalogs = emptyList(), types = listOf(ContentType.MOVIE), resources = emptyList())
}
