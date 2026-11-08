package com.nuvio.tv.updater

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.updater.model.AppUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

    private fun updaterTest(
        enabled: Boolean = true,
        channel: UpdateChannel = UpdateChannel.STABLE,
        lastCheckAt: Long = 0L,
        block: suspend Fixture.() -> Unit
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this, enabled, channel, lastCheckAt)
        try {
            fixture.block()
        } finally {
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
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
        private val downloads = mockk<UpdateDownloadStore>()
        private var model: UpdateViewModel? = null
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
            coEvery { downloads.clearIfInstalled() } returns Unit
            coEvery { repository.getLatestUpdate(any()) } returns Result.success(offer)
        }

        suspend fun open(): UpdateViewModel {
            val viewModel = UpdateViewModel(mockk(), repository, preferences, downloads)
            model = viewModel
            // Startup has an IO cleanup. Join the constructor's already scheduled work
            // before inspecting network calls on the test dispatcher.
            viewModel.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
            runCurrent()
            return viewModel
        }

        fun runCurrent() = scope.runCurrent()

        fun close() {
            model?.viewModelScope?.cancel()
        }
    }
}
