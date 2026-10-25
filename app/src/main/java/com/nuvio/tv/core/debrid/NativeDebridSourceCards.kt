package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream

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
        val active = settings.activeResolverCredential ?: return false
        val validHash = stream.getEffectiveInfoHash()?.matches(hash) == true
        if (stream.needsLocalDebridResolve()) return validHash && active.provider.supports(DebridProviderCapability.LocalTorrentResolve)
        val provider = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        if (provider != active.provider.id) return false
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
            .replace(Regex("\\s*[|•]\\s*(?:[|•]\\s*)+"), " | ")
            .trim().trim('|', '•').trim()
    }?.filter(String::isNotEmpty)?.joinToString("\n")?.takeIf(String::isNotEmpty)

    /** A seed-only description must not hide the release title already supplied by the provider. */
    fun displayDescription(stream: Stream): String? =
        displayText(stream.getDisplayDescription()) ?: displayText(stream.title)

    fun isSeederBadge(name: String): Boolean = seederBadge.matches(name.trim()) || seedEmoji.matches(name.trim())
}
