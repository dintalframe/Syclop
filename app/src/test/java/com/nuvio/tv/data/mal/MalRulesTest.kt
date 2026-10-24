package com.nuvio.tv.data.mal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MalRulesTest {
    private val redirect = "http://192.168.1.104:17893/mal/callback"

    @Test fun `PKCE verifier is fresh URL-safe and within MAL length bounds`() {
        val first = MalRules.randomUrlSafe(64)
        val second = MalRules.randomUrlSafe(64)
        assertTrue(first.length in 43..128)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(first, second)
    }

    @Test fun `callback accepts only the exact registered destination and matching state`() {
        assertEquals("one+two", MalRules.callbackCode("$redirect?code=one%2Btwo&state=expected", redirect, "expected"))
        reject { MalRules.callbackCode("$redirect?code=one&state=other", redirect, "expected") }
        reject { MalRules.callbackCode("$redirect?code=one", redirect, "expected") }
        reject { MalRules.callbackCode("http://evil.example:17893/mal/callback?code=one&state=expected", redirect, "expected") }
        reject { MalRules.callbackCode("$redirect/other?code=one&state=expected", redirect, "expected") }
    }

    @Test fun `duplicate decoded parameters and denied callbacks cannot authorize`() {
        reject { MalRules.callbackCode("$redirect?code=one&state=expected&%73tate=attacker", redirect, "expected") }
        reject { MalRules.callbackCode("$redirect?code=one&code=two&state=expected", redirect, "expected") }
        reject { MalRules.callbackCode("$redirect?error=access_denied&state=expected", redirect, "expected") }
        reject { MalRules.callbackCode("$redirect?code=one%0Atwo&state=expected", redirect, "expected") }
    }

    @Test fun `only active fixed-port callbacks use the local server`() {
        val native = MalConfiguration("1234567890abcdef1234567890abcdef", redirect)
        MalRules.validateConfiguration(native)
        assertTrue(MalRules.isLocalCallback(native))
        assertFalse(MalRules.isLocalCallback(native.copy(redirectUri = "https://example.com/mal/callback")))
        assertFalse(MalRules.isLocalCallback(native.copy(redirectUri = "http://192.168.1.104:18000/mal/callback")))
        reject { MalRules.validateConfiguration(native.copy(redirectUri = "$redirect?secret=invalid")) }
        reject { MalRules.validateConfiguration(native.copy(clientId = "")) }
    }

    @Test fun `completion requires 80 percent and does not guess seasons or specials`() {
        assertNull(MalRules.completedEpisode(79.99, 1, 3, false, 12))
        assertEquals(3, MalRules.completedEpisode(80.0, 1, 3, false, 12))
        assertNull(MalRules.completedEpisode(100.0, 2, 3, false, 12))
        assertNull(MalRules.completedEpisode(100.0, 0, 3, false, 12))
        assertNull(MalRules.completedEpisode(100.0, 1, 13, false, 12))
        assertNull(MalRules.completedEpisode(Double.NaN, 1, 3, false, 12))
        assertNull(MalRules.completedEpisode(120.0, 1, 3, false, 12))
        assertNull(MalRules.completedEpisode(100.0, null, null, false, 12))
        assertEquals(1, MalRules.completedEpisode(100.0, null, null, true, 1))
    }

    @Test fun `automatic progress cannot decrease watched count or overwrite status on replay`() {
        val current = MalAnime(1, "Example", totalEpisodes = 12, watchedEpisodes = 5, status = MalListStatus.ON_HOLD, onList = true)
        assertNull(MalRules.progressUpdate(current, 3))
        assertNull(MalRules.progressUpdate(current, 5))
        assertEquals(MalListStatus.WATCHING to 6, MalRules.progressUpdate(current, 6))
        assertEquals(MalListStatus.COMPLETED to 12, MalRules.progressUpdate(current, 12))
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("This authorization or update must be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
