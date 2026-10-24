package com.nuvio.tv.core.player

import org.junit.Assert.*
import org.junit.Test

class ExternalPlayerChoiceRulesTest {
    private val just = InstalledExternalPlayer("com.brouken.player", "A changed label")
    private val vlc = InstalledExternalPlayer("org.videolan.vlc", "VLC for Android")
    @Test fun `Just Player and VLC lead other installed handlers with stable deduplication`() {
        val result = ExternalPlayerChoiceRules.ordered(listOf(InstalledExternalPlayer("org.sample.zeta", "Zeta"), vlc,
            InstalledExternalPlayer("org.sample.alpha", "Alpha"), just, just), "com.dintal.syclop")
        assertEquals(listOf("com.brouken.player", "org.videolan.vlc", "org.sample.alpha", "org.sample.zeta"), result.map { it.packageName })
        assertEquals("Just Player", result.first().label)
    }
    @Test fun `own app and malformed package targets are excluded`() {
        val result = ExternalPlayerChoiceRules.ordered(listOf(just, InstalledExternalPlayer("com.dintal.syclop", "Self"),
            InstalledExternalPlayer("https://example.org", "URL"), InstalledExternalPlayer("com.player/.Activity", "Component")), "com.dintal.syclop")
        assertEquals(listOf(just.packageName), result.map { it.packageName })
    }
    @Test fun `missing saved player does not select another installed app`() {
        val error = runCatching { ExternalPlayerChoiceRules.resolvePackage(listOf(just), vlc.packageName, just.packageName) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(ExternalPlayerChoiceRules.UNAVAILABLE_MESSAGE, error?.message)
    }
    @Test fun `real installed Android default is made explicit`() {
        assertEquals(vlc.packageName, ExternalPlayerChoiceRules.resolvePackage(listOf(just, vlc), null, vlc.packageName))
    }
    @Test fun `system resolver or absent default never receives a signed file`() {
        for (default in listOf(null, "android", "com.android.intentresolver")) {
            val error = runCatching { ExternalPlayerChoiceRules.resolvePackage(listOf(just, vlc), null, default) }.exceptionOrNull()
            assertEquals(ExternalPlayerChoiceRules.CHOOSE_MESSAGE, error?.message)
        }
    }
    @Test fun `explicit valid choice is not replaced by Android default`() {
        assertEquals(just.packageName, ExternalPlayerChoiceRules.resolvePackage(listOf(just, vlc), just.packageName, vlc.packageName))
    }
    @Test fun `stored package format excludes URI intent and credential payloads`() {
        for (bad in listOf("", "vlc", "com.player/.Activity", "intent://video", "com.player?api_key=secret", "com.\nplayer", "a." + "b".repeat(254))) {
            assertFalse(bad, ExternalPlayerChoiceRules.validPackage(bad))
        }
        assertTrue(ExternalPlayerChoiceRules.validPackage("com.brouken.player"))
    }
}
