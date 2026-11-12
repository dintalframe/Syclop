package com.nuvio.tv.updater

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.updater.model.AppUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelSchedulingTest {
    @Test fun `initialization loads preferences without a release request until explicitly triggered`() =
        updaterTest(channel = UpdateChannel.BETA) {
            val viewModel = open()
            assertTrue(viewModel.uiState.value.updateBannerEnabled)
            assertEquals(UpdateChannel.BETA, viewModel.uiState.value.updateChannel)
            coVerify(exactly = 0) { repository.getLatestUpdate(any()) }

            viewModel.checkForUpdates(force = false, showNoUpdateFeedback = false)
            runCurrent()

            coVerify(exactly = 1) { repository.getLatestUpdate(UpdateChannel.BETA) }
            assertTrue(viewModel.uiState.value.showBanner)
        }

    @Test fun `automatic request cannot cancel an in-flight manual request`() =
        updaterTest(lastCheckAt = System.currentTimeMillis()) {
            val viewModel = open()
            lastCheckAt.value = 0L
            val response = CompletableDeferred<AppUpdate>()
            var manualCancelled = false
            coEvery { repository.getLatestUpdate(UpdateChannel.STABLE) } coAnswers {
                try {
                    Result.success(response.await())
                } catch (cancelled: CancellationException) {
                    manualCancelled = true
                    throw cancelled
                }
            }

            viewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
            runCurrent()
            assertTrue(viewModel.uiState.value.isChecking)
            viewModel.checkForUpdates(force = false, showNoUpdateFeedback = false)
            runCurrent()

            assertFalse("The manual request was cancelled by an automatic request", manualCancelled)
            coVerify(exactly = 1) { repository.getLatestUpdate(UpdateChannel.STABLE) }
            response.complete(offer)
            runCurrent()
            assertEquals(offer, viewModel.uiState.value.update)
            assertTrue(viewModel.uiState.value.manualPrompt)
        }

    @Test fun `automatic request leaves a visible manual prompt intact even when cooldown is clear`() =
        updaterTest(lastCheckAt = System.currentTimeMillis()) {
            val viewModel = open()
            viewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
            runCurrent()
            assertTrue(viewModel.uiState.value.showBanner)
            assertTrue(viewModel.uiState.value.manualPrompt)
            lastCheckAt.value = 0L

            viewModel.checkForUpdates(force = false, showNoUpdateFeedback = false)
            runCurrent()

            coVerify(exactly = 1) { repository.getLatestUpdate(UpdateChannel.STABLE) }
            assertEquals(offer, viewModel.uiState.value.update)
            assertTrue(viewModel.uiState.value.showBanner)
            assertTrue(viewModel.uiState.value.manualPrompt)
        }

    @Test fun `persisted opt-out suppresses automatic checks`() = updaterTest(enabled = false) {
        val viewModel = open()
        assertFalse(viewModel.uiState.value.updateBannerEnabled)

        viewModel.checkForUpdates(force = false, showNoUpdateFeedback = false)
        runCurrent()

        coVerify(exactly = 0) { repository.getLatestUpdate(any()) }
        assertFalse(viewModel.uiState.value.showBanner)
    }

    @Test fun `persisted cooldown suppresses automatic checks until a new check is due`() =
        updaterTest(lastCheckAt = System.currentTimeMillis()) {
            val viewModel = open()
            viewModel.checkForUpdates(force = false, showNoUpdateFeedback = false)
            runCurrent()
            coVerify(exactly = 0) { repository.getLatestUpdate(any()) }

            lastCheckAt.value = 0L
            viewModel.checkForUpdates(force = false, showNoUpdateFeedback = false)
            runCurrent()

            coVerify(exactly = 1) { repository.getLatestUpdate(UpdateChannel.STABLE) }
            assertTrue("The completed check should persist its time", lastCheckAt.value > 0L)
            assertTrue(viewModel.uiState.value.showBanner)
        }

    @Test fun `a completed download opens the installer once when the user returns`() = updaterTest {
        val viewModel = openOffer()
        val oldActivity = mockk<ComponentActivity>()
        every { oldActivity.lifecycle.currentState } returns Lifecycle.State.STARTED

        viewModel.downloadUpdate()
        awaitWork()
        assertEquals(apk, viewModel.uiState.value.pendingInstall)
        viewModel.launchPendingInstaller(oldActivity)
        verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }

        val resumedActivity = mockk<ComponentActivity>()
        every { resumedActivity.lifecycle.currentState } returns Lifecycle.State.RESUMED
        viewModel.launchPendingInstaller(resumedActivity)
        viewModel.launchPendingInstaller(resumedActivity)

        verify(exactly = 1) { ApkInstaller.launchSystemInstaller(resumedActivity, apk) }
        coVerify(exactly = 1) { downloads.download(offer, any()) }
        assertFalse(viewModel.uiState.value.showBanner)
    }

    @Test fun `granting the unknown sources permission continues the original update once`() = updaterTest {
        val viewModel = openOffer()
        installAllowed = false
        viewModel.downloadUpdate()
        assertTrue(viewModel.uiState.value.showUnknownSourcesDialog)
        viewModel.openUnknownSourcesSettings { assertEquals(unknownSourcesIntent, it) }
        awaitWork()
        coVerify(exactly = 0) { downloads.download(any(), any()) }

        installAllowed = true
        viewModel.onUnknownSourcesSettingsReturned()
        viewModel.onUnknownSourcesSettingsReturned()
        awaitWork()

        assertEquals(apk, viewModel.uiState.value.pendingInstall)
        coVerify(exactly = 1) { downloads.download(offer, any()) }
    }

    @Test fun `returning without the unknown sources permission leaves the offer available`() = updaterTest {
        val viewModel = openOffer()
        installAllowed = false
        viewModel.downloadUpdate()
        viewModel.openUnknownSourcesSettings {}
        viewModel.onUnknownSourcesSettingsReturned()
        awaitWork()

        assertTrue(viewModel.uiState.value.showBanner)
        assertFalse(viewModel.uiState.value.showUnknownSourcesDialog)
        coVerify(exactly = 0) { downloads.download(any(), any()) }
    }

    @Test fun `dismissing the offer cancels a verified pending install`() = updaterTest {
        val viewModel = openOffer()
        viewModel.downloadUpdate()
        awaitWork()
        assertNotNull(viewModel.uiState.value.pendingInstall)

        viewModel.dismissBanner()
        val resumedActivity = mockk<ComponentActivity>()
        every { resumedActivity.lifecycle.currentState } returns Lifecycle.State.RESUMED
        viewModel.launchPendingInstaller(resumedActivity)
        awaitWork()

        verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }
        assertFalse(viewModel.uiState.value.showBanner)
    }

    private fun updaterTest(
        enabled: Boolean = true,
        channel: UpdateChannel = UpdateChannel.STABLE,
        lastCheckAt: Long = 0L,
        block: suspend Fixture.() -> Unit
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ApkInstaller)
        val fixture = Fixture(this, enabled, channel, lastCheckAt)
        try {
            fixture.block()
        } finally {
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
            unmockkObject(ApkInstaller)
        }
    }

    private class Fixture(
        private val scope: TestScope,
        enabled: Boolean,
        channel: UpdateChannel,
        initialLastCheckAt: Long
    ) {
        val lastCheckAt = MutableStateFlow(initialLastCheckAt)
        val repository = mockk<UpdateRepository>()
        private val preferences = mockk<UpdatePreferences>()
        val downloads = mockk<UpdateDownloadStore>()
        private var model: UpdateViewModel? = null
        val apk = File("verified.apk")
        val unknownSourcesIntent = mockk<Intent>()
        var installAllowed = true
        val offer = AppUpdate(
            tag = "v9.0.0", title = "Fixture release", notes = "", releaseUrl = null,
            assetName = "syclop-release.apk", assetUrl = "https://example.invalid/fixture.apk",
            assetSizeBytes = 1L, assetSha256 = "a".repeat(64)
        )

        init {
            every { preferences.updateBannerEnabled } returns flowOf(enabled)
            every { preferences.updateChannel } returns flowOf(channel)
            every { preferences.lastCheckAtMs } returns lastCheckAt
            every { preferences.deferredRelease } returns flowOf(null)
            coEvery { preferences.setLastCheckAtMs(any()) } coAnswers { lastCheckAt.value = firstArg() }
            coEvery { preferences.deferRelease(any(), any()) } returns Unit
            coEvery { downloads.clearIfInstalled() } returns Unit
            coEvery { downloads.download(offer, any()) } returns Result.success(apk)
            every { ApkInstaller.canRequestPackageInstalls(any()) } answers { installAllowed }
            every { ApkInstaller.buildUnknownSourcesSettingsIntent(any()) } returns unknownSourcesIntent
            every { ApkInstaller.launchSystemInstaller(any(), any()) } returns Unit
            coEvery { repository.getLatestUpdate(any()) } returns Result.success(offer)
        }

        suspend fun open(): UpdateViewModel {
            val viewModel = UpdateViewModel(mockk(), repository, preferences, downloads)
            model = viewModel
            // Startup has an IO cleanup. Join the constructor's already scheduled work
            // before inspecting network calls on the test dispatcher.
            awaitWork()
            return viewModel
        }

        suspend fun openOffer(): UpdateViewModel = open().also { viewModel ->
            viewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
            runCurrent()
            assertTrue(viewModel.uiState.value.showBanner)
        }

        suspend fun awaitWork() {
            runCurrent()
            model!!.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
            runCurrent()
        }

        fun runCurrent() = scope.runCurrent()

        fun close() {
            model?.viewModelScope?.cancel()
        }
    }
}
