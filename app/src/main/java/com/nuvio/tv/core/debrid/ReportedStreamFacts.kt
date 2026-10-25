package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/** Provider/filename claims only. Nothing here is native container or decoder proof. No I/O. */
object ReportedStreamFacts {
    private const val MAX_TEXT = 20_000
    private const val MAX_BITRATE = 10_000_000_000L
    private val defaults = DebridSettings()
    private val bitrate = Regex("(?<![A-Za-z0-9.+-])([0-9]{1,6}(?:\\.[0-9]{1,6})?)\\s*(Mbps|Mbit/s|kbps|kbit/s|Gbps|Gbit/s)(?![A-Za-z/])", RegexOption.IGNORE_CASE)
    private val fps = Regex("(?<![A-Za-z0-9])([0-9]{1,3}(?:\\.[0-9]{1,3})?)[ ._-]*fps(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val episode = Regex("(?:^|[^a-z0-9])(?:s[0-9]{1,3}(?:e[0-9]{1,4})+|[0-9]{1,3}x[0-9]{1,4}|e[0-9]{1,4})(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE)
    private val pack = Regex("(?:^|[^a-z0-9])(pack|collection|complete|anthology|seasons?|s[0-9]{1,2})(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE)

    private fun texts(stream: Stream): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        return listOfNotNull(stream.name, stream.title, stream.description, stream.quality,
            stream.behaviorHints?.filename, resolve?.filename, resolve?.torrentName,
            raw?.filename, raw?.torrentName, parsed?.resolution, parsed?.quality, parsed?.codec,
            parsed?.hdr?.take(8)?.joinToString(" "), parsed?.audio?.take(8)?.joinToString(" "),
            parsed?.channels?.take(8)?.joinToString(" ")).map { it.take(2_048) }
    }

    private fun text(stream: Stream): String = texts(stream).joinToString(" ").take(MAX_TEXT)

    fun qualitySummary(stream: Stream): String? {
        val text = text(stream)
        // Keep the established release parser, including behaviorHints.filename which its
        // normal text gatherer omits. Remove native facts so checked dimensions never leak
        // into a row explicitly labelled reported/unverified.
        val claim = stream.copy(name = null, title = text, description = null, quality = null,
            clientResolve = null, behaviorHints = null, debridCacheStatus = null, nativeFileMetadata = null)
        val facts = DirectDebridStreamFilter.facts(claim, defaults)
        val values = mutableListOf<String>()
        facts.resolution.takeUnless { it == DebridStreamResolution.UNKNOWN }?.let {
            values += if (it == DebridStreamResolution.P2160) "4K (2160p)" else it.label
        }
        facts.quality.takeUnless { it == DebridStreamQuality.UNKNOWN }?.label?.let(values::add)
        facts.encode.takeUnless { it == DebridStreamEncode.UNKNOWN }?.label?.let(values::add)
        val visual = facts.visualTags
        if (DebridStreamVisualTag.DV in visual) values += "Dolby Vision"
        listOf(DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HDR10,
            DebridStreamVisualTag.HLG, DebridStreamVisualTag.HDR, DebridStreamVisualTag.SDR)
            .firstOrNull { it in visual }?.label?.let(values::add)
        if (DebridStreamVisualTag.TEN_BIT in visual) values += "10bit"
        facts.audioTags.filterNot { it == DebridStreamAudioTag.UNKNOWN }.map { it.label }.distinct().take(3).let(values::addAll)
        // Explicit layout tokens only. A filename's 6ch claim does not prove an LFE.
        listOf("7.1", "6.1", "5.1", "2.1", "2.0", "1.0").filter {
            Regex("(?<![A-Za-z0-9.])${Regex.escape(it)}(?![A-Za-z0-9.])").containsMatchIn(text)
        }.take(3).let(values::addAll)
        if (Regex("(?i)(?<![A-Za-z0-9])stereo(?![A-Za-z0-9])").containsMatchIn(text) && "2.0" !in values) values += "Stereo"
        fps.findAll(text).take(8).mapNotNull { match ->
            match.groupValues[1].toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal(240) }
        }.firstOrNull()?.let { values += "${it.stripTrailingZeros().toPlainString()} fps" }
        return values.distinct().joinToString(" · ").takeIf(String::isNotEmpty)
    }

    /** Explicit bit-rate units; byte rates, FPS and impossible/negative values stay unknown. */
    fun explicitBitsPerSecond(stream: Stream): Long? = bitrate.findAll(text(stream)).take(8).mapNotNull { match ->
        if ('B' in match.groupValues[2]) return@mapNotNull null // MBps denotes bytes, not bits.
        val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT).first()) {
            'k' -> BigDecimal(1_000); 'g' -> BigDecimal(1_000_000_000); else -> BigDecimal(1_000_000)
        }
        val value = match.groupValues[1].toBigDecimalOrNull()?.multiply(multiplier) ?: return@mapNotNull null
        if (value < BigDecimal.ONE || value > BigDecimal(MAX_BITRATE)) null else value.toLong()
    }.firstOrNull()

    /** Display/ranking estimate only for a confirmed single movie; never a season/episode pack. */
    fun averageBitsPerSecond(stream: Stream): Long? {
        val millis = stream.reportedMovieRuntimeMillis?.takeIf { it in 60_000L..86_400_000L } ?: return null
        val title = text(stream)
        if (stream.clientResolve?.season != null || stream.clientResolve?.episode != null || pack.containsMatchIn(title) || episode.containsMatchIn(title)) return null
        val raw = stream.clientResolve?.stream?.raw
        // A folder total cannot identify the selected file even for a movie catalogue entry.
        if (raw?.folderSize?.takeIf { it > 0L } != null) return null
        val bytes = (raw?.size ?: stream.behaviorHints?.videoSize ?: StreamTextSizeParser.sizeBytesFromStreamText(stream))
            ?.takeIf { it > 0L } ?: return null
        val value = BigInteger.valueOf(bytes).multiply(BigInteger.valueOf(8_000L)).divide(BigInteger.valueOf(millis))
        return value.takeIf { it.signum() > 0 && it <= BigInteger.valueOf(MAX_BITRATE) }?.toLong()
    }

    fun rankingBitsPerSecond(stream: Stream): Long? = explicitBitsPerSecond(stream) ?: averageBitsPerSecond(stream)

    fun bitrateLabel(stream: Stream): String? = explicitBitsPerSecond(stream)?.let {
        "Reported bitrate: ${StreamBitrateFacts.formattedMbps(it)} Mbps (unverified)"
    } ?: averageBitsPerSecond(stream)?.let {
        "Reported avg: ${StreamBitrateFacts.formattedMbps(it)} Mbps (reported size / movie runtime; unverified)"
    }
}
