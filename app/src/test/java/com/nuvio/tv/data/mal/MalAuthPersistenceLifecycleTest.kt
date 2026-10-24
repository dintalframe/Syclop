package com.nuvio.tv.data.mal

import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MalAuthPersistenceLifecycleTest {
    @Test fun `failed encrypted commit never publishes a connected account or exposes its tokens`() = runTest {
        val persistence = MemoryMalAccountPersistence(); val profiles = MutableStateFlow(1)
        val store = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        persistence.failWrites = true
        assertThrows(IOException::class.java) { store.complete(store.state.value.scope, malTestTokens, "Viewer") }
        assertFalse(store.state.value.connected); assertNull(store.credentials(store.state.value.scope))
        assertFalse(persistence.values.containsKey("account.p1"))
    }
    @Test fun `successful commit precedes publication and survives store restart`() = runTest {
        val persistence = MemoryMalAccountPersistence(); val profiles = MutableStateFlow(1)
        val store = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        assertTrue(store.complete(store.state.value.scope, malTestTokens, "Viewer"))
        assertTrue(persistence.values.containsKey("account.p1"))
        val restored = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        assertTrue(restored.state.value.connected); assertEquals("Viewer", restored.state.value.username)
        assertEquals(malTestTokens, restored.credentials(restored.state.value.scope))
    }
    @Test fun `failed refresh persistence retains the original token pair`() = runTest {
        val persistence = MemoryMalAccountPersistence(); val profiles = MutableStateFlow(1)
        val store = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        store.complete(store.state.value.scope, malTestTokens, "Viewer"); persistence.failWrites = true
        assertThrows(IOException::class.java) { store.replaceTokens(store.state.value.scope, malTestTokens, malTestTokens.copy(accessToken = "new-access")) }
        assertEquals(malTestTokens, store.credentials(store.state.value.scope))
    }
    @Test fun `cold start never reads viewer one before the actual profile arrives`() = runTest {
        val persistence = MemoryMalAccountPersistence()
        val original = MalAuthStore(persistence, MutableStateFlow(1), backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        original.complete(original.state.value.scope, malTestTokens, "Viewer A")
        val profiles = MutableSharedFlow<Int>()
        val cold = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        assertEquals(0, cold.state.value.scope.profileId); assertFalse(cold.state.value.connected)
        profiles.emit(2); runCurrent()
        assertEquals(2, cold.state.value.scope.profileId); assertNull(cold.credentials(cold.state.value.scope))
    }
    @Test fun `failed profile read clears previous viewer and observer survives`() = runTest {
        val persistence = MemoryMalAccountPersistence(); val profiles = MutableStateFlow(1)
        val stages = mutableListOf<MalAuthStage>()
        val store = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { stage, _ -> stages += stage })
        store.complete(store.state.value.scope, malTestTokens, "Viewer A")
        persistence.failReadKeys = setOf("account.p2"); profiles.value = 2; runCurrent()
        assertEquals(2, store.state.value.scope.profileId); assertFalse(store.state.value.connected)
        assertNull(store.credentials(store.state.value.scope)); assertTrue(stages.contains(MalAuthStage.STORAGE_READ))
        profiles.value = 1; runCurrent()
        assertTrue(store.state.value.connected); assertEquals("Viewer A", store.state.value.username)
    }
    @Test fun `late completion cannot save after a profile away and back`() = runTest {
        val profiles = MutableStateFlow(1); val persistence = MemoryMalAccountPersistence()
        val store = MalAuthStore(persistence, profiles, backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        val old = store.state.value.scope
        profiles.value = 2; runCurrent(); profiles.value = 1; runCurrent()
        assertFalse(store.complete(old, malTestTokens, "Wrong completion")); assertFalse(store.state.value.connected)
    }
}

internal class MemoryMalAccountPersistence : MalAccountPersistence {
    val values = mutableMapOf<String, String>()
    var failWrites = false
    var failReadKeys = emptySet<String>()
    override fun read(key: String): String? {
        if (key in failReadKeys) throw IOException("Synthetic private detail")
        return values[key]
    }
    override fun write(values: Map<String, String?>) {
        if (failWrites) throw IOException("Synthetic private detail")
        values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
    }
    override fun clear() { if (failWrites) throw IOException("Synthetic failure"); values.clear() }
}
internal val malTestConfiguration = MalConfiguration("own-test-public-client", "https://dintalframe.github.io/Syclop/mal/")
internal val malTestTokens = MalTokens("private-test-access", "private-test-refresh", 2_000_000)
