package com.nuvio.tv.ui.screens.profile

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDeletionRecoveryTest {
    @Before
    fun prepareMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun failedSecureDeletionRetainsTheProfileAndNotifiesTheErrorUi() = runTest {
        val entries = MutableStateFlow(listOf(UserProfile(1, "Primary", "#FFFFFF"), UserProfile(2, "Viewer", "#FFFFFF")))
        val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
            every { this@mockk.profiles } returns entries
        }
        coEvery { profiles.deleteProfile(2) } throws IOException("Fixture key unavailable")
        val sync = mockk<ProfileSyncService>(relaxed = true)
        coEvery { sync.pullProfileLockStates() } returns Result.success(emptyMap())
        val backgrounds = mockk<ProfileBackgroundRepository>(relaxed = true) {
            every { catalog } returns MutableStateFlow(emptyList())
        }
        val access = mockk<MemberAccessRepository> {
            every { this@mockk.access } returns MutableStateFlow(MemberAccess.None)
        }
        val locks = mockk<ProfileLockStateDataStore>(relaxed = true) {
            every { pinEnabled } returns flowOf(emptyMap())
        }
        val themes = mockk<ThemeDataStore> {
            every { observeThemeForProfile(any()) } returns flowOf(null)
        }
        val viewModel = ProfileSelectionViewModel(profiles, sync, mockk<ProfileSettingsSyncService>(),
            mockk<AvatarRepository>(relaxed = true), backgrounds, access, locks, themes)
        val notifications = mutableListOf<Boolean>()
        try {
            viewModel.deleteProfile(2, notifications::add)
            assertTrue(entries.value.any { it.id == 2 })
            assertEquals(listOf(false), notifications)
            coVerify(exactly = 0) { sync.deleteProfileData(any()) }
            coVerify(exactly = 0) { sync.pushToRemote() }
            viewModel.deleteProfile(2, notifications::add)
            assertEquals(listOf(false, false), notifications)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
