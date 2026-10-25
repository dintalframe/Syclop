package com.nuvio.tv.data.simkl.catalog

import com.nuvio.tv.domain.model.ContentType
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SimklPublicCatalogCodecTest {
    @Test fun `anime keeps exact MAL ID relative bookkeeping and explicit Anime provenance`() {
        val row = SimklPublicCatalogCodec.items("[${item(mal = "59978")}]", SimklFeedType.ANIME).single()
        assertEquals("mal:59978", row.preview.id)
        assertEquals(ContentType.SERIES, row.preview.type)
        assertTrue(row.preview.genres.contains("Anime"))
        assertEquals("https://simkl.com/anime/12345/frieren", row.pageUrl)
        assertEquals(SimklPublicCatalog.BASE_URL, row.preview.sourceAddonBaseUrl)
        assertNull(row.preview.ageRating)
        assertEquals(row.pageUrl, row.preview.links.single().url)
        assertFalse(row.preview.id.contains(":1")) // Feed data never invents season/episode IDs.
    }

    @Test fun `unmapped anime never falls back to an IMDb shared parent`() {
        for (mal in listOf("", "0", "-1", "59978:1", "1.5", "123456789012345"))
            assertTrue(SimklPublicCatalogCodec.items("[${item(mal = mal)}]", SimklFeedType.ANIME).isEmpty())
        assertEquals("tt1234567", SimklPublicCatalogCodec.items("[${item(mal = "")}]", SimklFeedType.TV).single().preview.id)
    }

    @Test fun `anime movie uses movie metadata but no guessed IMDb episode ordering`() {
        val movie = item().replace("\"anime_type\":\"tv\"", "\"anime_type\":\"movie\"")
        assertEquals(ContentType.MOVIE, SimklPublicCatalogCodec.items("[$movie]", SimklFeedType.ANIME).single().preview.type)
    }

    @Test fun `bad row identity cannot drop a valid neighbor and duplicate records are bounded`() {
        val bad = item().replace("\"simkl_id\":12345", "\"simkl_id\":12345,\"simkl\":999")
        val rows = SimklPublicCatalogCodec.items("[$bad,${item()},${item()}]", SimklFeedType.ANIME)
        assertEquals(1, rows.size)
        assertEquals("mal:59978", rows.single().preview.id)
    }

    @Test fun `unknown or family genre never invents an age certification`() {
        val family = item().replace("\"Drama\"", "\"Family\"")
            .replace("\"overview\"", "\"certification\":\"PG\",\"overview\"")
        assertNull(SimklPublicCatalogCodec.items("[$family]", SimklFeedType.MOVIES).single().preview.ageRating)
    }

    @Test fun `adult videogame and music video records do not enter catalogs`() {
        for (genre in listOf("Hentai", "Erotica", "Pornography", "Adult", "Video Game Play")) {
            assertTrue(SimklPublicCatalogCodec.items("[${item().replace("Drama", genre)}]", SimklFeedType.ANIME).isEmpty())
        }
        val music = item().replace("\"anime_type\":\"tv\"", "\"anime_type\":\"music video\"")
        assertTrue(SimklPublicCatalogCodec.items("[$music]", SimklFeedType.ANIME).isEmpty())
    }

    @Test fun `rich landscape artwork only builds documented fixed-host image URLs`() {
        val row = SimklPublicCatalogCodec.items("[${item()}]", SimklFeedType.TV).single().preview
        assertTrue(row.landscapePoster!!.contains("wsrv.nl"))
        assertTrue(row.landscapePoster!!.contains("_w.webp"))
        for (path in listOf("../secret", "https://private.example/a", "1/a?token=secret", "1/a#fragment", "1/../../a"))
            assertNull(SimklPublicCatalogCodec.image(path, "posters", "_w"))
    }

    @Test fun `genre paths require discovered index slug and skip adult categories`() {
        val rows = SimklPublicCatalogCodec.genres("""[{"name":"Documentary","slug":"documentary"},{"name":"Erotica","slug":"erotica"},{"name":"Family","slug":"../family"},{"name":"Nature","slug":"nature"}]""")
        assertEquals(listOf("documentary", "nature"), rows.map { it.slug })
    }

    @Test fun `documented public publisher repeated hyphens retain exact per-item link spelling`() {
        val payload = item().replace("\"slug\":\"frieren\"", "\"slug\":\"bleach-sennen-kessen-hen---kashin-tan\"")
        val row = SimklPublicCatalogCodec.items("[$payload]", SimklFeedType.ANIME).single()
        assertEquals("https://simkl.com/anime/12345/bleach-sennen-kessen-hen---kashin-tan", row.pageUrl)
        assertTrue(SimklPublicCatalog.isItemPageUrl(row.pageUrl))
    }

    @Test fun `per item browser links reject arbitrary hosts paths queries and credentials`() {
        assertTrue(SimklPublicCatalog.isItemPageUrl("https://simkl.com/anime/12345/frieren"))
        for (url in listOf("http://simkl.com/anime/1/a", "https://simkl.com.evil/anime/1/a", "https://user@simkl.com/anime/1/a", "https://simkl.com/anime/0/a", "https://simkl.com/anime/1/a?q=x", "https://simkl.com/anime/1/a#x", "https://simkl.com:443/anime/1/a"))
            assertFalse(SimklPublicCatalog.isItemPageUrl(url))
    }

    @Test fun `response item bounds and envelope shapes fail closed`() {
        for (body in listOf("{}", "{", "[" + List(501) { "{}" }.joinToString(",") + "]"))
            assertTrue(runCatching { SimklPublicCatalogCodec.items(body, SimklFeedType.TV) }.isFailure)
    }

    @Test fun `HTML synopsis is inert plain text with decoded entities and readable breaks`() {
        val overview = "<p>A &amp; B</p><br><br>Next &quot;chapter&quot;." +
            "<script>hiddenScript()</script><style>.hidden{color:red}</style><template>hiddenTemplate</template>" +
            "<a href='https://untrusted.invalid/path'> Read more</a><img src='https://untrusted.invalid/image'>"
        val row = SimklPublicCatalogCodec.items("[${withOverview(overview)}]", SimklFeedType.ANIME).single()
        assertEquals("""A & B Next "chapter". Read more""", row.preview.description)
        assertEquals("mal:59978", row.preview.id)
        assertEquals("Frieren: Beyond Journey's End Season 2", row.preview.name)
    }

    @Test fun `empty or oversized synopsis is omitted without losing valid catalog identity`() {
        for (overview in listOf("<br><p></p><script>hidden()</script>", "a".repeat(8_001))) {
            val row = SimklPublicCatalogCodec.items("[${withOverview(overview)}]", SimklFeedType.ANIME).single()
            assertNull(row.preview.description)
            assertEquals("mal:59978", row.preview.id)
        }
    }

    private fun withOverview(overview: String) = item().replace(
        "\"overview\":\"Public synopsis.\"", "\"overview\":${JsonPrimitive(overview)}")

    internal companion object {
        fun item(mal: String = "59978") = """{"title":"Frieren: Beyond Journey's End Season 2","ids":{"simkl_id":12345,"slug":"frieren","mal":"$mal","imdb":"tt1234567"},"anime_type":"tv","genres":["Drama"],"poster":"19/abcdef","fanart":"20/abc123","overview":"Public synopsis.","release_date":"01/16/2026","ratings":{"imdb":{"rating":8.7}}}"""
    }
}
