package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.RealDebridOAuthState
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class DebridCredentialStorageTest {
    private val cipher = DebridCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }

    @Test
    fun `credentials are authenticated encrypted and bound to profile and service`() {
        val secret = "this-is-a-private-account-token"
        val encoded = cipher.encrypt(secret, 1, "realdebrid")
        assertFalse(encoded.contains(secret))
        assertTrue(cipher.isEncrypted(encoded))
        assertEquals(secret, cipher.decrypt(encoded, 1, "realdebrid"))
        assertEquals("", cipher.decrypt(encoded, 2, "realdebrid"))
        assertEquals("", cipher.decrypt(encoded, 1, "alldebrid"))
        assertNotEquals(encoded, cipher.encrypt(secret, 1, "realdebrid"))
        assertEquals("", cipher.decrypt(encoded.dropLast(2) + "xx", 1, "realdebrid"))
    }

    @Test
    fun `legacy credentials migrate without losing Torbox Premiumize or Real Debrid`() = runTest {
        val torbox = stringPreferencesKey("torbox_api_key")
        val premiumize = stringPreferencesKey("premiumize_api_key")
        val realDebrid = stringPreferencesKey("real_debrid_api_key")
        val memory = TestPreferencesStore(mutablePreferencesOf(torbox to "tb-token", premiumize to "pm-token", realDebrid to "rd-token"))
        val store = dataStore(memory)
        val settings = store.settings.first()
        assertEquals("tb-token", settings.torboxApiKey)
        assertEquals("pm-token", settings.premiumizeApiKey)
        assertEquals("rd-token", settings.realDebridApiKey)
        val saved = memory.data.first()
        listOf(torbox, premiumize, realDebrid).forEach { assertTrue(cipher.isEncrypted(saved[it].orEmpty())) }
        assertFalse(saved.toString().contains("rd-token"))
        assertEquals("rd-token", store.settings.first().realDebridApiKey)
    }

    @Test
    fun `manual AllDebrid key save is encrypted and clearing does not remove another account`() = runTest {
        val memory = TestPreferencesStore()
        val store = dataStore(memory)
        store.setProviderApiKey(DebridProviders.REAL_DEBRID_ID, "rd-token")
        store.setProviderApiKey(DebridProviders.ALL_DEBRID_ID, "ad-token")
        store.setEnabled(true)
        assertEquals("ad-token", store.settings.first().allDebridApiKey)
        assertFalse(memory.data.first().toString().contains("ad-token"))
        store.setProviderApiKey(DebridProviders.ALL_DEBRID_ID, "")
        val settings = store.settings.first()
        assertEquals("rd-token", settings.realDebridApiKey)
        assertEquals("", settings.allDebridApiKey)
        assertTrue(settings.enabled)
        store.setProviderApiKey(DebridProviders.REAL_DEBRID_ID, "")
        assertFalse(store.settings.first().enabled)
    }

    @Test
    fun `OAuth client secret and rotated credentials are encrypted together and manual replacement clears them`() = runTest {
        val memory = TestPreferencesStore()
        val store = dataStore(memory)
        val state = RealDebridOAuthState("user-client", "user-client-secret", "access-secret", "refresh-secret", 50_000L)
        assertTrue(store.setProviderApiKeyForProfile(1, DebridProviders.REAL_DEBRID_ID, state.accessToken, "", state))
        assertEquals(state, store.realDebridOAuth(1))
        val persisted = memory.value.toString()
        listOf(state.clientId, state.clientSecret, state.accessToken, state.refreshToken).forEach { assertFalse(persisted.contains(it)) }
        assertFalse(store.setProviderApiKeyForProfile(1, DebridProviders.REAL_DEBRID_ID, "late-key", "wrong-old-key"))
        assertEquals(state, store.realDebridOAuth(1))
        store.setProviderApiKey(DebridProviders.REAL_DEBRID_ID, "manual-new")
        assertNull(store.realDebridOAuth(1))
        assertEquals("manual-new", store.settings.first().realDebridApiKey)
    }

    @Test
    fun `late validation cannot save into a switched profile`() = runTest {
        val memory = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(1, "debrid_settings") } returns memory
        val profiles = mockk<ProfileManager>()
        val active = MutableStateFlow(1)
        every { profiles.activeProfileId } returns active
        val store = DebridSettingsDataStore(factory, profiles, cipher)
        active.value = 2
        assertFalse(store.setProviderApiKeyForProfile(1, DebridProviders.ALL_DEBRID_ID, "late-key"))
        assertTrue(memory.value.asMap().isEmpty())
    }

    @Test
    fun `keystore failure never falls back to saving plaintext`() = runTest {
        val memory = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(1, "debrid_settings") } returns memory
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val brokenCipher = DebridCredentialCipher { throw IllegalStateException("Keystore unavailable") }
        val store = DebridSettingsDataStore(factory, profiles, brokenCipher)
        try {
            store.setProviderApiKey(DebridProviders.ALL_DEBRID_ID, "do-not-save-this")
            fail("Expected secure storage failure")
        } catch (_: IllegalStateException) {
            assertFalse(memory.value.toString().contains("do-not-save-this"))
            assertTrue(memory.value.asMap().isEmpty())
        }
    }

    private fun dataStore(memory: TestPreferencesStore): DebridSettingsDataStore {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(1, "debrid_settings") } returns memory
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        return DebridSettingsDataStore(factory, profiles, cipher)
    }
}
