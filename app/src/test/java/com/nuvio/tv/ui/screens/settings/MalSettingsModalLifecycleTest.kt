package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.data.mal.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

class MalSettingsModalLifecycleTest {
    @Test fun `recycled row subscription cannot close stable modal or cancel pending auth`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = Harness(); val owner = ViewModelStore()
        try {
            val viewModel = MalSettingsViewModel(h.auth, h.repository); owner.put("mal", viewModel)
            val row = backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent(); viewModel.open(); h.session.value = h.pending; runCurrent()
            assertTrue(viewModel.dialogVisible.value); assertNotNull(h.session.value)
            row.cancelAndJoin(); advanceTimeBy(5_001); runCurrent()
            assertTrue(viewModel.dialogVisible.value); assertNotNull(h.session.value); assertEquals(0, h.cancelCount)
            val host = backgroundScope.launch { viewModel.uiState.collect {} }; runCurrent()
            assertNotNull(viewModel.uiState.value.session); host.cancelAndJoin()
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun `intentional close cancels active session and hides stable modal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val h = Harness(); val owner = ViewModelStore()
        try {
            val vm = MalSettingsViewModel(h.auth, h.repository); owner.put("mal", vm)
            runCurrent(); vm.open(); h.session.value = h.pending; runCurrent(); vm.close()
            assertFalse(vm.dialogVisible.value); assertNull(h.session.value); assertEquals(1, h.cancelCount)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun `actual profile change closes modal without affecting another account`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val h = Harness(); val owner = ViewModelStore()
        try {
            val vm = MalSettingsViewModel(h.auth, h.repository); owner.put("mal", vm)
            runCurrent(); vm.open(); h.session.value = h.pending; runCurrent()
            h.account.value = h.account.value.copy(scope = MalScope(2, 2)); runCurrent()
            assertFalse(vm.dialogVisible.value); assertNull(h.session.value)
            assertTrue(h.reasons.contains(MalAuthStage.ACCOUNT_CHANGED))
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun `queued connection action cannot reopen scan after user close`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val h = Harness(); val owner = ViewModelStore()
        try {
            val vm = MalSettingsViewModel(h.auth, h.repository); owner.put("mal", vm)
            runCurrent(); vm.open(); val original = h.account.value.scope; vm.close()
            vm.connectIfOpen(original)
            assertFalse(vm.dialogVisible.value); assertNull(h.session.value)
            verify(exactly = 0) { h.auth.connect() }
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private class Harness {
        val account = MutableStateFlow(MalAccountState(scope = MalScope(1, 1), configuration = MalConfiguration("test-public-client", "https://example.test/mal")))
        val session = MutableStateFlow<MalAuthorizationSession?>(null)
        val notice = MutableStateFlow<String?>(null)
        val library = MutableStateFlow(MalLibraryState(scope = account.value.scope))
        val auth = mockk<MalAuthRepository>(); val repository = mockk<MalRepository>(); val store = mockk<MalAuthStore>()
        val pending = MalAuthorizationSession("https://example.test/authorize", "https://example.test/mal", Long.MAX_VALUE, true)
        var cancelCount = 0; val reasons = mutableListOf<MalAuthStage>()
        init {
            every { auth.state } returns account; every { auth.session } returns session; every { auth.notice } returns notice
            every { auth.store } returns store; every { store.isCurrent(any()) } answers { firstArg<MalScope>() == account.value.scope }
            every { repository.state } returns library
            coEvery { repository.refresh() } just Runs
            every { auth.connect() } answers {
                MalAuthorizationSession("https://example.test/authorize", "https://example.test/mal", Long.MAX_VALUE, true).also { session.value = it }
            }
            every { auth.cancel() } answers { cancelCount++; session.value = null }
            every { auth.cancel(any()) } answers { cancelCount++; reasons += firstArg<MalAuthStage>(); session.value = null }
        }
    }
}
