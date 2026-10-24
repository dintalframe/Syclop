package com.nuvio.tv.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogDiagnosticsTest {
    @Test fun `credential paths userinfo query and fragment never reach URL logs`() {
        val url = "https://user:password@addons.example:8443/rd-token-123/manifest.json?api_key=secret#private"
        assertEquals("https://addons.example:8443", url.urlForLog())
    }

    @Test fun `malformed URLs and arbitrary raw values are hidden`() {
        assertEquals("[redacted URL]", "secret-token not a URL".urlForLog())
        assertEquals("[redacted]", "secret-token".rawForLog())
        assertEquals("(null)", (null as String?).urlForLog())
    }

    @Test fun `API response bodies and nested exception messages are hidden`() {
        val body = "{\"access_token\":\"secret-token\"}"
        val error = IllegalStateException(body, IllegalArgumentException("https://example/secret-token"))
        assertFalse(body.bodySnippetForLog().contains("secret-token"))
        assertEquals("IllegalStateException <- IllegalArgumentException", error.diagnosticSummary())
    }
}
