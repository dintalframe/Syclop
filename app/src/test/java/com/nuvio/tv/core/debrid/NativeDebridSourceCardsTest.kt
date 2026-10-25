package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class NativeDebridSourceCardsTest {
    private val settings = DebridSettings(enabled = true, allDebridApiKey = "fixture-key", preferredResolverProviderId = "alldebrid")

    @Test fun `Magnify hash remains eligible metadata while local P2P is disabled`() {
        val candidate = source().copy(description = "Seeds: 0", url = "magnet:?xt=urn:btih:${"a".repeat(40)}")
        assertTrue(NativeDebridSourceCards.eligible(candidate, settings))
        assertNull(NativeDebridSourceCards.displayText(candidate.description))
        assertEquals("a".repeat(40), candidate.getEffectiveInfoHash())
        assertEquals("Seeds: 0", candidate.description) // Display sanitation cannot alter resolver input.
    }
    @Test fun `no connected native resolver means no eligible source cards`() {
        assertFalse(NativeDebridSourceCards.eligible(source(), DebridSettings()))
        assertFalse(NativeDebridSourceCards.eligible(source(), settings.copy(enabled = false)))
    }
    @Test fun `raw direct plugin URL or torrent file cannot masquerade as native service file`() {
        for (url in listOf("https://addon.test/file.mkv", "https://addon.test/file.torrent", "torrent:invalid", "magnet:?xt=urn:btih:invalid")) {
            assertFalse(url, NativeDebridSourceCards.eligible(source().copy(infoHash = null, url = url), settings))
        }
        assertFalse(NativeDebridSourceCards.eligible(source().copy(infoHash = "not-a-hash"), settings))
    }
    @Test fun `inactive provider cached claim and ungranted direct URL are hidden`() {
        assertFalse(NativeDebridSourceCards.eligible(source().copy(infoHash = null, clientResolve = resolve("realdebrid")), settings))
        assertFalse(NativeDebridSourceCards.eligible(source().copy(url = "https://addon.test/file.mkv", clientResolve = resolve("alldebrid")), settings))
        assertTrue(NativeDebridSourceCards.eligible(source().copy(clientResolve = resolve("alldebrid")), settings))
    }
    @Test fun `explicit RD AD hoster uses connected matching service but not arbitrary URL`() {
        val hoster = source().copy(infoHash = null, url = "https://hoster.test/file", clientResolve = resolve("alldebrid", "hoster"))
        assertTrue(NativeDebridSourceCards.eligible(hoster, settings))
        assertFalse(NativeDebridSourceCards.eligible(hoster.copy(url = "ftp://hoster.test/file"), settings))
        assertFalse(NativeDebridSourceCards.eligible(hoster.copy(clientResolve = resolve("torbox", "hoster")), settings))
    }
    @Test fun `seed count lines fields and emoji vanish without removing film title words`() {
        assertEquals("427 MB", NativeDebridSourceCards.displayText("Seeds: 0\n427 MB"))
        assertEquals("427 MB", NativeDebridSourceCards.displayText("👤 12 427 MB"))
        assertEquals("1080p | 427 MB", NativeDebridSourceCards.displayText("1080p | Seeders: 24 | 427 MB"))
        assertEquals("Seeds of Yesterday\nThe Seeders (2024)", NativeDebridSourceCards.displayText("Seeds of Yesterday\nThe Seeders (2024)"))
    }
    @Test fun `only seeder count badge names are hidden`() {
        for (name in listOf("Seeds", "Seeders: 12", "12 seeders", "👤 12")) assertTrue(name, NativeDebridSourceCards.isSeederBadge(name))
        for (name in listOf("HEVC", "4K", "Seeds of Yesterday", "5.1")) assertFalse(name, NativeDebridSourceCards.isSeederBadge(name))
    }
    @Test fun `presentation excludes unsupported cards before groups ranking and limits`() {
        val eligible = source()
        val direct = eligible.copy(url = "https://addon.test/file.mkv", infoHash = null, addonName = "Raw direct")
        val p2pOnly = eligible.copy(infoHash = "invalid", addonName = "P2P only")
        val presentation = DebridStreamPresentation(mockk(), mockk(), DebridStreamFormatter(DebridStreamTemplateEngine()))
        val groups = presentation.apply(listOf(AddonStreams("Sources", null, listOf(direct, p2pOnly, eligible))), settings)
        assertEquals(listOf(eligible), groups.single().streams)
        assertEquals(listOf(eligible), presentation.rankAll(listOf(direct, p2pOnly, eligible), settings))
    }
    @Test fun `native cloud grant remains eligible with unresolved resolver disabled`() {
        val cloud = source().copy(infoHash = null, url = "https://native-cloud.test/file.mkv")
        val disabled = settings.copy(enabled = false, cloudLibraryEnabled = true)
        assertTrue(NativeDebridSourceCards.eligible(cloud, disabled) { it == cloud.url })
        assertFalse(NativeDebridSourceCards.eligible(cloud, disabled) { false })
    }
    @Test fun `granted nonpreferred cloud URL survives apply and ranking with resolver on or off`() {
        val cloud = source().copy(infoHash = null, url = "https://native-cloud.test/file.mkv", clientResolve = resolve("realdebrid"))
        val ungranted = cloud.copy(url = "https://ungranted.test/file.mkv")
        val presentation = DebridStreamPresentation(mockk(), mockk(), DebridStreamFormatter(DebridStreamTemplateEngine())) { it == cloud.url }
        for (configured in listOf(settings, settings.copy(enabled = false, cloudLibraryEnabled = true))) {
            val groups = presentation.apply(listOf(AddonStreams("Cloud", null, listOf(ungranted, cloud))), configured)
            assertEquals(listOf(cloud), groups.single().streams)
            assertEquals(listOf(cloud), presentation.rankAll(listOf(ungranted, cloud), configured))
        }
    }
    @Test fun `episode coordinates and folder totals forbid reported movie average even if runtime supplied`() {
        val movie = source().copy(title = "Movie 10 GB", reportedMovieRuntimeMillis = 120_000L)
        assertNull(ReportedStreamFacts.averageBitsPerSecond(movie.copy(clientResolve = resolve("alldebrid").copy(season = 1, episode = 2))))
        val raw = StreamClientResolveRaw(null, null, 10_000_000_000L, 100_000_000_000L, null, null, null, null)
        assertNull(ReportedStreamFacts.averageBitsPerSecond(movie.copy(clientResolve = resolve("alldebrid").copy(stream = StreamClientResolveStream(raw)))))
    }
    private fun source() = Stream(null, "Big Buck Bunny 4K", null, null, null, "a".repeat(40), 0, null, null, "Magnify", null)
    private fun resolve(service: String, type: String = "debrid") = StreamClientResolve(type, "a".repeat(40), 0, null, null,
        "Movie.4K.mkv", "Movie.4K.mkv", "movie", "tt1", "tt1", "Movie", null, null, service, 0, null, true)
}
