package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale

/** Describes the selected source formats, never the TV/receiver's output capabilities. */
data class PlayerBadgeMediaSnapshot(
    val info: PlayerMediaInfo,
    val sourceKey: String?,
    val engine: InternalPlayerEngine
) {
    fun forPlayback(engine: InternalPlayerEngine, url: String?): PlayerMediaInfo? =
        info.takeIf { this.engine == engine && sourceKey != null && sourceKey == streamInfoMediaOriginKey(url) }
}

internal fun technicalPlaybackBadges(info: PlayerMediaInfo): List<String> = buildList {
    val width = info.videoWidth?.takeIf { it > 0 }
    val height = info.videoHeight?.takeIf { it > 0 }
    if (width != null && height != null) {
        add(when {
            width >= 7600 || height >= 4200 -> "8K"
            width >= 3800 || height >= 2100 -> "4K"
            width >= 2500 || height >= 1400 -> "1440p"
            width >= 1900 || height >= 1000 -> "1080p"
            width >= 1260 || height >= 700 -> "720p"
            else -> "${height}p"
        })
    }
    val dynamicRange = when {
        "DV" in info.hdr -> "Dolby Vision"
        "HDR10+" in info.hdr -> "HDR10+"
        "HDR10" in info.hdr -> "HDR10"
        "HLG" in info.hdr -> "HLG"
        else -> null
    }
    val codec = info.audioCodec?.trim()?.takeIf { it.isNotEmpty() }
    val profile = info.audioProfile.orEmpty().uppercase(Locale.ROOT)
    val audioFormat = when {
        codec?.uppercase(Locale.ROOT) == "E-AC-3-JOC" || profile.contains("ATMOS") -> "Atmos"
        profile.contains("DTS:X") -> "DTS:X"
        codec == "E-AC-3" -> "Dolby Digital Plus"
        codec == "AC-3" -> "Dolby Digital"
        else -> codec
    }
    listOfNotNull(dynamicRange, audioFormat).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
    val language = info.audioLanguage?.trim()?.takeUnless {
        it.isBlank() || it.lowercase(Locale.ROOT) in setOf("und", "unknown", "zxx")
    }?.let(::languageCodeToName)
    val channels = when (val count = info.audioChannels) {
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        in 1..64 -> "${count}ch"
        else -> null
    }
    listOfNotNull(language, channels).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" ")) }
}

/** Non-focusable metadata: the remote continues straight to the playback actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerTechnicalBadges(info: PlayerMediaInfo, modifier: Modifier = Modifier) {
    val labels = technicalPlaybackBadges(info)
    if (labels.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(Color(0xCC10232E), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF648B9E), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
