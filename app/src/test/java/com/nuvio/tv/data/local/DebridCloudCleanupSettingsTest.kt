package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.DebridSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridCloudCleanupSettingsTest {
    @Test
    fun `cleanup is off for old settings and each profile chooses separately`() = runBlocking {
        val activeProfile = MutableStateFlow(1)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns activeProfile }
        val stores = mapOf(1 to MemoryStore(), 2 to MemoryStore())
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "debrid_settings") } answers { stores.getValue(firstArg()) }
        }
        val cipher = mockk<DebridCredentialCipher> {
            every { decrypt(any(), any(), any()) } answers { firstArg() }
            every { isEncrypted(any()) } returns true
        }
        val store = DebridSettingsDataStore(factory, profiles, cipher)

        assertFalse(DebridSettings().cloudDeleteAfterPlayback)
        assertTrue(store.settings.first().cloudLibraryEnabled)
        assertFalse(store.settings.first().cloudDeleteAfterPlayback)
        assertFalse(store.cloudDeleteAfterPlaybackEnabledForProfile(1))
        assertFalse(store.isCloudCleanupAuthorizedForProfile(1, DebridProviders.REAL_DEBRID_ID, "fixture-key"))

        store.setCloudDeleteAfterPlayback(profileId = 1, enabled = true)
        assertTrue(store.settings.first().cloudDeleteAfterPlayback)
        assertTrue(store.isCloudCleanupAuthorizedForProfile(1, DebridProviders.REAL_DEBRID_ID, "fixture-key"))
        assertFalse(store.isCloudCleanupAuthorizedForProfile(1, DebridProviders.REAL_DEBRID_ID, "different-key"))

        activeProfile.value = 2
        assertFalse(store.settings.first().cloudDeleteAfterPlayback)
        assertTrue(store.cloudDeleteAfterPlaybackEnabledForProfile(1))
        assertFalse(store.cloudDeleteAfterPlaybackEnabledForProfile(2))
        assertFalse(store.isCloudCleanupAuthorizedForProfile(2, DebridProviders.REAL_DEBRID_ID, "fixture-key"))

        activeProfile.value = 1
        assertTrue(store.settings.first().cloudDeleteAfterPlayback)
        store.setCloudDeleteAfterPlayback(profileId = 1, enabled = false)
        assertFalse(store.settings.first().cloudDeleteAfterPlayback)
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(
            preferencesOf(
                booleanPreferencesKey("cloud_library_enabled") to true,
                stringPreferencesKey("real_debrid_api_key") to "fixture-key"
            )
        )

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
