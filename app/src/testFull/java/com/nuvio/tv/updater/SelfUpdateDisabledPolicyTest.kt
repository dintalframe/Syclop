package com.nuvio.tv.updater

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SelfUpdateDisabledPolicyTest {
    @Test fun `retiring legacy jobs cancels only app updater IDs and leaves provider and channel work alone`() {
        val scheduler = mockk<JobScheduler>(relaxed = true)
        val context = mockk<Context> {
            every { getSystemService(Context.JOB_SCHEDULER_SERVICE) } returns scheduler
        }
        assertFalse(BuildConfig.FEATURE_IN_APP_UPDATES_ENABLED)
        assertFalse(AppFeaturePolicy.inAppUpdatesEnabled)
        assertTrue(SelfUpdatePolicy.cancelIfDisabled(context))
        verify(exactly = 1) { scheduler.cancel(10_020); scheduler.cancel(10_021); scheduler.cancel(10_022) }
        confirmVerified(scheduler)
    }

    @Test fun `all scheduler entrypoints cancel old work instead of scheduling a new check or install`() {
        val scheduler = mockk<JobScheduler>(relaxed = true)
        val context = mockk<Context> {
            every { getSystemService(Context.JOB_SCHEDULER_SERVICE) } returns scheduler
        }
        UpdateJobService.schedulePeriodicCheck(context)
        UpdateJobService.scheduleCheckNow(context)
        UpdateJobService.scheduleIdleInstall(context)
        assertFalse(UpdateJobService.canInstallSilently(context))
        verify(exactly = 3) { scheduler.cancel(10_020); scheduler.cancel(10_021); scheduler.cancel(10_022) }
        confirmVerified(scheduler)
    }

    @Test fun `persisted service job is rejected before Hilt and never asks Android to retry`() {
        val scheduler = mockk<JobScheduler>(relaxed = true)
        val service = spyk(UpdateJobService())
        every { service.getSystemService(Context.JOB_SCHEDULER_SERVICE) } returns scheduler
        val params = mockk<JobParameters>()
        assertFalse(service.onStartJob(params))
        assertFalse(service.onStopJob(params))
        verify(exactly = 2) { scheduler.cancel(10_020); scheduler.cancel(10_021); scheduler.cancel(10_022) }
        verify(exactly = 0) { service.applicationContext }
        confirmVerified(scheduler)
        confirmVerified(params)
    }

    @Test fun `direct repository calls cannot make GitHub requests even when forced by a caller`() = runTest {
        val api = mockk<GitHubReleaseApi>()
        val repository = UpdateRepository(api)
        for (channel in UpdateChannel.entries) {
            val result = repository.getLatestUpdate(channel)
            assertTrue(result.isFailure)
            assertEquals("Self-updates are disabled in Syclop.", result.exceptionOrNull()?.message)
        }
        confirmVerified(api)
    }

    @Test fun `viewmodel startup and manual actions touch neither saved preferences nor backend or APK store`() {
        val scheduler = mockk<JobScheduler>(relaxed = true)
        val context = mockk<Context> {
            every { getSystemService(Context.JOB_SCHEDULER_SERVICE) } returns scheduler
        }
        val repository = mockk<UpdateRepository>()
        val preferences = mockk<UpdatePreferences>()
        val downloads = mockk<UpdateDownloadStore>()
        val viewModel = UpdateViewModel(context, repository, preferences, downloads)
        viewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
        viewModel.setUpdateBannerEnabled(true)
        viewModel.setUpdateChannel(UpdateChannel.BETA)
        viewModel.setAutoInstallEnabled(true)
        viewModel.setAutoInstallNoticeEnabled(true)
        viewModel.downloadUpdate()
        viewModel.installUpdateOrRequestPermission()
        viewModel.openUnknownSourcesSettings()
        assertFalse(viewModel.uiState.value.updateBannerEnabled)
        assertFalse(viewModel.uiState.value.autoInstallSupported)
        assertFalse(viewModel.uiState.value.isChecking)
        assertFalse(viewModel.uiState.value.isDownloading)
        assertFalse(viewModel.uiState.value.showBanner)
        confirmVerified(repository, preferences, downloads)
    }
}
