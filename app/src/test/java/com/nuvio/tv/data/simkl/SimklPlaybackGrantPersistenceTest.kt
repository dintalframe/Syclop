package com.nuvio.tv.data.simkl

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SimklPlaybackGrantPersistenceTest {
    private fun tokens() = SimklV2Tokens("fixture-access", "fixture-refresh", 100_000, 1_000_000, "media:read media:write")

    @Test fun `grant UUID survives refresh and process restart but not a replacement login`() = runTest {
        val persistence = MemorySimklV2Persistence()
        val store = SimklV2AuthStore(persistence)
        assertTrue(store.saveV2Tokens(tokens(), store.currentScope()))
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        assertTrue(store.isPlaybackGrantCurrent(1, first))
        assertTrue(store.saveV2Tokens(tokens().copy(accessToken = "rotated"), store.currentScope(), "fixture-access"))
        assertEquals(first, store.capturePlaybackGrantId(1))
        assertEquals(first, SimklV2AuthStore(persistence).capturePlaybackGrantId(1))
        store.clearAuth()
        assertFalse(store.isPlaybackGrantCurrent(1, first))
        assertNull(store.capturePlaybackGrantId(1))
        store.saveV2Tokens(tokens(), store.currentScope())
        assertNotEquals(first, store.capturePlaybackGrantId(1))
    }

    @Test fun `legacy credential backfill commits once and failure retains credentials without an origin`() = runTest {
        val persistence = MemorySimklV2Persistence()
        val original = SimklV2AuthStore(persistence)
        original.saveV2Tokens(tokens(), original.currentScope())
        val legacy = JsonObject(Json.parseToJsonElement(persistence.values.getValue(1)).jsonObject - "playback_grant_id").toString()
        persistence.values[1] = legacy
        val loaded = SimklV2AuthStore(persistence)
        persistence.failWrites = true
        assertNull(loaded.capturePlaybackGrantId(1))
        assertTrue(loaded.state.value.isAuthenticated)
        assertEquals(legacy, persistence.values[1])
        persistence.failWrites = false
        val id = requireNotNull(loaded.capturePlaybackGrantId(1))
        val committed = persistence.values[1]
        assertEquals(id, loaded.capturePlaybackGrantId(1))
        assertEquals(committed, persistence.values[1])
        assertEquals(id, SimklV2AuthStore(persistence).capturePlaybackGrantId(1))
    }

    @Test fun `profile switch deletion and cleanup cannot reuse another grant`() = runTest {
        val store = SimklV2AuthStore(MemorySimklV2Persistence())
        store.saveV2Tokens(tokens(), store.currentScope())
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        store.selectProfile(2)
        assertFalse(store.isPlaybackGrantCurrent(1, first))
        assertNull(store.capturePlaybackGrantId(1))
        store.saveV2Tokens(tokens(), store.currentScope())
        assertNotEquals(first, store.capturePlaybackGrantId(2))
        store.removeProfile(2)
        assertNull(store.capturePlaybackGrantId(2))
        store.selectProfile(1)
        assertEquals(first, store.capturePlaybackGrantId(1))
        store.clearAllProfiles()
        assertFalse(store.isPlaybackGrantCurrent(1, first))
    }

    @Test fun `capture started before replacement cannot bind it after IO resumes`() = runTest {
        val store = SimklV2AuthStore(MemorySimklV2Persistence())
        store.saveV2Tokens(tokens(), store.currentScope())
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        // Hold the store monitor across IO dispatch so replacement deterministically wins.
        val lock = store.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(store)
        val pending = synchronized(lock) {
            val job = async(start = CoroutineStart.UNDISPATCHED) { store.capturePlaybackGrantId(1) }
            store.clearAuth()
            store.saveV2Tokens(tokens(), store.currentScope())
            job
        }
        assertNull(pending.await())
        assertFalse(store.isPlaybackGrantCurrent(1, first))
        assertNotEquals(first, store.capturePlaybackGrantId(1))
    }
}
