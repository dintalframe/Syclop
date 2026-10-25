package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SimklProfileLogoutCacheTest {
    @Test fun `current cache clear captures initiating viewer before IO dispatch`() = runTest {
        val active = MutableStateFlow(1)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        val storage = CacheStorage()
        val repository = SimklSyncRepository(mockk(), storage, mockk(), disconnectedAuth(), profiles,
            StandardTestDispatcher(testScheduler, "SimklIO"), backgroundScope)
        runCurrent()
        val clear = launch(start = CoroutineStart.UNDISPATCHED) { repository.clearCurrentProfile() }
        active.value = 2 // Clear has dispatched, but has not read or removed storage yet.
        clear.join()
        assertEquals(listOf(1), storage.removed)
        assertFalse(storage.values.containsKey(1))
        assertTrue(storage.values.containsKey(2))
    }

    @Test fun `explicit logout profile remains the target after another viewer becomes active`() = runTest {
        val active = MutableStateFlow(1)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        val storage = CacheStorage()
        val repository = SimklSyncRepository(mockk(), storage, mockk(), disconnectedAuth(), profiles,
            StandardTestDispatcher(testScheduler, "SimklIO"), backgroundScope)
        runCurrent()
        val clear = launch(start = CoroutineStart.UNDISPATCHED) { repository.clearProfile(1) }
        active.value = 2
        clear.join()
        assertEquals(listOf(1), storage.removed)
        assertTrue(storage.values.containsKey(2))
    }

    private fun disconnectedAuth() = mockk<SimklAuthStorage> {
        every { state } returns MutableStateFlow(SimklAuthState())
    }

    private class CacheStorage : SimklSyncStorage {
        // Empty persisted snapshots are valid and keep this test focused on profile targeting.
        val values = mutableMapOf(1 to "", 2 to "")
        val removed = mutableListOf<Int>()
        override suspend fun load(profileId: Int): String? = values[profileId]
        override suspend fun save(profileId: Int, payload: String) { values[profileId] = payload }
        override suspend fun remove(profileId: Int) { removed += profileId; values.remove(profileId) }
    }
}
