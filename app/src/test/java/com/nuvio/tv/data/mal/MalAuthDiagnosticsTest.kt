package com.nuvio.tv.data.mal

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MalAuthDiagnosticsTest {
    @Test fun `diagnostics never include exception message URLs code or tokens`() {
        val secret = "https://private.test/callback?code=one&access_token=private-access"
        val message = MalAuthDiagnostics.message(MalAuthStage.TOKEN_EXCHANGE, IOException(secret))
        assertEquals("stage=token_exchange class=IOException", message)
        assertFalse(message.contains("http")); assertFalse(message.contains("private")); assertFalse(message.contains("code="))
    }
    @Test fun `only numeric HTTP status augments the fixed stage and exception class`() {
        assertEquals("stage=account_read class=MalApiException status=403", MalAuthDiagnostics.message(MalAuthStage.ACCOUNT_READ, MalApiException(403)))
        assertFalse(MalAuthDiagnostics.message(MalAuthStage.PERSIST, MalApiException(Int.MAX_VALUE)).contains("status="))
    }
}
