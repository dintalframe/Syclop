package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class NativeAvailabilityPresentationTest {
    private val scope = StreamAvailabilityScope(1, 3L, 7L)
    private val settings = DebridSettings(enabled = true, premiumizeApiKey = "synthetic-pm",
        torboxApiKey = "synthetic-tb", preferredResolverProviderId = DebridProviders.PREMIUMIZE_ID,
        connectedServiceFallbackEnabled = true)
    private var now = 1_000L
    private val presentation = DebridStreamPresentation(mockk(), mockk(),
        DebridStreamFormatter(DebridStreamTemplateEngine()), { false }, { scope }, { now })

    @Test fun `only current negative observations from every applicable account hide a candidate`() {
        val stream = source(listOf(status(DebridProviders.PREMIUMIZE_ID), status(DebridProviders.TORBOX_ID)))
        assertTrue(presentation.rankAll(listOf(stream), settings).isEmpty())
        assertTrue(presentation.apply(listOf(AddonStreams("Synthetic", null, listOf(stream))), settings)
            .single().streams.isEmpty())
        now = 11_000L
        assertEquals(1, presentation.rankAll(listOf(stream), settings).size)
    }

    @Test fun `a negative preferred service cannot hide unchecked AllDebrid fallback`() {
        val stream = source(listOf(status(DebridProviders.PREMIUMIZE_ID)))
        val connected = settings.copy(torboxApiKey = "", allDebridApiKey = "synthetic-ad")
        assertEquals(1, presentation.rankAll(listOf(stream), connected).size)
    }

    @Test fun `foreign account or hash observations cannot hide a candidate`() {
        for (observations in listOf(
            listOf(status(DebridProviders.PREMIUMIZE_ID).copy(nativeScope = scope.copy(credentialRevision = 8)), status(DebridProviders.TORBOX_ID)),
            listOf(status(DebridProviders.PREMIUMIZE_ID).copy(infoHash = "b".repeat(40)), status(DebridProviders.TORBOX_ID))
        )) assertEquals(1, presentation.rankAll(listOf(source(observations)), settings).size)
    }

    private fun status(provider: String) = StreamDebridCacheStatus(provider, "Synthetic",
        StreamDebridCacheState.NOT_CACHED, nativeScope = scope, infoHash = "a".repeat(40),
        checkedAtMs = 1_000L, expiresAtMs = 11_000L)
    private fun source(observations: List<StreamDebridCacheStatus>) = Stream("Synthetic", "Show.S01E04.mkv",
        null, null, null, "a".repeat(40), null, null, null, "Synthetic", null,
        nativeDebridAvailability = observations)
}
