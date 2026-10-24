package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class TraktCredentialStorageTest {
    private val cipher = DebridCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }

    @Test fun `legacy OAuth and device tokens migrate to authenticated ciphertext`() = runTest {
        val access = stringPreferencesKey("access_token")
        val refresh = stringPreferencesKey("refresh_token")
        val device = stringPreferencesKey("device_code")
        val memory = TestPreferencesStore(mutablePreferencesOf(access to "access-private", refresh to "refresh-private", device to "device-private"))
        val store = dataStore(memory)
        val state = store.state.first()
        assertEquals("access-private", state.accessToken)
        assertEquals("refresh-private", state.refreshToken)
        assertEquals("device-private", state.deviceCode)
        for (key in listOf(access, refresh, device)) assertTrue(cipher.isEncrypted(memory.value[key].orEmpty()))
        assertFalse(memory.value.toString().contains("private"))
        assertFalse(state.toString().contains("private"))
    }

    @Test fun `new tokens bind to initiating profile and application and clearing removes secrets`() = runTest {
        val memory = TestPreferencesStore()
        val store = dataStore(memory)
        store.saveDeviceFlow(TraktDeviceCodeResponseDto("device-private", "ABCD1234", "https://auth.trakt.tv/activate", 600, 5), 1, "syclop-client")
        store.saveToken(TraktTokenResponseDto("access-private", "bearer", 604800, "refresh-private", "public", 1), 1, "syclop-client")
        assertEquals("syclop-client", store.getCurrentState().registrationClientId)
        assertTrue(store.getCurrentState().isAuthenticated)
        assertFalse(memory.value.toString().contains("private"))
        val encryptedAccess = memory.value[stringPreferencesKey("access_token")].orEmpty()
        assertEquals("", cipher.decrypt(encryptedAccess, 2, "trakt_access_token"))
        assertEquals("", cipher.decrypt(encryptedAccess, 1, "trakt_refresh_token"))
        store.clearAuthForProfile(1)
        assertFalse(store.getCurrentState().isAuthenticated)
        assertNull(store.getCurrentState().deviceCode)
    }

    @Test fun `keystore failure never stores plaintext OAuth credentials`() = runTest {
        val memory = TestPreferencesStore()
        val store = dataStore(memory, DebridCredentialCipher { error("Keystore unavailable") })
        try {
            store.saveToken(TraktTokenResponseDto("access-private", "bearer", 604800, "refresh-private", null, 1), 1, "syclop-client")
            fail("Expected secure storage failure")
        } catch (_: IllegalStateException) {
            assertTrue(memory.value.asMap().isEmpty())
        }
    }

    private fun dataStore(memory: TestPreferencesStore, selectedCipher: DebridCredentialCipher = cipher): TraktAuthDataStore {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(1, "trakt_auth_store") } returns memory
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        return TraktAuthDataStore(factory, profiles, selectedCipher)
    }
}
