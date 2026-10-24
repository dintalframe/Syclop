package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MDBListRatingCredentialStorageTest {
    private val key = stringPreferencesKey("mdblist_api_key")
    private val cipher = MDBListRatingCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }

    @Test fun `ratings credentials use authenticated ciphertext bound to the profile`() {
        val value = cipher.encrypt("synthetic-rating-key", 1)
        assertTrue(cipher.isEncrypted(value))
        assertFalse(value.contains("synthetic-rating-key"))
        assertEquals("synthetic-rating-key", cipher.decrypt(value, 1))
        assertEquals("", cipher.decrypt(value, 2))
        assertEquals("", cipher.decrypt(value.dropLast(2) + "xx", 1))
        assertNotEquals(value, cipher.encrypt("synthetic-rating-key", 1))
    }

    @Test fun `legacy plaintext is atomically replaced only after encryption succeeds`() = runTest {
        val memory = TestPreferencesStore(mutablePreferencesOf(key to "legacy-ratings-key"))
        val store = store(memory)
        val settings = store.settings.first()
        assertEquals("legacy-ratings-key", settings.apiKey)
        assertTrue(cipher.isEncrypted(memory.value[key].orEmpty()))
        assertFalse(memory.value.toString().contains("legacy-ratings-key"))
        assertEquals("legacy-ratings-key", store.settings.first().apiKey)
        assertFalse(settings.toString().contains("legacy-ratings-key"))
    }

    @Test fun `failed migration retains legacy data for retry without exposing the key`() = runTest {
        val memory = TestPreferencesStore(mutablePreferencesOf(key to "legacy-ratings-key"))
        val broken = MDBListRatingCredentialCipher { error("Keystore unavailable") }
        val store = store(memory, cipher = broken)
        assertEquals("", store.settings.first().apiKey)
        assertEquals("legacy-ratings-key", memory.value[key])
        assertTrue(runCatching { store.setApiKey("replacement") }.isFailure)
        assertEquals("legacy-ratings-key", memory.value[key])
    }

    @Test fun `save and clear remain in the captured profile and preserve unrelated settings`() = runTest {
        val primary = TestPreferencesStore()
        val second = TestPreferencesStore(mutablePreferencesOf(key to cipher.encrypt("second-key", 2)))
        val active = MutableStateFlow(1)
        val store = store(primary, active, second)
        val ticket = store.captureCredentialScope()
        assertTrue(store.setApiKeyForScope(ticket, " first-key ", ""))
        assertEquals("first-key", store.credentialForProfile(1))
        assertFalse(primary.value.toString().contains("first-key"))
        assertTrue(store.setApiKeyForScope(ticket, "", "first-key"))
        assertNull(primary.value[key])
        assertEquals("second-key", store.credentialForProfile(2))
    }

    @Test fun `profile switch or replacement during validation rejects the stale commit`() = runTest {
        val primary = TestPreferencesStore()
        val active = MutableStateFlow(1)
        val store = store(primary, active)
        val ticket = store.captureCredentialScope()
        active.value = 2
        assertFalse(store.setApiKeyForScope(ticket, "late-key"))
        active.value = 1
        assertFalse(store.setApiKeyForScope(ticket, "late-key"))
        assertTrue(primary.value.asMap().isEmpty())
        val next = store.captureCredentialScope()
        assertTrue(store.setApiKeyForScope(next, "new-key"))
        assertFalse(store.setApiKeyForScope(next, "stale-key", "old-key"))
        assertEquals("new-key", store.credentialForProfile(1))
    }

    @Test fun `a copied ciphertext cannot authorize another profile and cannot leak in snapshots`() = runTest {
        val memory = TestPreferencesStore(mutablePreferencesOf(key to cipher.encrypt("profile-one-secret", 1)))
        val store = store(TestPreferencesStore(), MutableStateFlow(2), memory)
        val snapshot = store.scopedSettings.first()
        assertEquals(2, snapshot.profileId)
        assertEquals("", snapshot.settings.apiKey)
        assertFalse(snapshot.toString().contains("profile-one-secret"))
    }

    @Test fun `guard invalidates observed away and back transitions`() {
        val guard = MDBListRatingProfileGuard(1)
        val ticket = guard.capture(1)
        guard.observe(2)
        guard.observe(1)
        assertFalse(guard.valid(ticket, 1))
        assertTrue(guard.valid(guard.capture(1), 1))
    }

    @Test fun `explicit scoped clear works without a usable Keystore`() = runTest {
        val memory = TestPreferencesStore(mutablePreferencesOf(key to "legacy-ratings-key"))
        val store = store(memory, cipher = MDBListRatingCredentialCipher { error("Keystore unavailable") })
        assertTrue(store.setApiKeyForScope(store.captureCredentialScope(), ""))
        assertNull(memory.value[key])
    }

    private fun store(primary: TestPreferencesStore, active: MutableStateFlow<Int> = MutableStateFlow(1),
        second: TestPreferencesStore = TestPreferencesStore(), cipher: MDBListRatingCredentialCipher = this.cipher): MDBListSettingsDataStore {
        val factory = mockk<ProfileDataStoreFactory>(relaxed = true) {
            every { get(1, "mdblist_settings") } returns primary
            every { get(2, "mdblist_settings") } returns second
            every { isProfileDeleted(any()) } returns false
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        return MDBListSettingsDataStore(factory, profiles, cipher)
    }
}
