package com.nuvio.tv.core.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.core.streams.SyclopPlaybackPolicy

internal enum class ExternalPlayerLaunchRejection(val userMessage: String) {
    BLOCKED_STREAM(SyclopPlaybackPolicy.BLOCKED_MESSAGE),
    CHOOSE_PLAYER(ExternalPlayerCatalog.CHOOSE_MESSAGE),
    UNAVAILABLE_PLAYER(ExternalPlayerCatalog.UNAVAILABLE_MESSAGE),
    UNSUPPORTED_HEADERS(ExternalPlayerInteropRules.UNSUPPORTED_HEADERS_MESSAGE)
}

internal class ExternalPlayerLaunchRejected(val reason: ExternalPlayerLaunchRejection) :
    IllegalStateException(reason.userMessage)

/** One wire-format implementation for result-tracked and fire-and-forget launches. */
internal object ExternalPlayerIntentFactory {
    fun create(context: Context, input: ExternalPlayerInput): Intent {
        requireGrant(input.url)
        val targetPackage = try {
            ExternalPlayerCatalog.resolvePackage(context, input.playerPackage)
        } catch (unavailable: IllegalStateException) {
            val reason = if (unavailable.message == ExternalPlayerCatalog.CHOOSE_MESSAGE) {
                ExternalPlayerLaunchRejection.CHOOSE_PLAYER
            } else {
                ExternalPlayerLaunchRejection.UNAVAILABLE_PLAYER
            }
            throw ExternalPlayerLaunchRejected(reason)
        }
        val headerPairs = try {
            ExternalPlayerInteropRules.headerPairs(targetPackage, input.headers)
        } catch (_: IllegalArgumentException) {
            throw ExternalPlayerLaunchRejected(ExternalPlayerLaunchRejection.UNSUPPORTED_HEADERS)
        }
        val resume = ExternalPlayerInteropRules.resumePlan(targetPackage, input.resumePositionMs, input.startFromBeginning)
        val subtitles = ExternalPlayerInteropRules.cachedSubtitles(context.packageName, input.subtitles)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(input.url), "video/*")
            setPackage(targetPackage)
            input.title?.take(512)?.let {
                putExtra("title", it)
                putExtra(Intent.EXTRA_TITLE, it)
                putExtra("forcename", it)
            }
            if (headerPairs.isNotEmpty()) putExtra("headers", headerPairs)
            if (resume.sendPosition) {
                if (resume.useLongPosition) putExtra("position", resume.positionMs)
                else putExtra("position", resume.intPositionMs)
                putExtra("startfrom", resume.intPositionMs)
                putExtra("forceresume", !resume.startFromBeginning)
                putExtra("from_start", resume.startFromBeginning)
            }
            putExtra("return_result", true)
            ExternalPlayerInteropRules.skipSegments(input.skipSegmentsJson)?.let { putExtra("skip_segments", it) }
            if (subtitles.isNotEmpty()) {
                val uris = subtitles.map { Uri.parse(it.url) }.toTypedArray()
                val names = subtitles.map { it.name }.toTypedArray()
                val filenames = subtitles.map(ExternalPlayerInteropRules::subtitleFilename).toTypedArray()
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData("subtitles", arrayOf("application/x-subrip", "text/vtt", "text/x-ssa"),
                    ClipData.Item(uris.first())).also { clips ->
                    uris.drop(1).forEach { clips.addItem(ClipData.Item(it)) }
                }
                putExtra("subs", uris)
                putExtra("subs.name", names)
                putExtra("subs.filename", filenames)
                putExtra("subs.enable", arrayOf(uris.first()))
                // VLC requires a String. Just Player reads the MX `subs` fields above.
                putExtra("subtitles_location", subtitles.first().url)
                putExtra("forcedsrt", subtitles.first().url)
            }
        }
        // Discovery and payload preparation must not extend a stale account/profile grant.
        requireGrant(input.url)
        return intent
    }

    private fun requireGrant(url: String) {
        if (!ConnectedServicePlaybackGrants.registry.permits(url)) {
            throw ExternalPlayerLaunchRejected(ExternalPlayerLaunchRejection.BLOCKED_STREAM)
        }
    }
}
