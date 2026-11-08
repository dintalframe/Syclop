package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LibrarySourceMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktSettingsSourceDefaultsTest {
    @Test
    fun `new profiles default both sources to Auto without persisting a resolved provider`() = runTest {
        val h = Fixture()
        for (profile in listOf(1, 2)) {
            assertEquals(WatchProgressSource.AUTO, h.settings.getWatchProgressSource(profile))
            assertEquals(LibrarySourceMode.AUTO, h.settings.getLibrarySourceMode(profile))
            assertTrue(h.stores.getValue(profile).value.asMap().isEmpty())
        }
        assertEquals(WatchProgressSource.AUTO, h.settings.watchProgressSource.first())
        assertEquals(LibrarySourceMode.AUTO, h.settings.librarySourceMode.first())
        assertEquals(WatchProgressSource.AUTO, TraktSettingsDataStore.DEFAULT_WATCH_PROGRESS_SOURCE)
        assertEquals(LibrarySourceMode.AUTO, TraktSettingsDataStore.DEFAULT_LIBRARY_SOURCE_MODE)
    }

    @Test
    fun `invalid saved source names resolve to Auto without rewriting unrelated stored preferences`() = runTest {
        val progress = stringPreferencesKey("watch_progress_source")
        val library = stringPreferencesKey("library_source_mode")
        val h = Fixture(TestPreferencesStore(mutablePreferencesOf(progress to "unknown-progress", library to "unknown-library")))
        assertEquals(WatchProgressSource.AUTO, h.settings.getWatchProgressSource(1))
        assertEquals(LibrarySourceMode.AUTO, h.settings.getLibrarySourceMode(1))
        assertEquals("unknown-progress", h.stores.getValue(1).value[progress])
        assertEquals("unknown-library", h.stores.getValue(1).value[library])
    }

    @Test
    fun `explicit local and remote choices retain their stored meaning per profile`() = runTest {
        val h = Fixture()
        h.settings.setWatchProgressSource(WatchProgressSource.NUVIO_SYNC, 1)
        h.settings.setLibrarySourceMode(LibrarySourceMode.LOCAL, 1)
        h.settings.setWatchProgressSource(WatchProgressSource.SIMKL, 2)
        h.settings.setLibrarySourceMode(LibrarySourceMode.MAL, 2)
        assertEquals(WatchProgressSource.NUVIO_SYNC, h.settings.getWatchProgressSource(1))
        assertEquals(LibrarySourceMode.LOCAL, h.settings.getLibrarySourceMode(1))
        assertEquals(WatchProgressSource.SIMKL, h.settings.getWatchProgressSource(2))
        assertEquals(LibrarySourceMode.MAL, h.settings.getLibrarySourceMode(2))
        h.profile.value = 2
        assertEquals(WatchProgressSource.SIMKL, withContext(Dispatchers.Default) {
            // The settings StateFlow is fed by Dispatchers.IO; use a wall-clock deadline.
            withTimeout(3000) {
                h.settings.watchProgressSource.first { it == WatchProgressSource.SIMKL }
            }
        })
        assertEquals(LibrarySourceMode.MAL, h.settings.librarySourceMode.first())
        assertEquals(WatchProgressSource.NUVIO_SYNC, h.settings.getWatchProgressSource(1))
    }

    @Test
    fun `selecting Auto persists the preference rather than any current provider`() = runTest {
        val h = Fixture()
        h.settings.setWatchProgressSource(WatchProgressSource.TRAKT, 1)
        h.settings.setLibrarySourceMode(LibrarySourceMode.MDBLIST, 1)
        h.settings.setWatchProgressSource(WatchProgressSource.AUTO, 1)
        h.settings.setLibrarySourceMode(LibrarySourceMode.AUTO, 1)
        assertEquals("AUTO", h.stores.getValue(1).value[stringPreferencesKey("watch_progress_source")])
        assertEquals("AUTO", h.stores.getValue(1).value[stringPreferencesKey("library_source_mode")])
        val reloaded = TraktSettingsDataStore(h.factory, h.manager)
        assertEquals(WatchProgressSource.AUTO, reloaded.getWatchProgressSource(1))
        assertEquals(LibrarySourceMode.AUTO, reloaded.getLibrarySourceMode(1))
    }

    private class Fixture(firstStore: TestPreferencesStore = TestPreferencesStore()) {
        val stores = mapOf(1 to firstStore, 2 to TestPreferencesStore())
        val profile = MutableStateFlow(1)
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "trakt_settings") } answers { this@Fixture.stores.getValue(firstArg()) }
        }
        val manager = mockk<ProfileManager> { every { activeProfileId } returns this@Fixture.profile }
        val settings = TraktSettingsDataStore(factory, manager)
    }
}
