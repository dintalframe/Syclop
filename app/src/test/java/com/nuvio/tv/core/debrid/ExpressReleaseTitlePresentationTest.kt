package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.express.ExpressRow
import com.nuvio.tv.core.express.toStream
import com.nuvio.tv.domain.model.StreamBadge
import org.junit.Assert.*
import org.junit.Test

class ExpressReleaseTitlePresentationTest {
    @Test fun `actual multiline release card becomes one heading without repeated filename hash or Ready body`() {
        val release = "Cyberpunk Edgerunners S02e01 Xvid-afg"
        val original = source(release).copy(name = "Direct TB Instant",
            description = "$release\nXviD\n273.0 MB\nReady (TB)\nCyberpunk Edgerunners S02E01 XviD-AFG\n${"a".repeat(40)}",
            badges = listOf(StreamBadge("Available: TorBox", tagStyle = "native-service:torbox"),
                StreamBadge("XviD", tagStyle = "native-fact")))
        val identity = original.stableKey()
        val display = NativeDebridSourceCards.compactDisplay(original, fileSizeShownInBadges = true)
        assertEquals(release, display.title)
        assertNull(display.description)
        assertEquals(identity, original.stableKey())
        assertTrue(original.description!!.contains("Ready (TB)"))
        assertTrue(original.description!!.endsWith("a".repeat(40)))
        assertEquals("Direct TB Instant", original.name)
        assertEquals("a".repeat(40), original.infoHash)
        assertEquals(original.description, NativeDebridSourceCards.displayDescription(original))
    }

    @Test fun `compact details retain size and audio when they are not already visible as badges`() {
        val stream = source("Example.S02E01.mkv").copy(name = "FHD RD Instant",
            description = "Example.S02E01.mkv\nHEVC\nAAC 5.1\n2.4 GB\nNot Ready (RD)\nExample S02E01",
            badges = listOf(StreamBadge("Resolve with: Real-Debrid", tagStyle = "native-service:realdebrid"),
                StreamBadge("HEVC", tagStyle = "native-fact")))
        val display = NativeDebridSourceCards.compactDisplay(stream)
        assertEquals("Example.S02E01.mkv", display.title)
        assertEquals("AAC 5.1 · 2.4 GB", display.description)
    }

    @Test fun `meaningful custom names and custom description facts survive compact display`() {
        val stream = source("Example.S02E01.mkv").copy(name = "My preferred release",
            description = "1080p HEVC\nDual audio\nProvider supplied notes",
            badges = listOf(StreamBadge("Resolve with: TorBox", tagStyle = "native-service:torbox")))
        val display = NativeDebridSourceCards.compactDisplay(stream)
        assertEquals("My preferred release", display.title)
        assertEquals("1080p HEVC · Dual audio · Provider supplied notes", display.description)
    }

    @Test fun `compact source names never infer a service from an unproven Instant label`() {
        val stream = source("Exact.Release.S02E01.mkv").copy(name = "Direct TB Instant")
        assertEquals("Direct TB Instant", NativeDebridSourceCards.compactDisplay(stream).title)
        assertEquals(stream.title, NativeDebridSourceCards.compactDisplay(stream).description)
    }

    @Test fun `film titles mentioning Ready or Seeds remain visible and missing identity never shows raw hash`() {
        for (title in listOf("Ready Player One", "Seeds of Yesterday", "The Seeders (2024)")) {
            val stream = source(title).copy(name = title, description = "Seeders: 17")
            assertEquals(title, NativeDebridSourceCards.compactDisplay(stream).title)
        }
        val hash = "a".repeat(40)
        val hashOnly = source(hash).copy(name = "Direct TB Instant", description = "Ready (TB)\n$hash",
            badges = listOf(StreamBadge("Available: TorBox", tagStyle = "native-service:torbox")))
        val display = NativeDebridSourceCards.compactDisplay(hashOnly)
        assertNull(display.title)
        assertNull(display.description)
    }

    @Test fun `provider badges stay first and precise formats replace only redundant generic labels`() {
        val badges = listOf(StreamBadge("HDR"), StreamBadge("DV"), StreamBadge("DD"),
            StreamBadge("HDR10+"), StreamBadge("Dolby Vision"), StreamBadge("DD+"),
            StreamBadge("Available: TorBox", tagStyle = "native-service:torbox"),
            StreamBadge("Try Real-Debrid", tagStyle = "native-service:realdebrid"),
            StreamBadge("Magnify Scrapers"), StreamBadge("Seeds: 12"), StreamBadge("HDR10+"))
        assertEquals(listOf("Available: TorBox", "Try Real-Debrid", "HDR10+", "Dolby Vision", "DD+"),
            NativeDebridSourceCards.compactBadges(badges).map { it.name })
        assertEquals(listOf("HDR"), NativeDebridSourceCards.compactBadges(listOf(StreamBadge("HDR"))).map { it.name })
        assertEquals(listOf("DV", "DD"),
            NativeDebridSourceCards.compactBadges(listOf(StreamBadge("DV"), StreamBadge("DD"))).map { it.name })
        assertEquals(11, badges.size)
    }

    @Test fun `HTML Express release identity survives seed-only description sanitation`() {
        val title = "[Fixture] Example Second Season S02 01-10 (1080p HEVC Dual Audio)"
        val stream = source(title)
        // Exercise the actual Express conversion and original domain description precedence.
        assertEquals("Knaben", stream.name)
        assertEquals(title, stream.title)
        assertEquals("Seeds: 17", stream.getDisplayDescription())
        assertNull(NativeDebridSourceCards.displayText(stream.getDisplayDescription()))
        assertEquals(title, NativeDebridSourceCards.displayDescription(stream))
        // This is presentation only: retain all original resolver inputs, without promoting a title to filename.
        assertEquals("Seeds: 17", stream.description)
        assertEquals(title, stream.title)
        assertEquals("a".repeat(40), stream.infoHash)
        assertNull(stream.behaviorHints?.filename)
        assertNull(stream.fileIdx)
        assertNull(stream.clientResolve)
    }

    @Test fun `seed count or blank descriptions fall back to the exact existing title`() {
        val title = "Example.S02E01.1080p.ENG.mkv"
        for (description in listOf("Seeds: 0", "Seeders: 1,234", "12 seeders", "👤 42", "  ", null)) {
            assertEquals(title, NativeDebridSourceCards.displayDescription(source(title).copy(description = description)))
        }
    }

    @Test fun `mixed release text keeps its identity while only the seed field disappears`() {
        val title = "Example.S02E01.1080p.ENG.mkv"
        assertEquals("$title | 427 MB", NativeDebridSourceCards.displayDescription(
            source("Other existing title").copy(description = "$title | Seeds: 17 | 427 MB")))
    }

    @Test fun `a useful custom description is preserved instead of overriding its template with the release title`() {
        val stream = source("Original release title").copy(description = "1080p HEVC\nProvider supplied details")
        assertEquals(stream.description, NativeDebridSourceCards.displayDescription(stream))
    }

    @Test fun `title words mentioning seeds are preserved without inventing episode or language facts`() {
        for (title in listOf("Seeds of Yesterday", "The Seeders (2024)", "Example - 01 [ENG SUB]")) {
            assertEquals(title, NativeDebridSourceCards.displayDescription(source(title)))
        }
    }

    @Test fun `missing release identity remains unknown and never falls back to URL hash or provider label`() {
        val stream = source(null)
        assertNull(NativeDebridSourceCards.displayDescription(stream))
        assertNull(NativeDebridSourceCards.displayDescription(stream.copy(title = "Seeds: 23")))
    }

    private fun source(title: String?) = requireNotNull(ExpressRow(title = title, size = 26_300_000_000L,
        url = "magnet:?xt=urn:btih:${"a".repeat(40)}", hash = null, seeds = "17").toStream("Knaben"))
}
