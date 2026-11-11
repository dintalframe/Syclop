package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.focus.FocusRequester
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames

internal data class SavedLibraryPosterTarget(val key: String, val lazyIndex: Int)

internal fun savedLibraryPosterTarget(
    keys: List<String>,
    savedKey: String,
    savedPosterIndex: Int,
    showListActions: Boolean
): SavedLibraryPosterTarget? {
    if (keys.isEmpty()) return null
    val posterIndex = keys.indexOf(savedKey).takeIf { it >= 0 } ?: savedPosterIndex.coerceIn(keys.indices)
    // Title, view-mode picker and Saved filters each occupy one full-span item.
    val headerCount = if (showListActions) 4 else 3
    return SavedLibraryPosterTarget(keys[posterIndex], headerCount + posterIndex)
}

internal suspend fun restoreSavedLibraryPoster(
    grid: LazyGridState,
    target: SavedLibraryPosterTarget,
    requester: FocusRequester
): Boolean {
    if (grid.layoutInfo.visibleItemsInfo.none { it.key == target.key }) {
        grid.scrollToItem(target.lazyIndex)
    }
    return requester.requestFocusAfterFrames(frames = 1)
}
