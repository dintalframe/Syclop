package com.nuvio.tv.data.mal

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MalLibrarySourcePersistenceTest {
    @Test fun `MAL source persists per profile without changing resume source`() = runTest {
        val root = Files.createTempDirectory("syclop-mal-source").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val primary = PreferenceDataStoreFactory.create(scope = scope) { root.resolve("one.preferences_pb") }
            val second = PreferenceDataStoreFactory.create(scope = scope) { root.resolve("two.preferences_pb") }
            val profiles = MutableStateFlow(1)
            val factory = mockk<ProfileDataStoreFactory> {
                every { get(1, "trakt_settings") } returns primary
                every { get(2, "trakt_settings") } returns second
            }
            val manager = mockk<ProfileManager> { every { activeProfileId } returns profiles }
            val settings = TraktSettingsDataStore(factory, manager)
            settings.setWatchProgressSource(WatchProgressSource.NUVIO_SYNC, 1)
            settings.setLibrarySourceMode(LibrarySourceMode.MAL, 1)
            assertEquals("MAL", primary.data.first()[stringPreferencesKey("library_source_mode")])
            assertEquals(LibrarySourceMode.MAL, settings.getLibrarySourceMode(1))
            assertEquals(WatchProgressSource.NUVIO_SYNC, settings.getWatchProgressSource(1))
            profiles.value = 2
            settings.setLibrarySourceMode(LibrarySourceMode.LOCAL, 2)
            assertEquals(LibrarySourceMode.LOCAL, settings.getLibrarySourceMode(2))
            assertEquals(LibrarySourceMode.MAL, settings.getLibrarySourceMode(1))
            assertEquals(WatchProgressSource.NUVIO_SYNC, settings.getWatchProgressSource(1))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
