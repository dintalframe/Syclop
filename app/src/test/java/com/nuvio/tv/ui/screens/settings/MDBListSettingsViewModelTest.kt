package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.mdblist.MdbListTestHarness
import com.nuvio.tv.data.remote.api.MDBListApi
import io.mockk.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MDBListSettingsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test fun `connected tracker keeps separate encrypted ratings key and clear callback waits for commit`() = runTest {
        val f = fixture()
        f.harness.connected()
        coEvery { f.api.getUser("ratings-key") } returns Response.success(Unit)
        runCurrent()
        var successes = 0
        f.vm.validateAndSaveApiKey(" ratings-key ") { successes++ }
        assertEquals(0, successes)
        runCurrent()
        assertEquals(1, successes)
        assertEquals("ratings-key", f.store.credentialForProfile(1))
        assertFalse(f.primary.value.toString().contains("ratings-key"))
        assertTrue(f.vm.uiState.value.isConnected)
        assertFalse(f.vm.uiState.value.toString().contains("ratings-key"))
        f.vm.validateAndSaveApiKey("") { successes++ }
        assertEquals(1, successes)
        runCurrent()
        assertEquals(2, successes)
        assertEquals("", f.store.credentialForProfile(1))
        assertTrue(f.vm.uiState.value.isConnected)
        coVerify(exactly = 1) { f.api.getUser(any()) }
    }

    @Test fun `validation result cannot save into the newly selected profile`() = runTest {
        val f = fixture()
        val gate = delayedSuccess(f.api)
        runCurrent()
        var successes = 0
        f.vm.validateAndSaveApiKey("late-key") { successes++ }
        runCurrent()
        f.active.value = 2
        f.harness.store.selectProfile(2)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, successes)
        assertTrue(f.primary.value.asMap().isEmpty())
        assertTrue(f.second.value.asMap().isEmpty())
        assertFalse(f.vm.validating.value)
    }

    @Test fun `validation is invalid after switching away and back`() = runTest {
        val f = fixture()
        val gate = delayedSuccess(f.api)
        runCurrent()
        var successes = 0
        f.vm.validateAndSaveApiKey("late-key") { successes++ }
        runCurrent()
        f.active.value = 2
        runCurrent()
        f.active.value = 1
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, successes)
        assertEquals("", f.store.credentialForProfile(1))
    }

    @Test fun `cancelled validation never commits even if transport later returns success`() = runTest {
        val f = fixture()
        val gate = delayedSuccess(f.api)
        runCurrent()
        var successes = 0
        f.vm.validateAndSaveApiKey("late-key") { successes++ }
        runCurrent()
        f.vm.cancelApiKeyValidation()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, successes)
        assertEquals("", f.store.credentialForProfile(1))
        assertFalse(f.vm.validating.value)
    }

    @Test fun `encryption failure does not call success or save plaintext`() = runTest {
        val f = fixture(MDBListRatingCredentialCipher { error("Keystore unavailable") })
        coEvery { f.api.getUser("new-key") } returns Response.success(Unit)
        runCurrent()
        var successes = 0
        f.vm.validateAndSaveApiKey("new-key") { successes++ }
        runCurrent()
        assertEquals(0, successes)
        assertTrue(f.primary.value.asMap().isEmpty())
        assertFalse(f.vm.validating.value)
    }

    private fun delayedSuccess(api: MDBListApi): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getUser("late-key") } coAnswers {
            withContext(NonCancellable) { gate.await() }
            Response.success(Unit)
        }
        return gate
    }

    private data class Fixture(val active: MutableStateFlow<Int>, val primary: TestPreferencesStore,
        val second: TestPreferencesStore, val store: MDBListSettingsDataStore, val api: MDBListApi,
        val harness: MdbListTestHarness, val vm: MDBListSettingsViewModel)

    private fun fixture(cipher: MDBListRatingCredentialCipher = MDBListRatingCredentialCipher {
        SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    }): Fixture {
        val active = MutableStateFlow(1)
        val primary = TestPreferencesStore()
        val second = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>(relaxed = true) {
            every { get(1, "mdblist_settings") } returns primary
            every { get(2, "mdblist_settings") } returns second
            every { isProfileDeleted(any()) } returns false
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        val store = MDBListSettingsDataStore(factory, profiles, cipher)
        val api = mockk<MDBListApi>()
        val harness = MdbListTestHarness()
        return Fixture(active, primary, second, store, api, harness, MDBListSettingsViewModel(store, api, harness.store))
    }
}
