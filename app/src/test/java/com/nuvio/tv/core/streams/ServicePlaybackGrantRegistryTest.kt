package com.nuvio.tv.core.streams

import org.junit.Assert.*
import org.junit.Test

class ServicePlaybackGrantRegistryTest {
    private val url = "https://cdn.example.test/private/selected-file.mkv?token=private-url-value"
    private var now = 1_000L
    private val registry = ServicePlaybackGrantRegistry({ now }, 600L)
    private fun scope(profile: Int = 1, key: String = "synthetic-account-key") = registry.observe(profile, mapOf("alldebrid" to key))

    @Test fun `addon URLs and forged service names cannot mint authorization`() {
        val binding = scope()
        assertFalse(registry.permits(url, binding))
        assertFalse(registry.issue(url, "Unconnected Service", binding))
        assertFalse(registry.permits(url, binding))
    }
    @Test fun `trusted connected service result authorizes only its exact HTTPS URL`() {
        val binding = scope()
        assertTrue(registry.issue(url, "alldebrid", binding))
        assertTrue(registry.permits(url, binding))
        assertFalse(registry.permits(url.replace("selected-file", "different-file"), binding))
        assertFalse(registry.permits(url + "&extra=1", binding))
    }
    @Test fun `profile away and back invalidates the old private grant`() {
        val before = scope(); assertTrue(registry.issue(url, "alldebrid", before))
        scope(2); val returned = scope(1)
        assertFalse(registry.permits(url, returned))
        assertFalse(registry.issue(url, "alldebrid", before))
    }
    @Test fun `synchronous selection revision catches away-back even when profile flow conflates it`() {
        val old = registry.observe(1, mapOf("alldebrid" to "key"), 0L)
        assertTrue(registry.issue(url, "alldebrid", old))
        val returned = registry.observe(1, mapOf("alldebrid" to "key"), 2L)
        assertFalse(registry.permits(url, returned))
        assertFalse(registry.issue(url, "alldebrid", old))
    }
    @Test fun `credential replacement and disconnect invalidate previously playable URLs`() {
        val before = scope(); assertTrue(registry.issue(url, "alldebrid", before))
        val changed = scope(key = "different-account-key")
        assertFalse(registry.permits(url, changed))
        assertFalse(registry.issue(url, "alldebrid", before))
        assertTrue(registry.issue(url, "alldebrid", changed))
        val disconnected = registry.observe(1, mapOf("alldebrid" to ""))
        assertFalse(registry.permits(url, disconnected))
    }
    @Test fun `expired and cold process cached links fail closed`() {
        val binding = scope(); assertTrue(registry.issue(url, "alldebrid", binding))
        now = 1_599L; assertTrue(registry.permits(url, binding))
        now = 1_600L; assertFalse(registry.permits(url, binding))
        val freshProcess = ServicePlaybackGrantRegistry({ now })
        val freshScope = freshProcess.observe(1, mapOf("alldebrid" to "synthetic-account-key"))
        assertFalse(freshProcess.permits(url, freshScope))
    }
    @Test fun `magnet torrent file loopback and insecure URLs cannot be trusted playback outputs`() {
        val binding = scope()
        listOf("magnet:?xt=urn:btih:123", "torrent://123", "file:///private/file.mkv", "http://cdn.example.test/a",
            "https://localhost/a", "https://127.0.0.1/a", "https://[::1]/a", "https://tv.local/a",
            "https://user:secret@cdn.example.test/a", "https://cdn.example.test:444/a").forEach {
            assertFalse(it, registry.issue(it, "alldebrid", binding))
            assertFalse(it, registry.permits(it, binding))
        }
    }
    @Test fun `late service response from an obsolete account scope is rejected`() {
        val pending = scope()
        scope(2)
        assertFalse(registry.issue(url, "alldebrid", pending))
        assertFalse(registry.permits(url))
    }
    @Test fun `private grants are bounded and clearing them invalidates navigation replays`() {
        val binding = scope()
        repeat(129) { assertTrue(registry.issue("https://cdn.example.test/$it.mkv", "alldebrid", binding)) }
        assertFalse(registry.permits("https://cdn.example.test/0.mkv", binding))
        assertTrue(registry.permits("https://cdn.example.test/128.mkv", binding))
        registry.clear(); assertFalse(registry.permits("https://cdn.example.test/128.mkv", binding))
    }
    @Test fun `sync final boundary rejects selection changes before flow collectors run`() {
        var live = ServicePlaybackOrigin(1, 0L, 0L)
        val guarded = ServicePlaybackGrantRegistry({ now }, requireLiveOrigin = true)
        guarded.bindLiveOrigin { live }
        val binding = guarded.observe(1, mapOf("alldebrid" to "key"), 0L, 0L)
        assertTrue(guarded.issue(url, "alldebrid", binding))
        live = ServicePlaybackOrigin(1, 2L, 0L) // A -> B -> A, no observe() / flow delivery.
        assertFalse(guarded.permits(url))
        assertFalse(guarded.issue(url, "alldebrid", binding))
    }
    @Test fun `account edit in progress and replacement-back invalidate final handoff synchronously`() {
        var live = ServicePlaybackOrigin(1, 0L, 0L)
        val guarded = ServicePlaybackGrantRegistry({ now }, requireLiveOrigin = true)
        guarded.bindLiveOrigin { live }
        val binding = guarded.observe(1, mapOf("alldebrid" to "key"), 0L, 0L)
        assertTrue(guarded.issue(url, "alldebrid", binding))
        live = ServicePlaybackOrigin(1, 0L, -1L)
        assertFalse(guarded.permits(url))
        live = ServicePlaybackOrigin(1, 0L, 2L)
        assertFalse(guarded.permits(url))
    }
    @Test fun `strict production registry cannot authorize before live scope reader is bound`() {
        val guarded = ServicePlaybackGrantRegistry({ now }, requireLiveOrigin = true)
        val binding = guarded.observe(1, mapOf("alldebrid" to "key"))
        assertFalse(guarded.issue(url, "alldebrid", binding))
        assertFalse(guarded.permits(url))
    }
    @Test fun `clock overflow cannot turn a grant into an unbounded authorization`() {
        val binding = scope(); now = Long.MAX_VALUE - 1
        assertFalse(registry.issue(url, "alldebrid", binding))
    }
    @Test fun `retained torrent hash cannot override an HTTPS service playback URL`() {
        val resolved = com.nuvio.tv.domain.model.Stream(name = "service file", title = null, description = null,
            url = "HTTPS://cdn.example.test/a.mkv", ytId = null, infoHash = "a".repeat(40), fileIdx = 1,
            externalUrl = null, behaviorHints = null, addonName = "untrusted display label", addonLogo = null)
        assertEquals("HTTPS://cdn.example.test/a.mkv", resolved.getStreamUrl())
        assertFalse(resolved.isTorrent())
        assertFalse(resolved.needsLocalDebridResolve())
        assertTrue(SyclopPlaybackPolicy.isHttps(resolved.getStreamUrl()!!))
        assertFalse(SyclopPlaybackPolicy.ALLOW_LOCAL_P2P)
        assertTrue(SyclopPlaybackPolicy.CONNECTED_SERVICE_ONLY)
        assertThrows(IllegalStateException::class.java) { SyclopPlaybackPolicy.requireLocalP2pAllowed() }
    }
}
