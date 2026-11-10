package com.nuvio.tv.core.setup

import org.junit.Assert.*
import org.junit.Test
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.nuvio.tv.TestPreferencesStore
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class EssentialSetupSessionTest {
    @Test fun `remembered child waits for metadata and the effective loaded addon owner`() = runTest {
        val primary = UserProfile(1, "Primary", "#000000")
        val child = UserProfile(2, "Child", "#111111", usesPrimaryAddons = true)
        val metadata = MutableStateFlow(listOf(primary))
        val preferences = inheritedPreferences(metadata)
        val initial = preferences.installedAddonConfiguration.first()
        assertEquals(2, initial.ownerProfileId)
        assertFalse(firstWatchAddonConfigurationReady(metadata.value.find { it.id == 2 }, initial))
        metadata.value = listOf(primary, child)
        assertFalse("the earlier child-owned empty emission is not the inherited configuration",
            firstWatchAddonConfigurationReady(child, initial))
        val inherited = withTimeout(5_000L) { preferences.installedAddonConfiguration.first { it.ownerProfileId == 1 } }
        assertTrue(firstWatchAddonConfigurationReady(child, inherited))
        assertFalse(shouldShowFirstWatchSetup(true, inherited.hasAddons, skipped = false, alreadyShown = false,
            configurationLoaded = firstWatchAddonConfigurationReady(child, inherited)))
    }

    @Test fun `shared addon configuration retains primary owner rather than active child id`() = runTest {
        val primary = UserProfile(1, "Primary", "#000000")
        val child = UserProfile(2, "Child", "#111111", usesPrimaryAddons = true)
        val installed = inheritedPreferences(MutableStateFlow(listOf(primary, child))).installedAddonConfiguration.first()
        assertEquals(1, installed.ownerProfileId)
        assertTrue(installed.hasAddons)
        assertTrue(firstWatchAddonConfigurationReady(child, installed))
    }

    private fun inheritedPreferences(metadata: MutableStateFlow<List<UserProfile>>): AddonPreferences {
        val primary = TestPreferencesStore(preferencesOf(stringPreferencesKey("installed_addon_urls_ordered") to "[\"https://primary.example\"]"))
        val child = TestPreferencesStore(preferencesOf(stringPreferencesKey("installed_addon_urls_ordered") to "[]"))
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "addon_preferences") } returns primary
            every { get(2, "addon_preferences") } returns child
        }
        val manager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns MutableStateFlow(2)
            every { profiles } returns metadata
        }
        return AddonPreferences(factory, manager)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `delayed persisted configured addons never latch a repository initial empty list`() = runTest {
        val release = CompletableDeferred<Unit>()
        val saved = preferencesOf(stringPreferencesKey("installed_addon_urls_ordered") to "[\"https://saved.example\"]")
        val memory = object : DataStore<Preferences> {
            override val data = flow { release.await(); emit(saved) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Read only")
        }
        val factory = mockk<ProfileDataStoreFactory> { every { get(1, "addon_preferences") } returns memory }
        val manager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns MutableStateFlow(1)
            every { profiles } returns MutableStateFlow(emptyList<UserProfile>())
        }
        val preferences = AddonPreferences(factory, manager)
        var configured: List<String>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            preferences.installedAddonUrls.collect { configured = it }
        }
        assertFalse(shouldShowFirstWatchSetup(true, hasAddons = false, skipped = false, alreadyShown = false,
            configurationLoaded = configured != null))
        release.complete(Unit); runCurrent()
        assertEquals(listOf("https://saved.example"), configured)
        assertFalse(shouldShowFirstWatchSetup(true, hasAddons = configured!!.isNotEmpty(), skipped = false,
            alreadyShown = false, configurationLoaded = true))
    }
    @Test fun `fresh essential profile enters setup and stays after first addon installs`() {
        assertTrue(shouldShowFirstWatchSetup(isEssential = true, hasAddons = false, skipped = false, alreadyShown = false))
        assertTrue(shouldShowFirstWatchSetup(isEssential = true, hasAddons = true, skipped = false, alreadyShown = true))
    }

    @Test fun `legacy configured profile is not forced back into setup`() {
        assertFalse(shouldShowFirstWatchSetup(isEssential = true, hasAddons = true, skipped = false, alreadyShown = false))
    }

    @Test fun `continue or skip exits an already shown setup and advanced mode never enters`() {
        assertFalse(shouldShowFirstWatchSetup(isEssential = true, hasAddons = true, skipped = true, alreadyShown = true))
        assertFalse(shouldShowFirstWatchSetup(isEssential = true, hasAddons = false, skipped = true, alreadyShown = true))
        assertFalse(shouldShowFirstWatchSetup(isEssential = false, hasAddons = false, skipped = false, alreadyShown = true))
    }
}
