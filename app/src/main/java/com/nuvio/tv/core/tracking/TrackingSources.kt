package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode

internal val AUTOMATIC_WATCH_PROGRESS_SOURCES =
    listOf(WatchProgressSource.TRAKT, WatchProgressSource.SIMKL, WatchProgressSource.MDBLIST)

val WatchProgressSource.providerId: TrackingProviderId?
    get() = when (this) {
        WatchProgressSource.TRAKT -> TrackingProviderId.TRAKT
        WatchProgressSource.SIMKL -> TrackingProviderId.SIMKL
        WatchProgressSource.MDBLIST -> TrackingProviderId.MDBLIST
        WatchProgressSource.AUTO, WatchProgressSource.NUVIO_SYNC -> null
    }

val LibrarySourceMode.providerId: TrackingProviderId?
    get() = when (this) {
        LibrarySourceMode.AUTO, LibrarySourceMode.LOCAL -> null
        LibrarySourceMode.TRAKT -> TrackingProviderId.TRAKT
        LibrarySourceMode.SIMKL -> TrackingProviderId.SIMKL
        LibrarySourceMode.MDBLIST -> TrackingProviderId.MDBLIST
        LibrarySourceMode.MAL -> TrackingProviderId.MAL
    }

fun effectiveWatchProgressSource(
    requestedSource: WatchProgressSource,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean
): WatchProgressSource {
    if (requestedSource == WatchProgressSource.AUTO) {
        return AUTOMATIC_WATCH_PROGRESS_SOURCES
            .firstOrNull { isProviderAuthenticated(requireNotNull(it.providerId)) }
            ?: WatchProgressSource.NUVIO_SYNC
    }
    val providerId = requestedSource.providerId ?: return WatchProgressSource.NUVIO_SYNC
    return requestedSource.takeIf { isProviderAuthenticated(providerId) } ?: WatchProgressSource.NUVIO_SYNC
}

fun effectiveLibrarySourceMode(
    requestedSource: LibrarySourceMode,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean
): LibrarySourceMode {
    if (requestedSource == LibrarySourceMode.AUTO) {
        return listOf(LibrarySourceMode.TRAKT, LibrarySourceMode.SIMKL, LibrarySourceMode.MDBLIST, LibrarySourceMode.MAL)
            .firstOrNull { isProviderAuthenticated(requireNotNull(it.providerId)) }
            ?: LibrarySourceMode.LOCAL
    }
    val providerId = requestedSource.providerId ?: return LibrarySourceMode.LOCAL
    return requestedSource.takeIf { isProviderAuthenticated(providerId) } ?: LibrarySourceMode.LOCAL
}

data class TrackingSourceSelection(
    val watchProgressSource: WatchProgressSource,
    val librarySourceMode: LibrarySourceMode
)

fun effectiveTrackingSourceSelection(
    requested: TrackingSourceSelection,
    connectedProviderIds: Set<TrackingProviderId>
): TrackingSourceSelection {
    return TrackingSourceSelection(
        watchProgressSource = effectiveWatchProgressSource(requested.watchProgressSource) {
            it in connectedProviderIds
        },
        librarySourceMode = effectiveLibrarySourceMode(requested.librarySourceMode) {
            it in connectedProviderIds
        }
    )
}

fun availableWatchProgressSources(
    connectedProviderIds: Set<TrackingProviderId>
): List<WatchProgressSource> = buildList {
    add(WatchProgressSource.AUTO)
    add(WatchProgressSource.NUVIO_SYNC)
    if (TrackingProviderId.TRAKT in connectedProviderIds) add(WatchProgressSource.TRAKT)
    if (TrackingProviderId.SIMKL in connectedProviderIds) add(WatchProgressSource.SIMKL)
    if (TrackingProviderId.MDBLIST in connectedProviderIds) add(WatchProgressSource.MDBLIST)
}

fun availableLibrarySourceModes(
    connectedProviderIds: Set<TrackingProviderId>
): List<LibrarySourceMode> = buildList {
    add(LibrarySourceMode.AUTO)
    add(LibrarySourceMode.LOCAL)
    if (TrackingProviderId.TRAKT in connectedProviderIds) add(LibrarySourceMode.TRAKT)
    if (TrackingProviderId.SIMKL in connectedProviderIds) add(LibrarySourceMode.SIMKL)
    if (TrackingProviderId.MDBLIST in connectedProviderIds) add(LibrarySourceMode.MDBLIST)
    if (TrackingProviderId.MAL in connectedProviderIds) add(LibrarySourceMode.MAL)
}
