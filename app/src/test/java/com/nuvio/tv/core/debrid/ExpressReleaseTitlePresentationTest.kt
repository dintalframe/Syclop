package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.express.ExpressRow
import com.nuvio.tv.core.express.toStream
import org.junit.Assert.*
import org.junit.Test

class ExpressReleaseTitlePresentationTest {
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
