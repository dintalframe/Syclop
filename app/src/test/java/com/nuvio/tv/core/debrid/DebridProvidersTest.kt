package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersTest {
    @Test
    fun `native real debrid and all debrid are visible and selectable`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            realDebridApiKey = "rd",
            allDebridApiKey = "ad",
            preferredResolverProviderId = DebridProviders.ALL_DEBRID_ID
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(listOf(DebridProviders.Torbox, DebridProviders.RealDebrid, DebridProviders.AllDebrid), services.map { it.provider })
        assertTrue(DebridProviders.isVisible(DebridProviders.TORBOX_ID))
        assertTrue(DebridProviders.isVisible(DebridProviders.REAL_DEBRID_ID))
        assertTrue(DebridProviders.isVisible(DebridProviders.ALL_DEBRID_ID))
        assertEquals(DebridProviders.ALL_DEBRID_ID, settings.activeResolverProviderId)
        assertTrue(settings.canResolvePlayableLinks)
        assertTrue(settings.hasCloudLibraryProvider)
        assertFalse(DebridProviders.RealDebrid.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.CloudLibrary))
        assertFalse(DebridProviders.AllDebrid.supports(DebridProviderCapability.LocalTorrentCacheCheck))
    }

    @Test
    fun `account model string representations never print credentials`() {
        val secret = "secret-token-do-not-print"
        assertFalse(DebridSettings(realDebridApiKey = secret, allDebridApiKey = secret).toString().contains(secret))
        assertFalse(DebridServiceCredential(DebridProviders.AllDebrid, secret).toString().contains(secret))
    }
}
