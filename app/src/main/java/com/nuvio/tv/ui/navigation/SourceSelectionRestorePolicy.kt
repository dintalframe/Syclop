package com.nuvio.tv.ui.navigation

import android.os.Bundle
import com.nuvio.tv.ui.screens.player.PlayerProgressWriteOwner

internal const val SOURCE_SELECTION_RESTORE_STATE_KEY = "restoreSourceSelection"

internal fun shouldArmSourceSelectionRestore(
    autoPlayNavigation: Boolean,
    previousRoute: String?
): Boolean {
    return !autoPlayNavigation && previousRoute.orEmpty().startsWith("stream/")
}

internal fun sourceSelectionRestoreTarget(
    focusedStreamIndex: Int,
    selectedStreamKey: String?,
    streamKeys: List<String>
): Int? {
    if (streamKeys.isEmpty()) return null
    val selectedIndex = selectedStreamKey?.let(streamKeys::indexOf)
    return selectedIndex?.takeIf { it >= 0 } ?: focusedStreamIndex.coerceIn(0, streamKeys.lastIndex)
}

internal fun playerSourceSelectionOwner(arguments: Bundle?): PlayerProgressWriteOwner? {
    val args = arguments ?: return null
    return PlayerProgressWriteOwner(
        profileId = args.getString("profileId")?.toIntOrNull() ?: return null,
        contentId = args.getString("contentId") ?: return null,
        contentType = args.getString("contentType") ?: return null,
        videoId = args.getString("videoId") ?: return null,
        season = args.getString("season")?.toIntOrNull(),
        episode = args.getString("episode")?.toIntOrNull()
    )
}

internal fun returnFromPlaybackError(
    currentOwner: PlayerProgressWriteOwner?,
    retainedStreamOwner: PlayerProgressWriteOwner?,
    arguments: Bundle?,
    popBackToStream: () -> Boolean,
    navigateToStream: (String) -> Unit,
    goBack: () -> Unit
) {
    val title = arguments?.getString("title").orEmpty()
    if (currentOwner == null || currentOwner.contentId.isBlank() || currentOwner.contentType.isBlank() ||
        currentOwner.videoId.isBlank() || title.isBlank() ||
        arguments?.getString("contentId") != currentOwner.contentId ||
        arguments?.getString("contentType") != currentOwner.contentType ||
        (currentOwner.contentType in listOf("series", "tv") &&
            (currentOwner.season == null || currentOwner.episode == null))) {
        goBack()
        return
    }
    if (retainedStreamOwner == currentOwner && popBackToStream()) return
    val sameEpisode = arguments?.getString("videoId") == currentOwner.videoId &&
        arguments?.getString("season")?.toIntOrNull() == currentOwner.season &&
        arguments?.getString("episode")?.toIntOrNull() == currentOwner.episode
    navigateToStream(Screen.Stream.createRoute(
        videoId = currentOwner.videoId,
        contentType = currentOwner.contentType,
        title = title,
        poster = arguments?.getString("poster"),
        backdrop = arguments?.getString("backdrop"),
        logo = arguments?.getString("logo"),
        season = currentOwner.season,
        episode = currentOwner.episode,
        episodeName = arguments?.getString("episodeTitle").takeIf { sameEpisode },
        genres = null,
        year = arguments?.getString("year"),
        contentId = currentOwner.contentId,
        contentName = arguments?.getString("contentName"),
        runtime = null,
        manualSelection = true,
        returnToDetailOnBack = arguments?.getString("returnToDetailOnBack")?.toBooleanStrictOrNull() == true,
        returnToHomeOnBack = arguments?.getString("returnToHomeOnBack")?.toBooleanStrictOrNull() == true,
        profileId = currentOwner.profileId
    ))
}
