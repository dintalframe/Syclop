package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FirstWatchConfigurationMutationTest {
    @Test fun `source away and back rotates atomically without a flow observer and survives recreation`() = runTest {
        val h = Harness()
        val addons = h.addons()
        addons.addAddon("https://owned.test")
        val before = addons.firstWatchSourceRevision.first()
        addons.setAddonEnabled("https://owned.test", false)
        addons.setAddonEnabled("https://owned.test", true)
        val after = h.addons().firstWatchSourceRevision.first()
        assertNotEquals(before, after)
        assertEquals(true, h.addons().addonEnabledStates.first()["https://owned.test"])
        addons.setAddonEnabled("https://owned.test", true)
        assertEquals(after, h.addons().firstWatchSourceRevision.first())
        h.active.value = 2
        assertEquals("", h.addons().firstWatchSourceRevision.first())
        h.active.value = 1
        assertEquals(after, h.addons().firstWatchSourceRevision.first())
    }

    @Test fun `engine away and back invalidates without a flow observer but identical selection does not`() = runTest {
        val h = Harness()
        val player = h.player()
        val before = player.firstWatchEngineRevision.first()
        player.setInternalPlayerEngine(InternalPlayerEngine.MVP_PLAYER)
        player.setInternalPlayerEngine(InternalPlayerEngine.EXOPLAYER)
        val after = h.player().firstWatchEngineRevision.first()
        assertNotEquals(before, after)
        player.setInternalPlayerEngine(InternalPlayerEngine.EXOPLAYER)
        assertEquals(after, h.player().firstWatchEngineRevision.first())
        player.setPlayerPreference(PlayerPreference.EXTERNAL)
        player.setPlayerPreference(PlayerPreference.INTERNAL)
        assertNotEquals(after, h.player().firstWatchEngineRevision.first())
        h.active.value = 2
        assertEquals("", h.player().firstWatchEngineRevision.first())
    }

    private class Harness {
        val active = MutableStateFlow(1)
        private val memories = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers {
                memories.getOrPut(firstArg<Int>() to secondArg<String>()) {
                    TestPreferencesStore(mutablePreferencesOf(stringPreferencesKey("subtitle_preferred_language") to "en"))
                }
            }
        }
        private val manager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns active
            every { activeProfile } returns null
            every { profiles } returns MutableStateFlow(emptyList<UserProfile>())
        }
        fun addons() = AddonPreferences(factory, manager)
        fun player() = PlayerSettingsDataStore(factory, manager, mockk<Context>(relaxed = true))
    }
}
