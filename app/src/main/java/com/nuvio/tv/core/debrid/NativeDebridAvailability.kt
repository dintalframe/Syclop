package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.anime.NativeAnimeEpisodeMappings
import com.nuvio.tv.core.anime.NativeAnimeEpisodeProof
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamAvailabilityScope
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import java.util.Locale

/** Account observations are hints for selection, never playback grants. */
internal fun Stream.nativeAvailabilityFor(providerId: String, scope: StreamAvailabilityScope,
    nowMs: Long = System.currentTimeMillis()): StreamDebridCacheStatus? {
    val hash = localAvailabilityHash() ?: return null
    return nativeDebridAvailability.firstOrNull { it.providerId == providerId && it.infoHash == hash &&
        it.nativeScope == scope && it.isFreshNativeAvailability(nowMs) }
}

internal fun Stream.hasAccountLibraryEvidence(): Boolean =
    nativeDebridAvailability.any { it.libraryFile != null }

internal fun Stream.accountLibraryFile(providerId: String, scope: StreamAvailabilityScope) =
    localAvailabilityHash()?.let { hash ->
        nativeDebridAvailability.singleOrNull { it.providerId == providerId && it.infoHash == hash &&
            it.nativeScope == scope && it.state == StreamDebridCacheState.CACHED && it.libraryFile != null }?.libraryFile
    }

internal fun NativeDebridLibraryRequest.needsAnimeProof(stream: Stream): Boolean =
    videoId.requiresNativeAnimeProof(type, season, episode) || stream.requiresKitsuEpisodeProof(videoId)

internal fun NativeDebridLibraryRequest.animeProof(stream: Stream, mappings: NativeAnimeEpisodeMappings?): NativeAnimeEpisodeProof? {
    if (!needsAnimeProof(stream) || !validNativeAnimeContext(videoId, season, episode, type) ||
        !canSelectNativeAnimeFile(stream)) return null
    return mappings?.cachedProof(videoId)?.takeIf {
        stream.acceptsAnimeProof(videoId, it) && stream.matchesNativeAnimeSource(it)
    }
}

internal fun StreamDebridCacheStatus.isFreshNativeAvailability(nowMs: Long): Boolean {
    val checked = checkedAtMs ?: return false
    val expires = expiresAtMs ?: return false
    return nativeScope != null && infoHash != null && checked <= nowMs && nowMs < expires &&
        expires - checked in 1..60_000L
}

internal fun availabilityAccounts(stream: Stream, settings: DebridSettings): List<DebridServiceCredential> {
    if (!settings.canResolvePlayableLinks || stream.localAvailabilityHash() == null) return emptyList()
    return if (NativeDebridFallbackRouter.neutralHashSource(stream)) NativeDebridFallbackRouter.accounts(settings, true)
    else settings.resolverServices.filter { it.provider.id == DebridProviders.byId(stream.clientResolve?.service)?.id &&
        it.provider.supports(DebridProviderCapability.ClientResolve) }
}

internal fun Stream.allNativeAccountsUnavailable(settings: DebridSettings, scope: StreamAvailabilityScope,
    nowMs: Long): Boolean {
    val accounts = availabilityAccounts(this, settings)
    return accounts.isNotEmpty() && accounts.all {
        nativeAvailabilityFor(it.provider.id, scope, nowMs)?.state == StreamDebridCacheState.NOT_CACHED
    }
}

/** Normalize v1 BTIH only. No tracker, magnet or media transfer occurs here. */
internal fun canonicalAvailabilityHash(value: String?): String? {
    val hash = value?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (Regex("[a-f0-9]{40}").matches(hash)) return hash
    if (!Regex("[a-z2-7]{32}").matches(hash)) return null
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    var bits = 0
    var buffer = 0
    val bytes = ArrayList<Int>(20)
    for (char in hash) {
        buffer = (buffer shl 5) or alphabet.indexOf(char)
        bits += 5
        if (bits >= 8) { bits -= 8; bytes += (buffer ushr bits) and 255; buffer = buffer and ((1 shl bits) - 1) }
    }
    return bytes.joinToString("") { it.toString(16).padStart(2, '0') }
}
