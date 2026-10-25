package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import java.math.BigInteger
import java.util.Locale

object StreamBitrateFacts {
    /** Container-average estimate, including audio/subtitles/overhead; not measured video bitrate. */
    fun estimatedBitsPerSecond(stream: Stream): Long? {
        val file = stream.nativeFileMetadata ?: return null
        val bytes = file.selectedFileBytes?.takeIf { it > 0L } ?: return null
        val millis = file.durationMillis?.takeIf { it > 0L } ?: return null
        file.durationOrigin ?: return null
        val value = BigInteger.valueOf(bytes).multiply(BigInteger.valueOf(8_000L)).divide(BigInteger.valueOf(millis))
        return value.takeIf { it.signum() > 0 && it.bitLength() <= 63 }?.toLong()
    }
    fun formattedMbps(bitsPerSecond: Long): String = String.format(Locale.ROOT, "%.1f", bitsPerSecond / 1_000_000.0)
    fun label(stream: Stream): String = estimatedBitsPerSecond(stream)?.let { "Estimated avg ${formattedMbps(it)} Mbps" }
        ?: "Bitrate unknown"
    private val claimPattern = Regex("(?i)(?<![0-9.])([0-9]{1,4}(?:\\.[0-9]{1,3})?)\\s*(?:Mbps|Mbit/s)(?![A-Za-z])")
    /** Explicit text claims remain unverified and do not participate in trusted ranking. */
    fun providerClaimMbps(stream: Stream): Double? {
        if (estimatedBitsPerSecond(stream) != null) return null
        val text = listOfNotNull(stream.title, stream.description, stream.name).joinToString(" ").take(20_000)
        return claimPattern.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    }
    /** Native selected-file estimates precede reported claims in either direction; unknown stays last. */
    fun compareConfidenceRanked(leftNative: Long?, leftReported: Long?, rightNative: Long?, rightReported: Long?, descending: Boolean): Int {
        fun tier(native: Long?, reported: Long?) = if (native != null) 0 else if (reported != null) 1 else 2
        val confidence = tier(leftNative, leftReported).compareTo(tier(rightNative, rightReported))
        return if (confidence != 0) confidence else compareNullable(leftNative ?: leftReported, rightNative ?: rightReported, descending)
    }

    /** Unknown stays last for both directions, including malformed or zero metadata. */
    fun compareNullable(left: Long?, right: Long?, descending: Boolean): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        descending -> right.compareTo(left)
        else -> left.compareTo(right)
    }
}
