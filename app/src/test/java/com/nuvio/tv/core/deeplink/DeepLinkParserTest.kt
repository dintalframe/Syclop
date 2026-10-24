package com.nuvio.tv.core.deeplink

import com.nuvio.tv.domain.deeplink.AppDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParserTest {
    @Test
    fun parsesMetaQueryDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("syclop://meta?type=series&id=tt0944947")
        )
    }

    @Test
    fun parsesPartyInviteDeepLink() {
        assertEquals(AppDeepLink.Party("ABC234"), DeepLinkParser.parse("syclop://party/abc234"))
        assertEquals(AppDeepLink.Party("ABC234"), DeepLinkParser.parse("syclop://party?code=ABC-234"))
        assertNull(DeepLinkParser.parse("syclop://party/ABC10"))
    }

    @Test
    fun parsesAddonInstallDeepLink() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            DeepLinkParser.parse("syclop://free.nebulapro.xyz/sports/i/free/manifest.json")
        )
    }

    @Test
    fun parsesStremioAddonInstallDeepLink() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            DeepLinkParser.parse("stremio://free.nebulapro.xyz/sports/i/free/manifest.json")
        )
    }

    @Test
    fun parsesDirectImdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("syclop://series/tt0944947")
        )
    }

    @Test
    fun parsesProviderImdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("syclop://imdb/series/tt0944947")
        )
    }

    @Test
    fun parsesProviderTmdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tmdb:1399"),
            DeepLinkParser.parse("syclop://tmdb/tv/1399")
        )
    }

    @Test
    fun doesNotTreatAuthLinkAsAddonInstall() {
        assertNull(DeepLinkParser.parse("syclop://auth/trakt?code=abc"))
    }

    @Test
    fun doesNotTreatNonHostStremioLinkAsAddonInstall() {
        assertNull(DeepLinkParser.parse("stremio://detail/series/tt0944947"))
    }

    @Test
    fun parsesExactOpenMovieFixture() {
        assertEquals(
            AppDeepLink.Meta(type = "movie", id = "tt1254207"),
            DeepLinkParser.parse("syclop://movie/tt1254207")
        )
    }

    @Test
    fun parsesAllOwnDetailRouteAliases() {
        listOf("detail", "details", "open", "watch").forEach { route ->
            assertEquals(
                AppDeepLink.Meta(type = "movie", id = "tt1254207"),
                DeepLinkParser.parse("syclop://$route/movie/tt1254207")
            )
        }
    }

    @Test
    fun decodesExplicitLegacyLinksWithoutGeneratingThem() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("nuvio://series/tt0944947")
        )
        assertEquals(
            AppDeepLink.AddonInstall("https://example.org/addon/manifest.json"),
            DeepLinkParser.parse("nuvio://example.org/addon/manifest.json")
        )
    }

    @Test
    fun doesNotTreatUnrelatedSchemeAsAnAppRoute() {
        assertNull(DeepLinkParser.parse("https://movie/tt1254207"))
        assertNull(DeepLinkParser.parse("otherapp://movie/tt1254207"))
    }
}
