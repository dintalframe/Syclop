package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.nuvio.tv.R

object ExternalPlayerLauncher {
    /** Fire-and-forget fallback. Prefer the ActivityResult contract when an Activity is available. */
    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L,
        startFromBeginning: Boolean = false,
        subtitles: List<SubtitleInput>? = null,
        skipSegmentsJson: String? = null,
        playerPackage: String? = null
    ): Boolean {
        if (!com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants.registry.permits(url)) return false
        return try {
            val intent = ExternalPlayerIntentFactory.create(
                context,
                createInput(url, title, headers, resumePositionMs, startFromBeginning, subtitles,
                    skipSegmentsJson, playerPackage)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (rejected: ExternalPlayerLaunchRejected) {
            Toast.makeText(context, rejected.reason.userMessage, Toast.LENGTH_LONG).show()
            false
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.player_no_external_player), Toast.LENGTH_LONG).show()
            false
        }
    }

    fun createInput(
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L,
        startFromBeginning: Boolean = false,
        subtitles: List<SubtitleInput>? = null,
        skipSegmentsJson: String? = null,
        playerPackage: String? = null
    ): ExternalPlayerInput = ExternalPlayerInput(
        url = url,
        title = title,
        headers = headers,
        resumePositionMs = resumePositionMs,
        startFromBeginning = startFromBeginning,
        subtitles = subtitles,
        skipSegmentsJson = skipSegmentsJson,
        playerPackage = playerPackage
    )
}
