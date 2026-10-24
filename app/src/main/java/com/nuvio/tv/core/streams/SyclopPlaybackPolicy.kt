package com.nuvio.tv.core.streams

import java.net.URI
import java.security.MessageDigest

/** Product policy, not a preference or an addon-provided capability. */
object SyclopPlaybackPolicy {
    const val ALLOW_LOCAL_P2P = false
    const val CONNECTED_SERVICE_ONLY = true
    const val BLOCKED_MESSAGE = "Choose a file resolved through a connected streaming service. Local P2P playback is disabled in Syclop."

    fun requireLocalP2pAllowed() { check(ALLOW_LOCAL_P2P) { BLOCKED_MESSAGE } }
    fun isHttps(url: String): Boolean = runCatching {
        val parsed = URI(url)
        parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.port in setOf(-1, 443) && !parsed.host.equals("localhost", true) &&
            !parsed.host.endsWith(".local", true) && !parsed.host.contains(':') &&
            !parsed.host.matches(Regex("[0-9.]+"))
    }.getOrDefault(false)
}

/**
 * Private process-local grants are minted only after our authenticated service API succeeds.
 * Addon JSON, badges, URLs and navigation booleans cannot mint a grant. No credential or grant
 * is persisted or appended to a navigation URL. Process death requires a fresh service resolve.
 */
internal data class ServicePlaybackOrigin(val profileId: Int, val selectionRevision: Long, val accountRevision: Long)

internal class ServicePlaybackGrantRegistry(
    private val now: () -> Long,
    private val lifetimeMillis: Long = 15L * 60L * 1000L,
    private val requireLiveOrigin: Boolean = false
) {
    data class Scope internal constructor(val profileId: Int, internal val revision: Long, internal val credentials: Map<String, String>, internal val selectionRevision: Long, internal val accountRevision: Long)
    private data class Grant(val urlDigest: String, val provider: String, val scope: Scope, val expires: Long)
    private val _invalidations = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val invalidations: kotlinx.coroutines.flow.StateFlow<Long> = _invalidations
    private var liveOrigin: (() -> ServicePlaybackOrigin)? = null
    @Synchronized fun bindLiveOrigin(reader: () -> ServicePlaybackOrigin) { liveOrigin = reader }
    private fun live(scope: Scope): Boolean {
        val actual = liveOrigin?.invoke() ?: return !requireLiveOrigin
        return actual.accountRevision >= 0L && actual == ServicePlaybackOrigin(scope.profileId, scope.selectionRevision, scope.accountRevision)
    }
    private var revision = 0L
    private var current: Scope? = null
    private val grants = linkedMapOf<String, Grant>()

    @Synchronized fun observe(profileId: Int, credentials: Map<String, String>, selectionRevision: Long = 0L, accountRevision: Long = 0L): Scope {
        val fingerprints = credentials.filterValues { it.isNotBlank() }.mapValues { digest(it.value) }
        val old = current
        if (old == null || old.profileId != profileId || old.credentials != fingerprints || old.selectionRevision != selectionRevision || old.accountRevision != accountRevision) {
            revision += 1L; grants.clear(); _invalidations.value += 1L
            current = Scope(profileId, revision, fingerprints, selectionRevision, accountRevision)
        }
        return current!!
    }

    @Synchronized fun scopeIsCurrent(scope: Scope): Boolean = scope == current && live(scope)

    @Synchronized fun issue(url: String, provider: String, scope: Scope): Boolean {
        if (scope != current || !live(scope) || provider !in scope.credentials || !SyclopPlaybackPolicy.isHttps(url)) return false
        val deadline = runCatching { Math.addExact(now(), lifetimeMillis) }.getOrNull() ?: return false
        grants.entries.removeAll { it.value.expires <= now() }
        val key = digest(url)
        grants[key] = Grant(key, provider, scope, deadline)
        while (grants.size > 128) grants.remove(grants.keys.first())
        return true
    }

    @Synchronized fun permits(url: String, scope: Scope? = current): Boolean {
        if (scope == null || scope != current || !live(scope) || !SyclopPlaybackPolicy.isHttps(url)) return false
        val grant = grants[digest(url)] ?: return false
        return grant.scope == scope && grant.provider in scope.credentials && grant.expires > now()
    }

    @Synchronized fun clear() { revision += 1L; current = null; grants.clear(); _invalidations.value += 1L }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal object ConnectedServicePlaybackGrants {
    val registry = ServicePlaybackGrantRegistry({ System.nanoTime() / 1_000_000L }, requireLiveOrigin = true)
}
