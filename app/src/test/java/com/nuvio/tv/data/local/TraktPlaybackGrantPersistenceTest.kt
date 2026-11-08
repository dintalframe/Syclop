package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TraktPlaybackGrantPersistenceTest {
    private fun token(access: String = "fixture-access") = TraktTokenResponseDto(
        accessToken = access, tokenType = "bearer", expiresIn = 604800,
        refreshToken = "fixture-refresh", createdAt = 1L)

    @Test fun `durable grant survives token refresh and process restart but not replacement login`() = runTest {
        val h = Harness()
        val store = h.newStore()
        store.saveToken(token(), 1, "fixture-public-client")
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        val expected = store.getCurrentState(1)
        assertTrue(store.saveRefreshedTokenIfCurrent(token("rotated"), expected, store.grantRevision(1)) { true })
        assertEquals(first, store.capturePlaybackGrantId(1))
        assertEquals(first, h.newStore().capturePlaybackGrantId(1))
        store.saveToken(token(), 1, "fixture-public-client")
        assertFalse(store.isPlaybackGrantCurrent(1, first))
        assertNotEquals(first, store.capturePlaybackGrantId(1))
        store.clearAuthForProfile(1)
        assertNull(store.capturePlaybackGrantId(1))
    }

    @Test fun `legacy valid credentials get one durable UUID without changing credentials`() = runTest {
        val h = Harness()
        h.memory(1).values.value = legacy()
        val store = h.newStore()
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        val committed = h.memory(1).values.value
        assertEquals(first, store.capturePlaybackGrantId(1))
        assertEquals(committed, h.memory(1).values.value)
        assertEquals(first, h.newStore().capturePlaybackGrantId(1))
        assertEquals("fixture-access", store.getCurrentState(1).accessToken)
        assertEquals("fixture-refresh", store.getCurrentState(1).refreshToken)
    }

    @Test fun `backfill persistence failure exposes no origin and erases no existing credential`() = runTest {
        val h = Harness()
        val original = legacy()
        h.memory(1).values.value = original
        h.memory(1).failWrites = true
        val store = h.newStore()
        assertNull(store.capturePlaybackGrantId(1))
        assertEquals(original, h.memory(1).values.value)
        assertTrue(store.getCurrentState(1).isAuthenticated)
    }

    @Test fun `profile change while IO is suspended cannot bind the replacement viewer`() = runTest {
        val h = Harness()
        h.memory(1).values.value = legacy()
        val store = h.newStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.memory(1).beforeUpdate = { entered.complete(Unit); release.await() }
        val captured = async { store.capturePlaybackGrantId(1) }
        entered.await()
        h.selection.incrementAndGet()
        h.active.value = 2
        release.complete(Unit)
        assertNull(captured.await())
        assertNull(h.memory(1).values.value[stringPreferencesKey("playback_grant_id")])
        assertNull(store.capturePlaybackGrantId(1))
    }

    @Test fun `default profile before actual selection is ready cannot provide an origin`() = runTest {
        val h = Harness()
        h.memory(1).values.value = legacy()
        h.ready.value = false
        val store = h.newStore()
        assertNull(store.capturePlaybackGrantId(1))
        h.ready.value = true
        val first = requireNotNull(store.capturePlaybackGrantId(1))
        assertTrue(store.isPlaybackGrantCurrent(1, first))
        h.ready.value = false
        assertFalse(store.isPlaybackGrantCurrent(1, first))
    }

    private fun legacy() = mutablePreferencesOf(
        stringPreferencesKey("access_token") to "sealed:fixture-access",
        stringPreferencesKey("refresh_token") to "sealed:fixture-refresh",
        stringPreferencesKey("registration_client_id") to "fixture-public-client")

    private class Harness {
        val active = MutableStateFlow(1)
        val ready = MutableStateFlow(true)
        val selection = AtomicLong()
        private val stores = mutableMapOf<Int, MemoryStore>()
        private val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns active
            every { activeProfileReady } returns ready
            every { activeProfileSelectionRevision } answers { selection.get() }
        }
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "trakt_auth_store") } answers { memory(firstArg()) }
        }
        private val cipher = mockk<DebridCredentialCipher> {
            every { encrypt(any(), any(), any()) } answers { "sealed:" + firstArg<String>() }
            every { decrypt(any(), any(), any()) } answers { firstArg<String>().removePrefix("sealed:") }
            every { isEncrypted(any()) } answers { firstArg<String>().startsWith("sealed:") }
        }
        fun memory(profile: Int) = synchronized(stores) { stores.getOrPut(profile) { MemoryStore() } }
        fun newStore() = TraktAuthDataStore(factory, profiles, cipher)
    }

    private class MemoryStore : DataStore<Preferences> {
        val values = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = values
        var failWrites = false
        var beforeUpdate: (suspend () -> Unit)? = null
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
            if (failWrites) throw IOException("Synthetic persistence failure")
            beforeUpdate?.invoke()
            transform(values.value).also { values.value = it }
        }
    }
}
