package com.nuvio.tv.data.mal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MalPlaybackGrantPersistenceTest {
    @Test fun `grant UUID survives token rotation and restart but not login or configuration replacement`() = runTest {
        val persistence = MemoryMalAccountPersistence()
        val profiles = MutableStateFlow(1)
        val store = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        store.complete(store.state.value.scope, malTestTokens, "Fixture")
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        assertTrue(store.replaceTokens(store.state.value.scope, malTestTokens, malTestTokens.copy(accessToken = "rotated")))
        assertEquals(first, store.capturePlaybackGrantId(1))
        assertEquals(first, MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration,
            diagnose = { _, _ -> }).capturePlaybackGrantId(1))
        store.signOut()
        assertFalse(store.isPlaybackGrantCurrent(1, first))
        store.complete(store.state.value.scope, malTestTokens, "Fixture")
        val second = requireNotNull(store.capturePlaybackGrantId(1))
        assertNotEquals(first, second)
        store.configure(malTestConfiguration.copy(clientId = "different-public-client"))
        assertFalse(store.isPlaybackGrantCurrent(1, second))
        assertNull(store.capturePlaybackGrantId(1))
    }

    @Test fun `legacy account backfill commits before exposing its UUID and preserves account on failure`() = runTest {
        val persistence = MemoryMalAccountPersistence()
        val profiles = MutableStateFlow(1)
        val original = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        original.complete(original.state.value.scope, malTestTokens, "Fixture")
        val legacy = JsonObject(Json.parseToJsonElement(persistence.values.getValue("account.p1")).jsonObject - "playback_grant_id").toString()
        persistence.values["account.p1"] = legacy
        val loaded = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        persistence.failWrites = true
        assertNull(loaded.capturePlaybackGrantId(1))
        assertTrue(loaded.state.value.connected)
        assertEquals(legacy, persistence.values["account.p1"])
        persistence.failWrites = false
        val id = requireNotNull(loaded.capturePlaybackGrantId(1))
        assertEquals(id, MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration,
            diagnose = { _, _ -> }).capturePlaybackGrantId(1))
        assertEquals(malTestTokens, loaded.credentials(loaded.state.value.scope))
    }

    @Test fun `profile selection and deletion do not transfer a durable grant`() = runTest {
        val persistence = MemoryMalAccountPersistence()
        val profiles = MutableStateFlow(1)
        val store = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        store.complete(store.state.value.scope, malTestTokens, "Fixture")
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        profiles.value = 2; runCurrent()
        assertFalse(store.isPlaybackGrantCurrent(1, first))
        assertNull(store.capturePlaybackGrantId(1))
        store.complete(store.state.value.scope, malTestTokens, "Other fixture")
        assertNotEquals(first, store.capturePlaybackGrantId(2))
        store.removeProfile(2)
        assertNull(store.capturePlaybackGrantId(2))
        profiles.value = 1; runCurrent()
        assertEquals(first, store.capturePlaybackGrantId(1))
        store.clearAllProfiles()
        assertFalse(store.isPlaybackGrantCurrent(1, first))
    }

    @Test fun `capture started before replacement cannot bind it after IO resumes`() = runTest {
        val store = MalAuthStore(MemoryMalAccountPersistence(), MutableStateFlow(1), backgroundScope,
            malTestConfiguration, diagnose = { _, _ -> })
        store.complete(store.state.value.scope, malTestTokens, "Fixture")
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        val lock = store.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(store)
        val pending = synchronized(lock) {
            val job = async(start = CoroutineStart.UNDISPATCHED) { store.capturePlaybackGrantId(1) }
            store.signOut()
            store.complete(store.state.value.scope, malTestTokens, "Fixture")
            job
        }
        assertNull(pending.await())
        assertFalse(store.isPlaybackGrantCurrent(1, first))
        assertNotEquals(first, store.capturePlaybackGrantId(1))
    }
}
