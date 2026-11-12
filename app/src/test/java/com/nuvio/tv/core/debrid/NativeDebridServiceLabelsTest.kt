package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class NativeDebridServiceLabelsTest {
    private val scope = StreamAvailabilityScope(1, 2L, 3L)
    private val settings = DebridSettings(realDebridApiKey = "fixture-rd", torboxApiKey = "fixture-tb",
        allDebridApiKey = "fixture-ad", premiumizeApiKey = "fixture-pm", preferredResolverProviderId = "realdebrid")
    private fun source() = Stream(null, "Movie.1080p.HEVC.HDR.Atmos.mkv", null, null, null,
        "a".repeat(40), 0, null, null, "Magnify Scrapers", null)
    private fun contract(provider: String) = StreamClientResolve("debrid", "a".repeat(40), 0,
        null, null, "Movie.mkv", "Movie.mkv", "movie", "tt1", "tt1", "Movie", null, null,
        provider, 0, "FAKE", true)
    private fun status(provider: String) = StreamDebridCacheStatus(provider, "Untrusted display name",
        StreamDebridCacheState.CACHED, nativeScope = scope, infoHash = "a".repeat(40), checkedAtMs = 900, expiresAtMs = 1900)

    @Test fun `each connected issuer is admitted and named without rebinding to preferred RD`() {
        DebridProviders.all().forEach { provider ->
            val stream = source().copy(clientResolve = contract(provider.id))
            assertTrue(NativeDebridSourceCards.eligible(stream, settings) { false })
            val facts = NativeDebridServiceLabels.services(stream, settings, scope, 1000)
            assertEquals(listOf(provider), facts.providers)
            assertFalse(facts.verifiedCached) // Addon isCached and extension are not native verification.
            assertEquals("Resolve with: ${provider.displayName}",
                NativeDebridServiceLabels.decorate(stream, settings, scope, 1000).badges.first().name)
        }
    }
    @Test fun `neutral hash names automatic eligible pool without claiming preferred service or cached status`() {
        val facts = NativeDebridServiceLabels.services(source(), settings, scope, 1000)
        assertTrue(facts.automatic)
        assertFalse(facts.verifiedCached)
        assertEquals(setOf("realdebrid", "torbox", "alldebrid", "premiumize"), facts.providers.map { it.id }.toSet())
        val badges = NativeDebridServiceLabels.decorate(source(), settings, scope, 1000).badges
            .filter { it.tagStyle.startsWith("native-service:") }
        assertEquals("Automatic", badges.first().name)
        assertEquals(setOf("Try Real-Debrid", "Try Torbox", "Try AllDebrid", "Try Premiumize"),
            badges.drop(1).map { it.name }.toSet())
        assertEquals(5, badges.map { it.tagStyle }.distinct().size)
        assertFalse(badges.any { it.name.contains("Cached") })
        val single = NativeDebridServiceLabels.decorate(source(), DebridSettings(torboxApiKey = "fixture"), scope, 1000)
            .badges.filter { it.tagStyle.startsWith("native-service:") }
        assertEquals(listOf("Resolve with: Torbox"), single.map { it.name })
    }
    @Test fun `only fresh matching account hash and viewer observations claim available services`() {
        val stream = source().copy(nativeDebridAvailability = listOf(status("torbox"), status("premiumize")))
        val facts = NativeDebridServiceLabels.services(stream, settings, scope, 1000)
        assertEquals(setOf("torbox", "premiumize"), facts.providers.map { it.id }.toSet())
        assertTrue(facts.verifiedCached)
        assertFalse(facts.automatic)
        for (candidate in listOf(stream.copy(nativeDebridAvailability = listOf(status("torbox").copy(infoHash = "b".repeat(40)))),
            stream.copy(nativeDebridAvailability = listOf(status("torbox").copy(nativeScope = scope.copy(profileId = 2)))),
            stream.copy(nativeDebridAvailability = listOf(status("torbox").copy(expiresAtMs = 999))))) {
            assertTrue(NativeDebridServiceLabels.services(candidate, settings, scope, 1000).automatic)
        }
    }
    @Test fun `actual winner replaces automatic discovery label without changing source identity`() {
        val original = source()
        val resolved = original.copy(nativeResolvedProviderId = "alldebrid")
        val displayed = NativeDebridServiceLabels.decorate(resolved, settings, scope, 1000)
        assertEquals("Resolved: AllDebrid", displayed.badges.first().name)
        assertEquals(original.stableKey(0), displayed.stableKey(0))
        assertEquals(1, displayed.badges.count { it.tagStyle.startsWith("native-service:") })
        assertEquals(displayed, NativeDebridServiceLabels.decorate(displayed, settings, scope, 1000))
    }
    @Test fun `unknown or disconnected issuer and winner are never claimed`() {
        for (provider in listOf("unknown-provider", "torbox")) {
            val configured = settings.copy(torboxApiKey = "")
            val stream = source().copy(clientResolve = contract(provider), nativeResolvedProviderId = provider)
            assertFalse(NativeDebridSourceCards.eligible(stream, configured) { false })
            assertTrue(NativeDebridServiceLabels.services(stream, configured, scope, 1000).providers.isEmpty())
            assertFalse(NativeDebridServiceLabels.decorate(stream, configured, scope, 1000).badges.any {
                it.tagStyle.startsWith("native-service:") })
        }
    }
    @Test fun `unverified addon cached flag and extension cannot become formatter verification`() {
        val stream = source().copy(clientResolve = contract("torbox"))
        val output = DebridStreamFormatter(DebridStreamTemplateEngine()).format(stream, settings,
            scope = scope, nowMs = 1000)
        assertTrue(output.description.orEmpty().contains("Not Ready (TB)"))
        assertFalse(output.name.orEmpty().contains("FAKE"))
    }
    @Test fun `default formatter reports unverified RD availability without a negative cache claim`() {
        val stream = source().copy(debridCacheStatus = status("realdebrid").copy(state = StreamDebridCacheState.UNKNOWN))
        val output = DebridStreamFormatter(DebridStreamTemplateEngine()).format(stream, settings,
            scope = scope, nowMs = 1000)
        assertTrue(output.description.orEmpty().contains("Availability unverified"))
        assertFalse(output.description.orEmpty().contains("Not Ready"))
        assertFalse(output.name.orEmpty().contains("Instant"))
    }

    @Test fun `Magnify display suppression keeps release text and underlying source intact`() {
        val stream = source().copy(description = "Magnify Scrapers\nMovie.1080p.mkv")
        assertFalse(NativeDebridSourceCards.visibleAddonName("Magnify Scrapers"))
        assertFalse(NativeDebridSourceCards.visibleAddonName("  MAGNIFY   SCRAPERS "))
        assertFalse(NativeDebridSourceCards.visibleAddonName("Magnify\tScrapers"))
        assertNull(NativeDebridSourceCards.displayText("Magnify Scrapers"))
        assertEquals("1080p", NativeDebridSourceCards.displayText("Magnify   Scrapers | 1080p"))
        assertEquals("1080p", NativeDebridSourceCards.displayText("Magnify Scrapers\n1080p"))
        assertTrue(NativeDebridSourceCards.visibleAddonName("Other Scrapers"))
        assertEquals("Movie.1080p.mkv", NativeDebridSourceCards.displayDescription(stream))
        assertEquals("Magnify Scrapers", stream.addonName)
        assertEquals("a".repeat(40), stream.getEffectiveInfoHash())
    }
}
