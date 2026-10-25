package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AddonProfileCatalogSnapshotTest {
    @Test fun `explicit profile snapshot reads URL order and enabled choices together without writes`() = runTest {
        val contents = preferencesOf(
            stringPreferencesKey("installed_addon_urls_ordered") to "[\"https://b.test/One?config=X\",\"https://b.test/Two?config=Y\"]",
            stringPreferencesKey("installed_addon_enabled_states") to "{\"https://b.test/Two?config=Y\":false}")
        val memory = readOnly(contents)
        val factory = mockk<ProfileDataStoreFactory> { every { get(2, "addon_preferences") } returns memory }
        val profiles = mockk<ProfileManager>(relaxed = true) { every { activeProfileId } returns MutableStateFlow(1) }
        val preferences = AddonPreferences(factory, profiles)
        assertEquals(listOf("https://b.test/One?config=X"), preferences.enabledUrlsForProfile(2))
        verify(exactly = 1) { factory.get(2, "addon_preferences") }
        verify(exactly = 0) { factory.get(1, any()) }
    }
    @Test fun `case-sensitive configured URLs never inherit another URL disabled state`() = runTest {
        val contents = preferencesOf(
            stringPreferencesKey("installed_addon_urls_ordered") to "[\"https://b.test/Case?config=X\",\"https://b.test/case?config=x\"]",
            stringPreferencesKey("installed_addon_enabled_states") to "{\"https://b.test/Case?config=X\":false}")
        val factory = mockk<ProfileDataStoreFactory> { every { get(2, "addon_preferences") } returns readOnly(contents) }
        assertEquals(listOf("https://b.test/case?config=x"), AddonPreferences(factory, mockk(relaxed = true)).enabledUrlsForProfile(2))
    }
    private fun readOnly(value: Preferences) = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(value)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Read-only snapshot must not edit preferences")
    }
}
