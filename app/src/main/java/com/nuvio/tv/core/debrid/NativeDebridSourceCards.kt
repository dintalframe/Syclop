package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import java.util.Locale

/** Display eligibility only. Native service resolution and final playback grants remain required. */
object NativeDebridSourceCards {
    private val hash = Regex("[a-fA-F0-9]{40}|[a-zA-Z2-7]{32}")
    private val seedCount = Regex("(?i)(?:^|(?<=[|•]))\\s*(?:seeds?|seeders?)\\s*[:=]\\s*[0-9][0-9,.]*\\s*(?=$|[|•])")
    private val countThenSeeds = Regex("(?i)(?:^|(?<=[|•]))\\s*[0-9][0-9,.]*\\s+seed(?:er)?s?\\s*(?=$|[|•])")
    private val seedEmoji = Regex("👤\\s*[0-9][0-9,.]*\\s*")
    private val seederBadge = Regex("(?i)^(?:(?:seeds?|seeders?)(?:\\s*[:=]?\\s*[0-9][0-9,.]*)?|[0-9][0-9,.]*\\s+(?:seeds?|seeders?))$")

    fun eligible(stream: Stream, settings: DebridSettings): Boolean =
        eligible(stream, settings) { ConnectedServicePlaybackGrants.registry.permits(it) }

    internal fun eligible(stream: Stream, settings: DebridSettings, nativeGrant: (String) -> Boolean): Boolean {
        // Cloud-library grants are independent of the unresolved-source resolver toggle or
        // preferred provider. Only this private native registry can establish a service file.
        stream.getStreamUrl()?.let { if (nativeGrant(it)) return true }
        if (!settings.canResolvePlayableLinks) return false
        val connected = settings.resolverServices
        if (connected.isEmpty()) return false
        val validHash = stream.getEffectiveInfoHash()?.matches(hash) == true
        if (stream.clientResolve == null && stream.needsLocalDebridResolve()) return validHash && connected.any { it.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
        val provider = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        if (connected.none { it.provider.id == provider && it.provider.supports(DebridProviderCapability.ClientResolve) }) return false
        val type = stream.clientResolve?.type
        val hoster = provider in setOf(DebridProviders.REAL_DEBRID_ID, DebridProviders.ALL_DEBRID_ID) &&
            (type.equals("hoster", true) || type.equals("http", true))
        if (hoster) return stream.getStreamUrl()?.let { value -> runCatching {
            val uri = java.net.URI(value)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }.getOrDefault(false) } == true
        return stream.isDirectDebrid() && stream.getStreamUrl() == null && validHash
    }

    /** Remove labelled count fields/known provider emoji, never generic film-title words. */
    fun displayText(value: String?): String? = value?.lineSequence()?.map { line ->
        seedEmoji.replace(countThenSeeds.replace(seedCount.replace(line, ""), ""), "")
            .replace(Regex("(?i)\\bMagnify\\s+Scrapers\\b"), "")
            .replace(Regex("\\s*[|•]\\s*(?:[|•]\\s*)+"), " | ")
            .trim().trim('|', '•').trim()
    }?.filter(String::isNotEmpty)?.joinToString("\n")?.takeIf(String::isNotEmpty)

    /** A seed-only description must not hide the release title already supplied by the provider. */
    fun displayDescription(stream: Stream): String? =
        displayText(stream.getDisplayDescription()) ?: displayText(stream.title)

    data class CompactDisplay(val title: String?, val description: String?)

    /** A bounded card projection only: the full text and every resolver field stay on Stream. */
    fun compactDisplay(stream: Stream, fileSizeShownInBadges: Boolean = false): CompactDisplay {
        val badges = compactBadges(stream.badges)
        val badgeKeys = badges.mapTo(hashSetOf()) { displayKey(it.name) }
        val knownHash = stream.getEffectiveInfoHash()
        fun pureHash(value: String) = Regex("(?i)[a-f0-9]{40}").matches(value) ||
            knownHash?.equals(value, ignoreCase = true) == true
        val seen = hashSetOf<String>()
        val lines = displayDescription(stream).orEmpty().lineSequence().take(32)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() && !pureHash(it) && !readyLine.matches(it) }
            .filter { seen.add(displayKey(it)) }.toList()
        val originalName = displayText(stream.getDisplayNameOrNull())?.replace(Regex("\\s+"), " ")
            ?.takeUnless(::pureHash)
        val redundantServiceName = badges.any { it.tagStyle.startsWith("native-service:") } &&
            originalName?.let { serviceHeading.matches(it) } == true
        val release = lines.firstOrNull { displayKey(it) !in badgeKeys && !sizeLine.matches(it) }
        val title = if (redundantServiceName || originalName == null) release else originalName
        val titleKey = title?.let(::displayKey)
        val details = lines.filterNot { line ->
            val key = displayKey(line)
            key == titleKey || key in badgeKeys || fileSizeShownInBadges && sizeLine.matches(line)
        }.joinToString(" · ").takeIf { it.isNotBlank() }
        return CompactDisplay(title, details)
    }

    /** Native provider labels stay first; overlapping generic tags add no extra rows. */
    fun compactBadges(badges: List<StreamBadge>): List<StreamBadge> {
        val visible = badges.filterNot { isSeederBadge(it.name) || !visibleAddonName(it.name) }
            .distinctBy { if (it.name.isBlank()) it.imageURL else it.name.trim().lowercase(Locale.ROOT) }
        fun key(value: String) = value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9+]"), "")
        val names = visible.mapTo(hashSetOf()) { key(it.name) }
        return visible.filterNot { badge -> when (key(badge.name)) {
            "HDR" -> names.any { it.startsWith("HDR10") || it.startsWith("DOLBYVISION") || it == "DV" || it == "HLG" }
            "DV" -> names.any { it.startsWith("DOLBYVISION") }
            "DD" -> names.any { it.startsWith("DD+") || it.startsWith("DDP") || it.startsWith("DOLBYDIGITALPLUS") || it == "EAC3" }
            else -> false
        } }.sortedBy { if (it.tagStyle.startsWith("native-service:")) 0 else 1 }
    }

    private val readyLine = Regex("(?i)^(?:Not\\s+)?Ready(?:\\s*\\([A-Za-z][A-Za-z /+.-]{0,79}\\))?$")
    private val sizeLine = Regex("(?i)^[0-9]+(?:[.,][0-9]+)?\\s*(?:[KMGT]i?B|bytes?)$")
    private val serviceHeading = Regex("(?i)^(?:(?:4K|QHD|FHD|HD|Direct)\\s+)?(?:RD|TB|AD|PM|Automatic|Debrid)(?:\\s*/\\s*(?:RD|TB|AD|PM))*\\s+Instant$")
    private fun displayKey(value: String) = value.trim().replace(Regex("(?i)\\.(?:mkv|mp4|avi|m4v|ts)$"), "")
        .filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)

    /** Hide this aggregator's branding without changing source identity or discovery. */
    fun visibleAddonName(name: String?): Boolean = !name.orEmpty().trim()
        .replace(Regex("\\s+"), " ").equals("Magnify Scrapers", ignoreCase = true)

    fun isSeederBadge(name: String): Boolean = seederBadge.matches(name.trim()) || seedEmoji.matches(name.trim())
}
