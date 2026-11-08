package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingSourceMemoryTest {
    private class Stored(
        var progress: WatchProgressSource,
        var library: LibrarySourceMode,
        var rememberedProgress: WatchProgressSource? = null,
        var rememberedLibrary: LibrarySourceMode? = null
    ) {
        var progressWrites = 0
        var libraryWrites = 0
    }

    @Test
    fun `automatic connection changes preserve stored AUTO without clearing local viewing data`() = runTest {
        val stored = Stored(WatchProgressSource.AUTO, LibrarySourceMode.AUTO)
        val cache = mockk<ContinueWatchingEnrichmentCache>(relaxed = true)
        val watched = mockk<WatchedItemsPreferences>(relaxed = true)
        val progress = mockk<WatchProgressPreferences>(relaxed = true)
        val controller = controller(stored, cache, watched, progress)
        val sequence = listOf(emptySet(), setOf(TrackingProviderId.MAL), setOf(TrackingProviderId.MDBLIST),
            setOf(TrackingProviderId.SIMKL), setOf(TrackingProviderId.TRAKT), emptySet())
        val effective = sequence.map { controller.reconcileConnectedProviders(it) }
        assertEquals(listOf(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.NUVIO_SYNC,
            WatchProgressSource.MDBLIST, WatchProgressSource.SIMKL, WatchProgressSource.TRAKT,
            WatchProgressSource.NUVIO_SYNC), effective.map { it.watchProgressSource })
        assertEquals(listOf(LibrarySourceMode.LOCAL, LibrarySourceMode.MAL, LibrarySourceMode.MDBLIST,
            LibrarySourceMode.SIMKL, LibrarySourceMode.TRAKT, LibrarySourceMode.LOCAL), effective.map { it.librarySourceMode })
        assertEquals(WatchProgressSource.AUTO, stored.progress)
        assertEquals(LibrarySourceMode.AUTO, stored.library)
        assertEquals(0, stored.progressWrites)
        assertEquals(0, stored.libraryWrites)
        coVerify(exactly = 0) { cache.saveInProgressSnapshot(any(), any()) }
        coVerify(exactly = 0) { cache.saveNextUpSnapshot(any(), any()) }
        coVerify(exactly = 0) { watched.clearAll(any()) }
        coVerify(exactly = 0) { progress.clearAllPreservingNonTraktIds(any(), any()) }
    }

    @Test
    fun `selecting Auto forgets prior remembered manual source and preserves Auto on reconnect`() = runTest {
        val stored = Stored(WatchProgressSource.NUVIO_SYNC, LibrarySourceMode.LOCAL,
            rememberedProgress = WatchProgressSource.SIMKL, rememberedLibrary = LibrarySourceMode.MAL)
        val controller = controller(stored)
        controller.selectWatchProgressSource(WatchProgressSource.AUTO)
        controller.selectLibrarySourceMode(LibrarySourceMode.AUTO)
        assertNull(stored.rememberedProgress)
        assertNull(stored.rememberedLibrary)
        assertEquals(1, stored.progressWrites)
        assertEquals(1, stored.libraryWrites)
        controller.reconcileConnectedProviders(setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL, TrackingProviderId.MAL))
        controller.reconcileConnectedProviders(emptySet())
        assertEquals(WatchProgressSource.AUTO, stored.progress)
        assertEquals(LibrarySourceMode.AUTO, stored.library)
        assertEquals(1, stored.progressWrites)
        assertEquals(1, stored.libraryWrites)
    }

    @Test
    fun `manual selection overrides Auto and cannot be promoted by another provider connecting`() = runTest {
        val stored = Stored(WatchProgressSource.AUTO, LibrarySourceMode.AUTO)
        val controller = controller(stored)
        controller.selectWatchProgressSource(WatchProgressSource.NUVIO_SYNC)
        controller.selectLibrarySourceMode(LibrarySourceMode.LOCAL)
        controller.reconcileConnectedProviders(TrackingProviderId.entries.toSet())
        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertNull(stored.rememberedProgress)
        assertNull(stored.rememberedLibrary)
    }

    @Test
    fun `MAL library choice is remembered on logout without changing local resume`() = runTest {
        val stored = Stored(WatchProgressSource.NUVIO_SYNC, LibrarySourceMode.MAL)
        val controller = controller(stored)
        controller.reconcileConnectedProviders(emptySet())
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertEquals(LibrarySourceMode.MAL, stored.rememberedLibrary)
        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        controller.reconcileConnectedProviders(setOf(TrackingProviderId.MAL))
        assertEquals(LibrarySourceMode.MAL, stored.library)
        assertNull(stored.rememberedLibrary)
        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
    }

    @Test
    fun `logging out falls back and logging back in restores the choice`() = runTest {
        val stored = Stored(WatchProgressSource.TRAKT, LibrarySourceMode.TRAKT)
        val controller = controller(stored)

        controller.reconcileConnectedProviders(emptySet())
        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertEquals(WatchProgressSource.TRAKT, stored.rememberedProgress)
        assertEquals(LibrarySourceMode.TRAKT, stored.rememberedLibrary)

        controller.reconcileConnectedProviders(setOf(TrackingProviderId.TRAKT))
        assertEquals(WatchProgressSource.TRAKT, stored.progress)
        assertEquals(LibrarySourceMode.TRAKT, stored.library)
        assertNull(stored.rememberedProgress)
        assertNull(stored.rememberedLibrary)
    }

    @Test
    fun `another provider connecting keeps the fallback and the remembered choice`() = runTest {
        val stored = Stored(
            WatchProgressSource.NUVIO_SYNC,
            LibrarySourceMode.LOCAL,
            rememberedProgress = WatchProgressSource.TRAKT,
            rememberedLibrary = LibrarySourceMode.TRAKT
        )

        controller(stored).reconcileConnectedProviders(setOf(TrackingProviderId.SIMKL))

        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertEquals(WatchProgressSource.TRAKT, stored.rememberedProgress)
        assertEquals(LibrarySourceMode.TRAKT, stored.rememberedLibrary)
    }

    @Test
    fun `choosing a source by hand forgets the remembered one`() = runTest {
        val stored = Stored(
            WatchProgressSource.NUVIO_SYNC,
            LibrarySourceMode.LOCAL,
            rememberedProgress = WatchProgressSource.TRAKT,
            rememberedLibrary = LibrarySourceMode.TRAKT
        )
        val controller = controller(stored)

        controller.selectWatchProgressSource(WatchProgressSource.NUVIO_SYNC)
        controller.selectLibrarySourceMode(LibrarySourceMode.LOCAL)
        controller.reconcileConnectedProviders(setOf(TrackingProviderId.TRAKT))

        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertNull(stored.rememberedProgress)
        assertNull(stored.rememberedLibrary)
    }

    private fun controller(stored: Stored,
        cache: ContinueWatchingEnrichmentCache = mockk(relaxed = true),
        watched: WatchedItemsPreferences = mockk(relaxed = true),
        progress: WatchProgressPreferences = mockk(relaxed = true)
    ): TrackingSourceController {
        val settings = mockk<TraktSettingsDataStore>(relaxed = true)
        coEvery { settings.getWatchProgressSource(1) } answers { stored.progress }
        coEvery { settings.getLibrarySourceMode(1) } answers { stored.library }
        coEvery { settings.getRememberedWatchProgressSource(1) } answers { stored.rememberedProgress }
        coEvery { settings.getRememberedLibrarySourceMode(1) } answers { stored.rememberedLibrary }
        coEvery { settings.setWatchProgressSource(any(), 1) } answers { stored.progressWrites++; stored.progress = firstArg() }
        coEvery { settings.setLibrarySourceMode(any(), 1) } answers { stored.libraryWrites++; stored.library = firstArg() }
        coEvery { settings.setRememberedWatchProgressSource(any(), 1) } answers { stored.rememberedProgress = firstArg() }
        coEvery { settings.setRememberedLibrarySourceMode(any(), 1) } answers { stored.rememberedLibrary = firstArg() }
        return TrackingSourceController(
            settings, TrackingProgressProviderRegistry(emptySet()), TrackingLibraryProviderRegistry(emptySet()),
            mockk(relaxed = true), watched, progress, mockk(relaxed = true),
            mockk(relaxed = true), cache, mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        )
    }
}
