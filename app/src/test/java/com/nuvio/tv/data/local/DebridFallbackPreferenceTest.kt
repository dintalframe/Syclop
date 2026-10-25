package com.nuvio.tv.data.local

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

class DebridFallbackPreferenceTest {
    @Test fun `approved fallback default is enabled without requiring a saved setting`() = runTest {
        val h = Harness()
        assertTrue(h.store().settings.first().connectedServiceFallbackEnabled)
        assertTrue(h.memory.getValue(1).value.asMap().keys.none { it.name == "connected_service_fallback_enabled" })
    }
    @Test fun `explicit off persists across recreation and does not change preferred service or credentials`() = runTest {
        val h = Harness()
        val store = h.store()
        store.setProviderApiKey("alldebrid", "synthetic-ad")
        store.setProviderApiKey("realdebrid", "synthetic-rd")
        store.setPreferredResolverProviderId("alldebrid")
        store.setConnectedServiceFallbackEnabled(false)
        val restored = h.store().settings.first()
        assertFalse(restored.connectedServiceFallbackEnabled)
        assertEquals("alldebrid", restored.activeResolverProviderId)
        assertEquals("synthetic-ad", restored.allDebridApiKey)
        assertEquals("synthetic-rd", restored.realDebridApiKey)
    }
    @Test fun `one profile can opt out without changing another profile default`() = runTest {
        val h = Harness()
        val store = h.store()
        store.setConnectedServiceFallbackEnabled(false)
        h.active.value = 2
        assertTrue(store.settings.first().connectedServiceFallbackEnabled)
        h.active.value = 1
        assertFalse(store.settings.first().connectedServiceFallbackEnabled)
    }
    private class Harness {
        val active = MutableStateFlow(1)
        val memory = mapOf(1 to TestPreferencesStore(), 2 to TestPreferencesStore())
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "debrid_settings") } answers { memory.getValue(firstArg()) }
        }
        private val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns active
            every { activeProfileSelectionRevision } returns 0L
        }
        fun store() = DebridSettingsDataStore(factory, profiles,
            DebridCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
    }
}
