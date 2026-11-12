package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonPreferencesOrderTest {
    @Test
    fun `phone reorder preserves enabled state and cannot restore a removed add-on`() = runBlocking {
        val entries = MutableStateFlow(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = entries
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(entries.value).also { entries.value = it }
        }
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "addon_preferences") } returns store
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfile } returns null
            every { activeProfileId } returns MutableStateFlow(1)
            every { profiles } returns MutableStateFlow(emptyList())
        }
        val preferences = AddonPreferences(factory, profileManager)
        val first = "https://example.test/private-link"
        val second = "https://second.example/provider"
        val original = listOf(first, second)
        val reordered = listOf(second, first)
        assertTrue(preferences.setAddonOrder(original))
        assertTrue(preferences.setAddonEnabled(first, false))
        assertTrue(preferences.setExistingAddonOrder(reordered, original, 1) { true })
        assertEquals(reordered, preferences.installedAddonUrls.first())
        assertEquals(false, preferences.addonEnabledStates.first()[first])

        assertTrue(preferences.removeAddon(first))
        assertFalse(preferences.setExistingAddonOrder(reordered, reordered, 1) { true })
        assertFalse(preferences.setExistingAddonOrder(reordered, listOf(second), 1) { true })
        assertFalse(preferences.setExistingAddonOrder(emptyList(), listOf(second), 1) { false })
        assertEquals(listOf(second), preferences.installedAddonUrls.first())
    }
}
