package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceRecoveryCacheOwnershipTest {
    @Test fun `current winning source publishes its own binge metadata`() = runTest {
        val store = QueuedStore().apply { release.complete(Unit) }
        val factory = mockk<ProfileDataStoreFactory> { every { get(1, any()) } returns store }
        val cache = BingeGroupCacheDataStore(factory, profiles())
        cache.replace("show", "winning-group", "exact-episode.mkv", "winning-hash")
        val saved = store.data.first()
        assertEquals("winning-group", saved[stringPreferencesKey("bg_show")])
        assertEquals("exact-episode.mkv", saved[stringPreferencesKey("bg_fn_show")])
        assertEquals("winning-hash", saved[stringPreferencesKey("bg_ih_show")])
    }

    @Test fun `cancelled queued binge write cannot replace metadata`() = runTest {
        val store = QueuedStore()
        val profiles = profiles()
        val factory = mockk<ProfileDataStoreFactory> { every { get(1, any()) } returns store }
        val cache = BingeGroupCacheDataStore(factory, profiles)
        val write = async { cache.replace("show", "refused-source", "wrong-file", "wrong-hash") }
        store.entered.await()
        write.cancel()
        store.release.complete(Unit)
        runCurrent()
        assertTrue(store.data.first().asMap().isEmpty())
    }

    @Test fun `profile away and back before queued binge edit cannot publish metadata`() = runTest {
        var revision = 0L
        val store = QueuedStore()
        val profiles = profiles()
        every { profiles.activeProfileSelectionRevision } answers { revision }
        val factory = mockk<ProfileDataStoreFactory> { every { get(1, any()) } returns store }
        val cache = BingeGroupCacheDataStore(factory, profiles)
        val write = async { cache.replace("show", "obsolete-source", "wrong-file", "wrong-hash") }
        store.entered.await()
        revision = 2L
        store.release.complete(Unit)
        write.await()
        assertTrue(store.data.first().asMap().isEmpty())
    }

    private fun profiles() = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(1)
        every { activeProfileSelectionRevision } returns 0L
    }

    /** The storage worker receives an edit before cancellation and executes it after owner invalidation. */
    private class QueuedStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = withContext(NonCancellable) {
            entered.complete(Unit)
            release.await()
            transform(data.value).also { data.value = it }
        }
    }
}
