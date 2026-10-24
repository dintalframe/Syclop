package com.nuvio.tv.core.network

import com.nuvio.tv.domain.model.AuthState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyclopLocalModeTest {
    @Test
    fun localStartupIsSignedOutWithoutWaitingForCloud() {
        assertEquals(AuthState.SignedOut, startupAuthState(localOnly = true))
        assertEquals(AuthState.Loading, startupAuthState(localOnly = false))
    }

    @Test
    fun fixedCloudEndpointsAreBlockedButDebridIsUntouched() {
        listOf("nuvio.tv", "api.nuvio.tv", "cloud-disabled.syclop.invalid")
            .forEach { assertTrue(it, isDisabledCloudHost(it)) }
        listOf("api.real-debrid.com", "api.alldebrid.com", "example.org", "nuvio.tv.example.org", "localhost", "127.0.0.1", "::1")
            .forEach { assertFalse(it, isDisabledCloudHost(it)) }
    }

    @Test
    fun supabaseGuardStopsBeforeAnyNetworkEngineExecutes() = runBlocking {
        var engineCalled = false
        val engine = MockEngine {
            engineCalled = true
            error("The network engine must not execute")
        }
        val client = HttpClient(engine) { install(SyclopCloudGuard) }
        try {
            val error = runCatching { client.get("$SYCLOP_DISABLED_CLOUD_URL/auth/v1/user") }.exceptionOrNull()
            assertTrue(error.toString(), error is LocalCloudDisabledException)
            assertFalse(engineCalled)
        } finally {
            client.close()
        }
    }
}
