package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.debrid.DebridAccountCheck
import com.nuvio.tv.core.debrid.DebridAccountValidator
import com.nuvio.tv.core.debrid.DebridNativeDeviceAuth
import com.nuvio.tv.core.debrid.DebridDeviceAuthorization
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.setup.FirstWatchAccountOutcome
import com.nuvio.tv.core.setup.FirstWatchReadinessRepository
import com.nuvio.tv.core.setup.FirstWatchWriteScope
import com.nuvio.tv.data.local.DebridCredentialCipher
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.dto.PremiumizeAccountInfoDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirstWatchAccountCheckViewModelTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun `explicit account check preserves credentials and publishes actual accepted age`() = runTest {
        val h = Harness()
        h.store.setProviderApiKey("realdebrid", "owned-key")
        val before = h.store.settings.first()
        val vm = h.viewModel
        runCurrent()
        vm.checkSavedAccounts(); runCurrent()
        assertTrue(vm.checkingAccounts.value)
        h.response.complete(DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED))
        runCurrent()
        assertFalse(vm.checkingAccounts.value)
        assertEquals(before, h.store.settings.first())
        val checked = h.store.firstWatchObservations.first().accounts["realdebrid"]
        assertEquals(FirstWatchAccountOutcome.ACCEPTED, checked?.outcome)
        assertTrue((checked?.checkedAtMs ?: 0L) > 0L)
    }

    @Test fun `cancel ignores noncooperative account response and does not replace credentials`() = runTest {
        val h = Harness()
        h.store.setProviderApiKey("realdebrid", "owned-key")
        val vm = h.viewModel; runCurrent()
        vm.checkSavedAccounts(); runCurrent()
        assertTrue(vm.checkingAccounts.value)
        vm.cancelAccountCheck()
        h.response.complete(DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED)); runCurrent()
        assertFalse(vm.checkingAccounts.value)
        assertTrue(h.store.firstWatchObservations.first().accounts.isEmpty())
        assertEquals("owned-key", h.store.credentialForProfile(1, "realdebrid"))
    }

    @Test fun `profile away and back and credential replacement reject old check owner`() = runTest {
        for (replacement in listOf(false, true)) {
            val h = Harness(); h.store.setProviderApiKey("realdebrid", "owned-key")
            val vm = h.viewModel; runCurrent()
            vm.checkSavedAccounts(); runCurrent()
            assertTrue(vm.checkingAccounts.value)
            if (replacement) h.store.setProviderApiKey("realdebrid", "replacement")
            else { h.active.value = 2; h.selection += 2; h.active.value = 1 }
            h.response.complete(DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED)); runCurrent()
            assertTrue(h.store.firstWatchObservations.first().accounts.isEmpty())
            assertFalse(vm.checkingAccounts.value)
        }
    }

    @Test fun `premiumize account check preserves returned entitlement deadline`() = runTest {
        val h = Harness()
        h.store.setProviderApiKey("premiumize", "owned-pm-key")
        coEvery { h.premiumize.accountInfo("Bearer owned-pm-key") } returns Response.success(
            PremiumizeAccountInfoDto(status = "success", customerId = "owned", premiumUntil = 2_000_000_000L))
        val vm = h.viewModel; runCurrent()
        vm.checkSavedAccounts(); runCurrent()
        val checked = h.store.firstWatchObservations.first().accounts["premiumize"]
        assertEquals(FirstWatchAccountOutcome.ACCEPTED, checked?.outcome)
        assertEquals(2_000_000_000_000L, checked?.entitlementExpiresAtMs)
    }

    @Test fun `manual accepted response cannot cross a conflated profile away and back`() = runTest {
        val h = Harness(); h.store.setProviderApiKey("realdebrid", "old-key")
        val vm = h.viewModel; runCurrent()
        var saved = 0
        vm.validateAndSaveProviderApiKey("realdebrid", "owned-key") { saved++ }
        runCurrent()
        assertTrue(vm.validating.value)
        h.active.value = 2; h.selection += 2; h.active.value = 1
        h.response.complete(DebridAccountCheck(FirstWatchAccountOutcome.ACCEPTED)); runCurrent()
        assertEquals("old-key", h.store.credentialForProfile(1, "realdebrid"))
        assertEquals(0, saved)
        assertTrue(h.store.firstWatchObservations.first().accounts.isEmpty())
    }

    @Test fun `device session does not survive a conflated profile away and back`() = runTest {
        val h = Harness(); h.store.setProviderApiKey("realdebrid", "old-key")
        coEvery { h.nativeAuth.start("realdebrid") } returns DebridDeviceAuthorization("realdebrid", "device-code",
            "user-code", "https://service.example/activate", "https://service.example/activate", 5, null)
        val vm = h.viewModel; runCurrent()
        val session = vm.startDeviceAuthorization("realdebrid")!!
        assertTrue(vm.isCurrentDeviceSession(session))
        h.active.value = 2; h.selection += 2; h.active.value = 1
        assertFalse(vm.isCurrentDeviceSession(session))
    }

    private class Harness {
        val active = MutableStateFlow(1)
        var selection = 0L
        val response = CompletableDeferred<DebridAccountCheck>()
        val premiumize = mockk<PremiumizeApi>()
        val nativeAuth = mockk<DebridNativeDeviceAuth>()
        private val memory = TestPreferencesStore()
        private val factory = mockk<ProfileDataStoreFactory> { every { get(1, "debrid_settings") } returns memory }
        private val manager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns active
            every { activeProfileSelectionRevision } answers { selection }
        }
        val store = DebridSettingsDataStore(factory, manager,
            DebridCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") })
        private val readiness = mockk<FirstWatchReadinessRepository> {
            every { captureScope() } answers { FirstWatchWriteScope(active.value, selection, store.activeCredentialRevision) }
            every { isCurrent(any()) } answers {
                val captured = firstArg<FirstWatchWriteScope>()
                captured.profileId == active.value && captured.selectionRevision == selection &&
                    captured.credentialRevision == store.activeCredentialRevision
            }
        }
        private val validator = mockk<DebridAccountValidator> {
            coEvery { check("realdebrid", "owned-key") } coAnswers { withContext(NonCancellable) { response.await() } }
        }
        private val context = mockk<Context>(relaxed = true) {
            every { resources } throws IllegalStateException("No Android resources in JVM test")
            every { getString(any()) } returns "Message"
        }
        val viewModel by lazy { DebridSettingsViewModel(context, store, mockk(), premiumize,
            validator, nativeAuth, mockk(), readiness) }
    }
}
