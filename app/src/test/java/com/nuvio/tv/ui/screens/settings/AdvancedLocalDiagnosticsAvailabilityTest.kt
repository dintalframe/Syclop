package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedLocalDiagnosticsAvailabilityTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun `an absent remote service does not reset report preference or disable the local HUD setting`() = runTest {
        assertTrue(BuildConfig.PLAYBACK_REPORTS_BASE_URL.isBlank())
        val storedPlayerSettings = MutableStateFlow(PlayerSettings(playbackIssueReportsEnabled = true))
        val hudEnabled = MutableStateFlow(true)
        val layout = mockk<LayoutPreferenceDataStore> {
            every { fastHorizontalNavigationEnabled } returns flowOf(false)
            every { smoothBringIntoViewEnabled } returns flowOf(true)
            every { composeHighlighterEnabled } returns flowOf(false)
        }
        val playerStore = mockk<PlayerSettingsDataStore> {
            every { playerSettings } returns storedPlayerSettings
        }
        val deviceLocal = mockk<DeviceLocalPlayerPreferences> {
            every { playerStatsHudEnabled } returns hudEnabled
            coEvery { setPlayerStatsHudEnabled(any()) } coAnswers { hudEnabled.value = firstArg() }
        }
        val sentry = mockk<SentrySettingsDataStore> { every { enabled } returns flowOf(false) }
        val image = mockk<ImagePerformancePreferences> { every { rgb565Enabled } returns true }
        val restarter = mockk<AppRestarter>(relaxed = true)
        val viewModel = AdvancedSettingsViewModel(layout, playerStore, deviceLocal, sentry, image, restarter)
        runCurrent()
        assertTrue(viewModel.uiState.value.playerStatsHudEnabled)
        assertTrue(storedPlayerSettings.value.playbackIssueReportsEnabled)

        viewModel.onEvent(AdvancedSettingsEvent.SetPlayerStatsHudEnabled(false))
        runCurrent()

        assertFalse(viewModel.uiState.value.playerStatsHudEnabled)
        assertTrue(storedPlayerSettings.value.playbackIssueReportsEnabled)
        coVerify(exactly = 1) { deviceLocal.setPlayerStatsHudEnabled(false) }
        coVerify(exactly = 0) { playerStore.setPlaybackIssueReportsEnabled(any()) }
        verify(exactly = 0) { restarter.restart() }
    }
}
