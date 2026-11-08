package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridStreamFormatterTest {
    @Test
    fun `formats cached local debrid torrents with tv default formatter`() {
        val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
        val scope = StreamAvailabilityScope(1, 2L, 3L)
        val status = StreamDebridCacheStatus(DebridProviders.TORBOX_ID, "Torbox", StreamDebridCacheState.CACHED,
            cachedName = "Movie.2026.1080p.mkv", cachedSize = 1_500_000_000L, nativeScope = scope,
            infoHash = "a".repeat(40), checkedAtMs = 900L, expiresAtMs = 1900L)
        val stream = Stream(
            name = "Original torrent",
            title = "movie",
            description = null,
            url = null,
            ytId = null,
            infoHash = "a".repeat(40),
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = "Movie.2026.1080p.mkv"
            ),
            addonName = "Addon",
            addonLogo = null,
            debridCacheStatus = status,
            nativeDebridAvailability = listOf(status)
        )

        val formatted = formatter.format(stream, DebridSettings(torboxApiKey = "fixture-key"), scope = scope, nowMs = 1000L)

        assertEquals("FHD TB Instant", formatted.name)
        assertTrue(formatted.description.orEmpty().contains("Ready (TB)"))
        assertTrue(formatted.description.orEmpty().contains("Movie.2026.1080p.mkv"))
    }
}
