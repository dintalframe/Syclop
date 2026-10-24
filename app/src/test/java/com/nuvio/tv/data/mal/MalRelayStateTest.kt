package com.nuvio.tv.data.mal

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MalRelayStateTest {
    private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val local = "http://192.168.1.104:17893/mal/callback"

    @Test fun `relay state exactly matches static callback version and JSON contract`() {
        val state = MalRelayState.create(nonce, local)
        assertTrue(state.startsWith("syclop-mal-v1."))
        assertFalse(state.contains('='))
        val json = Base64.getUrlDecoder().decode(state.removePrefix(MalRelayState.PREFIX)).toString(Charsets.UTF_8)
        assertEquals("{\"nonce\":\"$nonce\",\"return_uri\":\"$local\"}", json)
        assertTrue(state.length <= 2048)
    }

    @Test fun `only canonical RFC1918 private IPv4 return URLs are supported`() {
        for (ip in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1")) {
            assertNotNull(ip, MalRelayState.privateReturnUri(ip))
            assertTrue(MalRelayState.isPrivateReturnUri(MalRelayState.privateReturnUri(ip)!!))
        }
        for (ip in listOf("127.0.0.1", "169.254.1.1", "172.15.1.1", "172.32.1.1", "8.8.8.8", "100.100.1.1",
            "192.168.001.1", "192.168.1.256", "192.168.1.1.evil.test", "[::1]", "::ffff:192.168.1.1")) {
            assertNull(ip, MalRelayState.privateReturnUri(ip))
        }
    }

    @Test fun `return URLs cannot inject credentials ports paths queries or fragments`() {
        for (uri in listOf("https://192.168.1.104:17893/mal/callback", "http://user@192.168.1.104:17893/mal/callback",
            "http://192.168.1.104/mal/callback", "$local?x=1", "$local#fragment", "$local/", "$local/../other")) {
            assertFalse(uri, MalRelayState.isPrivateReturnUri(uri))
            assertTrue(runCatching { MalRelayState.create(nonce, uri) }.isFailure)
        }
    }

    @Test fun `only the exact registered Syclop callback enables relay transport`() {
        assertTrue(MalRelayState.isRegisteredRedirect(MalRelayState.REGISTERED_CALLBACK))
        for (uri in listOf("https://evil.test/Syclop/mal/", "https://dintalframe.github.io/Syclop/mal",
            "http://dintalframe.github.io/Syclop/mal/", "https://dintalframe.github.io.evil.test/Syclop/mal/")) {
            assertFalse(MalRelayState.isRegisteredRedirect(uri))
            assertTrue(runCatching { MalRules.callbackCode("$local?code=abc&state=x", uri, "x", local) }.isFailure)
        }
    }

    @Test fun `manual HTTPS and current LAN callbacks require identical session state`() {
        val state = MalRelayState.create(nonce, local)
        for (destination in listOf(MalRelayState.REGISTERED_CALLBACK, local)) {
            assertEquals("authorization-code", MalRules.callbackCode("$destination?code=authorization-code&state=$state",
                MalRelayState.REGISTERED_CALLBACK, state, local))
            assertTrue(runCatching { MalRules.callbackCode("$destination?code=abc&state=wrong",
                MalRelayState.REGISTERED_CALLBACK, state, local) }.isFailure)
        }
        assertTrue(runCatching { MalRules.callbackCode("http://192.168.1.105:17893/mal/callback?code=abc&state=$state",
            MalRelayState.REGISTERED_CALLBACK, state, local) }.isFailure)
    }

    @Test fun `nonces are exactly canonical thirty two random bytes`() {
        for (invalid in listOf("short", nonce + "=", "*".repeat(43), "A".repeat(42) + "B")) {
            assertTrue(runCatching { MalRelayState.create(invalid, local) }.isFailure)
        }
        assertNotEquals(MalRelayState.create(nonce, local),
            MalRelayState.create(MalRules.randomUrlSafe(32), local))
    }

    @Test fun `token session and pending URL representations do not expose secrets`() {
        val tokens = MalTokens("secret-access", "secret-refresh", 123)
        val session = MalAuthorizationSession("https://example.test?code_challenge=private-verifier&state=private-state",
            "https://example.test/", 123, true)
        assertFalse(tokens.toString().contains("secret-access"))
        assertFalse(tokens.toString().contains("secret-refresh"))
        assertFalse(session.toString().contains("private-verifier"))
        assertFalse(session.toString().contains("private-state"))
        assertFalse(session.toString().contains("example.test"))
    }
}
