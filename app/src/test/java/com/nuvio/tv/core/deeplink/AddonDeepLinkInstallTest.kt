package com.nuvio.tv.core.deeplink

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonDeepLinkInstallTest {
    @Test
    fun confirmationShowsHostWithoutIncludingThePrivatePathOrQuery() {
        val request = requireNotNull(AddonLinkInstallRequest.create(url, 1, 7L))
        assertEquals("provider.example", request.host)
        assertEquals(url, request.manifestUrl)
        assertFalse(request.toString().contains("fixture-secret"))
        assertNull(AddonLinkInstallRequest.create("https://trusted.example@other.example/manifest.json", 1, 7L))
    }

    @Test
    fun staleSelectionCannotFetchOrInstall() = runTest {
        val fixture = Fixture()
        val request = requireNotNull(AddonLinkInstallRequest.create(url, 1, 7L))
        fixture.profileId.value = 2
        assertNull(fixture.handler.installAddon(request))
        coVerify(exactly = 0) { fixture.addons.fetchAddon(any()) }
        coVerify(exactly = 0) { fixture.addons.addAddonIfCurrent(any(), any(), any(), any()) }
    }

    @Test
    fun profileReselectionWhileFetchingCannotInstall() = runTest {
        val fixture = Fixture()
        val started = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        coEvery { fixture.addons.fetchAddon(url) } coAnswers {
            started.complete(Unit)
            respond.await()
            NetworkResult.Success(mockk())
        }
        val request = requireNotNull(AddonLinkInstallRequest.create(url, 1, 7L))
        val result = async { fixture.handler.installAddon(request) }
        started.await()
        fixture.revision = 8L
        respond.complete(Unit)
        assertNull(result.await())
        coVerify(exactly = 0) { fixture.addons.addAddonIfCurrent(any(), any(), any(), any()) }
    }

    @Test
    fun confirmedLinkInstallsOnlyForItsCapturedSelection() = runTest {
        val fixture = Fixture()
        coEvery { fixture.addons.fetchAddon(url) } returns NetworkResult.Success(mockk())
        coEvery { fixture.addons.addAddonIfCurrent(url, 1, 7L, any()) } coAnswers {
            assertTrue(arg<() -> Boolean>(3).invoke())
            true
        }
        every { fixture.context.getString(R.string.addon_install_success, "provider.example") } returns "Added"
        val request = requireNotNull(AddonLinkInstallRequest.create(url, 1, 7L))
        assertEquals(DeepLinkInstallResult.Success("Added"), fixture.handler.installAddon(request))
        coVerify(exactly = 1) { fixture.addons.addAddonIfCurrent(url, 1, 7L, any()) }
    }

    private class Fixture {
        val profileId = MutableStateFlow(1)
        var revision = 7L
        val addons = mockk<AddonRepositoryImpl>()
        val context = mockk<Context>()
        private val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns profileId
            every { activeProfileSelectionRevision } answers { revision }
        }
        val handler = DeepLinkHandler(addons, profiles, context)
    }

    private companion object {
        const val url = "https://provider.example/fixture-secret/manifest.json?token=fixture-secret"
    }
}
