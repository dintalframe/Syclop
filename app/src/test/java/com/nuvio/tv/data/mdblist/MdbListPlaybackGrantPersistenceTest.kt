package com.nuvio.tv.data.mdblist

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MdbListPlaybackGrantPersistenceTest {
    @Test fun `grant UUID survives refresh and restart while new authorization replaces it`() = runTest {
        val h = MdbListTestHarness()
        h.connected()
        val first = requireNotNull(h.store.capturePlaybackGrantId(1))
        val old = requireNotNull(h.store.authorization())
        assertTrue(h.store.refreshTokens(old.tokens.copy(accessToken = "rotated"), old))
        assertEquals(first, h.store.capturePlaybackGrantId(1))
        assertEquals(first, MdbListAuthStore(h.persistence).capturePlaybackGrantId(1))
        h.store.clearAuth()
        assertFalse(h.store.isPlaybackGrantCurrent(1, first))
        h.connected()
        assertNotEquals(first, h.store.capturePlaybackGrantId(1))
    }

    @Test fun `legacy backfill is additive durable and fails closed without erasing credentials`() = runTest {
        val h = MdbListTestHarness()
        h.connected()
        val legacy = JsonObject(Json.parseToJsonElement(h.persistence.profiles.getValue(1)).jsonObject - "playback_grant_id").toString()
        h.persistence.profiles[1] = legacy
        val loaded = MdbListAuthStore(h.persistence)
        h.persistence.failWrites = true
        assertNull(loaded.capturePlaybackGrantId(1))
        assertTrue(loaded.state.value.isAuthenticated)
        assertEquals(legacy, h.persistence.profiles[1])
        h.persistence.failWrites = false
        val id = requireNotNull(loaded.capturePlaybackGrantId(1))
        assertEquals(id, MdbListAuthStore(h.persistence).capturePlaybackGrantId(1))
        assertEquals("access-one", loaded.authorization()?.tokens?.accessToken)
    }

    @Test fun `profile selection and profile deletion reject old playback origins`() = runTest {
        val h = MdbListTestHarness()
        h.connected()
        val first = requireNotNull(h.store.capturePlaybackGrantId(1))
        h.store.selectProfile(2)
        assertFalse(h.store.isPlaybackGrantCurrent(1, first))
        assertNull(h.store.capturePlaybackGrantId(1))
        h.connected()
        assertNotEquals(first, h.store.capturePlaybackGrantId(2))
        h.store.removeProfile(2)
        assertNull(h.store.capturePlaybackGrantId(2))
        h.store.selectProfile(1)
        assertEquals(first, h.store.capturePlaybackGrantId(1))
        h.store.clearAllProfiles()
        assertFalse(h.store.isPlaybackGrantCurrent(1, first))
    }

    @Test fun `capture started before replacement cannot bind it after IO resumes`() = runTest {
        val h = MdbListTestHarness()
        h.connected()
        val first = requireNotNull(h.store.capturePlaybackGrantId(1))
        val lock = h.store.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(h.store)
        val pending = synchronized(lock) {
            val job = async(start = CoroutineStart.UNDISPATCHED) { h.store.capturePlaybackGrantId(1) }
            h.store.clearAuth()
            h.connected()
            job
        }
        assertNull(pending.await())
        assertFalse(h.store.isPlaybackGrantCurrent(1, first))
        assertNotEquals(first, h.store.capturePlaybackGrantId(1))
    }
}
