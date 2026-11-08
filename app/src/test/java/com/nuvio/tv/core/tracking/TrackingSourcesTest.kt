package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingSourcesTest {
    @Test
    fun `MDBList is available independently for watch and library sources`() {
        val connected = setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL, TrackingProviderId.MDBLIST)
        assertEquals(WatchProgressSource.MDBLIST, WatchProgressSource.fromStorage("MDBLIST"))
        assertEquals(TrackingProviderId.MDBLIST, WatchProgressSource.MDBLIST.providerId)
        assertEquals(
            listOf(WatchProgressSource.AUTO, WatchProgressSource.NUVIO_SYNC, WatchProgressSource.TRAKT, WatchProgressSource.SIMKL, WatchProgressSource.MDBLIST),
            availableWatchProgressSources(connected)
        )
        assertEquals(
            listOf(LibrarySourceMode.AUTO, LibrarySourceMode.LOCAL, LibrarySourceMode.TRAKT, LibrarySourceMode.SIMKL, LibrarySourceMode.MDBLIST),
            availableLibrarySourceModes(connected)
        )
        val selection = TrackingSourceSelection(WatchProgressSource.MDBLIST, LibrarySourceMode.TRAKT)
        assertEquals(selection, effectiveTrackingSourceSelection(selection, connected))
        assertEquals(
            selection.copy(watchProgressSource = WatchProgressSource.NUVIO_SYNC),
            effectiveTrackingSourceSelection(selection, connected - TrackingProviderId.MDBLIST)
        )
    }

    @Test
    fun `legacy source names retain their stored meaning`() {
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage("TRAKT"))
        assertEquals(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.fromStorage("NUVIO_SYNC"))
        assertEquals(LibrarySourceMode.TRAKT, LibrarySourceMode.valueOf("TRAKT"))
    }

    @Test
    fun `missing or invalid progress preference means automatic while explicit storage remains explicit`() {
        assertEquals(WatchProgressSource.AUTO, WatchProgressSource.fromStorage(null))
        assertEquals(WatchProgressSource.AUTO, WatchProgressSource.fromStorage("unknown-source"))
        assertEquals(WatchProgressSource.AUTO, WatchProgressSource.fromStorage("AUTO"))
        assertNull(WatchProgressSource.AUTO.providerId)
        assertNull(LibrarySourceMode.AUTO.providerId)
    }

    @Test
    fun `automatic sources choose connected providers in priority order for every connection combination`() {
        val providers = listOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL, TrackingProviderId.MDBLIST, TrackingProviderId.MAL)
        repeat(1 shl providers.size) { mask ->
            val connected = providers.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val expectedProgress = when {
                TrackingProviderId.TRAKT in connected -> WatchProgressSource.TRAKT
                TrackingProviderId.SIMKL in connected -> WatchProgressSource.SIMKL
                TrackingProviderId.MDBLIST in connected -> WatchProgressSource.MDBLIST
                else -> WatchProgressSource.NUVIO_SYNC
            }
            val expectedLibrary = when {
                TrackingProviderId.TRAKT in connected -> LibrarySourceMode.TRAKT
                TrackingProviderId.SIMKL in connected -> LibrarySourceMode.SIMKL
                TrackingProviderId.MDBLIST in connected -> LibrarySourceMode.MDBLIST
                TrackingProviderId.MAL in connected -> LibrarySourceMode.MAL
                else -> LibrarySourceMode.LOCAL
            }
            assertEquals("connections=$connected", TrackingSourceSelection(expectedProgress, expectedLibrary),
                effectiveTrackingSourceSelection(TrackingSourceSelection(WatchProgressSource.AUTO, LibrarySourceMode.AUTO), connected))
        }
    }

    @Test
    fun `automatic reconnect promotes priority while manual remote and local choices stay selected`() {
        val auto = TrackingSourceSelection(WatchProgressSource.AUTO, LibrarySourceMode.AUTO)
        val sequence = listOf(emptySet(), setOf(TrackingProviderId.MDBLIST),
            setOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL),
            setOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL, TrackingProviderId.TRAKT),
            setOf(TrackingProviderId.SIMKL), emptySet())
        assertEquals(listOf(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.MDBLIST,
            WatchProgressSource.SIMKL, WatchProgressSource.TRAKT, WatchProgressSource.SIMKL,
            WatchProgressSource.NUVIO_SYNC), sequence.map { effectiveTrackingSourceSelection(auto, it).watchProgressSource })
        val all = TrackingProviderId.entries.toSet()
        val explicit = TrackingSourceSelection(WatchProgressSource.SIMKL, LibrarySourceMode.MAL)
        assertEquals(explicit, effectiveTrackingSourceSelection(explicit, all))
        val local = TrackingSourceSelection(WatchProgressSource.NUVIO_SYNC, LibrarySourceMode.LOCAL)
        assertEquals(local, effectiveTrackingSourceSelection(local, all))
    }

    @Test
    fun `remote watch source falls back to Nuvio Sync when disconnected`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { false }
        )
        assertEquals(
            WatchProgressSource.SIMKL,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `remote library source falls back to local when disconnected`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { false }
        )
        assertEquals(
            LibrarySourceMode.SIMKL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `local sources do not map to remote providers`() {
        assertNull(WatchProgressSource.NUVIO_SYNC.providerId)
        assertNull(LibrarySourceMode.LOCAL.providerId)
    }

    @Test
    fun `source reconciliation covers every provider connection combination`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.NUVIO_SYNC,
                LibrarySourceMode.LOCAL
            ),
            effectiveTrackingSourceSelection(requested, emptySet())
        )
        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.NUVIO_SYNC,
                LibrarySourceMode.TRAKT
            ),
            effectiveTrackingSourceSelection(requested, setOf(TrackingProviderId.TRAKT))
        )
        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.SIMKL,
                LibrarySourceMode.LOCAL
            ),
            effectiveTrackingSourceSelection(requested, setOf(TrackingProviderId.SIMKL))
        )
        assertEquals(
            requested,
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)
            )
        )
    }

    @Test
    fun `disconnecting an active provider resets only its selected source`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            TrackingSourceSelection(
                watchProgressSource = WatchProgressSource.NUVIO_SYNC,
                librarySourceMode = LibrarySourceMode.TRAKT
            ),
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT)
            )
        )
    }

    @Test
    fun `disconnecting an inactive provider preserves selected sources`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.TRAKT,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            requested,
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT)
            )
        )
    }

    @Test
    fun `both connected pickers expose Nuvio Trakt and Simkl in stable order`() {
        val connected = setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)

        assertEquals(
            listOf(
                WatchProgressSource.AUTO,
                WatchProgressSource.NUVIO_SYNC,
                WatchProgressSource.TRAKT,
                WatchProgressSource.SIMKL
            ),
            availableWatchProgressSources(connected)
        )
        assertEquals(
            listOf(
                LibrarySourceMode.AUTO,
                LibrarySourceMode.LOCAL,
                LibrarySourceMode.TRAKT,
                LibrarySourceMode.SIMKL
            ),
            availableLibrarySourceModes(connected)
        )
    }

    @Test
    fun `disconnected providers are excluded from source pickers`() {
        assertEquals(
            listOf(WatchProgressSource.AUTO, WatchProgressSource.NUVIO_SYNC),
            availableWatchProgressSources(emptySet())
        )
        assertEquals(
            listOf(LibrarySourceMode.AUTO, LibrarySourceMode.LOCAL),
            availableLibrarySourceModes(emptySet())
        )
        assertEquals(
            listOf(WatchProgressSource.AUTO, WatchProgressSource.NUVIO_SYNC, WatchProgressSource.SIMKL),
            availableWatchProgressSources(setOf(TrackingProviderId.SIMKL))
        )
        assertEquals(
            listOf(LibrarySourceMode.AUTO, LibrarySourceMode.LOCAL, LibrarySourceMode.TRAKT),
            availableLibrarySourceModes(setOf(TrackingProviderId.TRAKT))
        )
    }
}
