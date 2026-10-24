package com.nuvio.tv.data.simkl

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SimklV2AuthStoreTest {
    @Test fun `cancel invalidates a late approval even without a visible session`() {
        val store = SimklV2AuthStore(MemorySimklV2Persistence()); val scope = store.currentScope()
        store.clearPinSession()
        assertFalse(store.saveV2Tokens(pair(), scope))
        assertNull(store.authorization())
    }

    @Test fun `profile switch away and back invalidates old scope`() {
        val store = SimklV2AuthStore(MemorySimklV2Persistence()); val scope = store.currentScope()
        store.selectProfile(2); store.selectProfile(1)
        assertFalse(store.saveV2Tokens(pair(), scope))
    }

    @Test fun `atomic refreshed pair preserves identity and refuses stale overwrite`() {
        val store = SimklV2AuthStore(MemorySimklV2Persistence())
        assertTrue(store.saveV2Tokens(pair(), store.currentScope()))
        val scope = store.currentScope(); store.saveIdentity("Viewer", 42, scope = scope)
        assertTrue(store.saveV2Tokens(pair().copy(accessToken = "new-access"), scope, "test-access"))
        assertFalse(store.saveV2Tokens(pair().copy(accessToken = "stale-access"), scope, "test-access"))
        assertEquals("new-access", store.authorization()?.accessToken)
        assertEquals("Viewer", store.state.value.username)
        assertEquals(scope, store.currentScope())
    }

    @Test fun `encryption persistence failure never publishes a new account`() {
        val persistence = MemorySimklV2Persistence().apply { failWrites = true }
        val store = SimklV2AuthStore(persistence)
        assertThrows(java.io.IOException::class.java) { store.saveV2Tokens(pair(), store.currentScope()) }
        assertNull(store.authorization()); assertFalse(store.state.value.isAuthenticated)
    }

    @Test fun `process restart cannot resume an old device polling session`() {
        val persistence = MemorySimklV2Persistence(); val store = SimklV2AuthStore(persistence)
        store.savePinSession(SimklPinSession("ABCD-EFGH", "https://simkl.com/pin", 100_000, 5))
        assertNull(SimklV2AuthStore(persistence).state.value.pinSession)
    }

    @Test fun `profile removal deletes the complete private grant`() {
        val persistence = MemorySimklV2Persistence(); val store = SimklV2AuthStore(persistence)
        store.saveV2Tokens(pair(), store.currentScope()); store.removeProfile(1)
        assertTrue(persistence.values.isEmpty()); assertNull(store.authorization())
    }

    @Test fun `credentials and authorization diagnostics redact both tokens`() {
        val store = SimklV2AuthStore(MemorySimklV2Persistence()); store.saveV2Tokens(pair(), store.currentScope())
        val diagnostics = listOf(pair().toString(), store.authorization().toString(), store.oauthAuthorization().toString())
        diagnostics.forEach { assertFalse(it.contains("test-access")); assertFalse(it.contains("test-refresh")) }
    }

    @Test fun `cold start does not expose default viewer credentials before actual profile loads`() {
        val persistence = MemorySimklV2Persistence()
        val first = SimklV2AuthStore(persistence)
        first.saveV2Tokens(pair(), first.currentScope())
        val cold = SimklV2AuthStore(persistence, initialProfileId = 0)
        assertNull(cold.authorization())
        assertFalse(cold.saveV2Tokens(pair(), cold.currentScope()))
        cold.selectProfile(2)
        assertNull(cold.authorization())
        cold.selectProfile(1)
        assertEquals("test-access", cold.authorization()?.accessToken)
    }

    @Test fun `failed profile read never retains previous viewer authorization`() {
        val persistence = MemorySimklV2Persistence()
        val store = SimklV2AuthStore(persistence)
        store.saveV2Tokens(pair(), store.currentScope())
        persistence.failReadsFor = setOf(2)
        store.selectProfile(2)
        assertEquals(2, store.currentScope().profileId)
        assertNull(store.authorization())
        assertNull(store.oauthAuthorization())
        assertFalse(store.state.value.isAuthenticated)
        assertEquals(SimklAuthError.STORAGE, store.state.value.error)
    }

    @Test fun `constructor read failure creates a disconnected safe storage error`() {
        val store = SimklV2AuthStore(MemorySimklV2Persistence().apply { failReadsFor = setOf(1) })
        assertNull(store.authorization())
        assertEquals(SimklAuthError.STORAGE, store.state.value.error)
    }

    @Test fun `profile observer survives failed encrypted read and handles next selection`() = runTest {
        val persistence = MemorySimklV2Persistence()
        val store = SimklV2AuthStore(persistence)
        store.saveV2Tokens(pair(), store.currentScope())
        persistence.failReadsFor = setOf(2)
        val profiles = MutableSharedFlow<Int>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            profiles.collect(store::selectProfile)
        }
        profiles.emit(2)
        assertTrue(observer.isActive)
        assertNull(store.authorization())
        profiles.emit(1)
        assertTrue(observer.isActive)
        assertEquals("test-access", store.authorization()?.accessToken)
        assertNull(store.state.value.error)
    }

    private fun pair() = SimklV2Tokens("test-access", "test-refresh", 100_000, 1_000_000, "media:read media:write")
}
