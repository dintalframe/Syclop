package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Subtitle data to pass to an external player.
 */
data class SubtitleInput(
    val url: String,
    val name: String,
    val lang: String
) {
    override fun toString(): String = "SubtitleInput(redacted)"
}

/**
 * Input data for launching an external video player.
 */
data class ExternalPlayerInput(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String>? = null,
    val resumePositionMs: Long = 0L,
    val startFromBeginning: Boolean = false,
    val subtitles: List<SubtitleInput>? = null,
    // Pre-resolved intro/outro skip segments as a JSON array string
    // (`[{"type","start","end"}]`, times in seconds). Read by mpvNova.
    val skipSegmentsJson: String? = null,
    /** Explicit installed player; null uses a unique/current installed Android default. */
    val playerPackage: String? = null
) {
    override fun toString(): String =
        "ExternalPlayerInput(hasHeaders=${!headers.isNullOrEmpty()}, subtitleCount=${subtitles?.size ?: 0})"
}

/**
 * Result returned by an external video player after playback ends.
 * Not all players return this data — MX Player, VLC, and Just Player are known to support it.
 */
data class ExternalPlayerResult(
    val positionMs: Long,
    val durationMs: Long?,
    val endedByUser: Boolean = true
)

/**
 * ActivityResultContract that launches an external video player via ACTION_VIEW and parses the
 * playback result. Players disagree wildly on what they return, so [parseResult] is deliberately
 * lenient. Observed contracts (verified on-device):
 * - MX Player / mpvNova: position + duration (Int ms) + end_by.
 * - Just Player: on completion returns ONLY end_by=playback_completion (no position/duration).
 * - mpv-android (vanilla is.xyz.mpv): position/duration (Int) only on a back-press; at EOF returns
 *   RESULT_OK with NO extras and no end_by (handled by the heuristic in [parseResult]).
 * - VLC: extra_position/extra_duration (Long ms), no end_by; duration can be missing for network
 *   streams (the tracker backfills it). Launch resume uses position, not extra_position.
 * - Vimu: position.
 * - NovaPlayer: returns nothing usable (RESULT_CANCELED + null intent) — cannot be supported here.
 */
class ExternalPlayerResultContract : ActivityResultContract<ExternalPlayerInput, ExternalPlayerResult?>() {

    // Let createIntent reject stale/absent grants with a launch failure. Returning a null
    // synchronous result would otherwise make the tracker report an unsuccessful handoff as launched.
    override fun createIntent(context: Context, input: ExternalPlayerInput): Intent =
        ExternalPlayerIntentFactory.create(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): ExternalPlayerResult? {
        val data = intent ?: return null
        return ExternalPlayerInteropRules.parseResult(
            resultOk = resultCode == Activity.RESULT_OK,
            action = data.action,
            positionMs = firstPositiveExtra(data, "extra_position", "position"),
            durationMs = firstPositiveExtra(data, "extra_duration", "duration"),
            endBy = data.getStringExtra("end_by")
        )
    }

    /** Only the known numeric fields are read; returned URIs and arbitrary extras stay unused. */
    private fun firstPositiveExtra(data: Intent, vararg keys: String): Long? {
        for (key in keys) {
            val asLong = data.getLongExtra(key, -1L)
            if (asLong > 0L) return asLong
            val asInt = data.getIntExtra(key, -1)
            if (asInt > 0) return asInt.toLong()
        }
        return null
    }
}
