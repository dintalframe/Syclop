package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.ServicePlaybackGrantRegistry
import org.junit.Assert.*
import org.junit.Test

class CachedDebridOriginTest {
    @Test fun `cached service URL is reusable only by its exact originating account scope`() {
        val registry = ServicePlaybackGrantRegistry({ 1L })
        val old = registry.observe(1, mapOf("alldebrid" to "account-A"), 0L, 0L)
        val cached = CachedDirectDebridResolve(DirectDebridResolveResult.Success("https://cdn.example.test/A", null, null), old, 1_000L)
        assertTrue(cached.isReusable(old, 1_001L))
        val otherAccount = registry.observe(1, mapOf("alldebrid" to "account-B"), 0L, 1L)
        assertFalse(cached.isReusable(otherAccount, 1_001L))
    }
    @Test fun `same service key on another viewer cannot rebind the old cached URL`() {
        val registry = ServicePlaybackGrantRegistry({ 1L })
        val old = registry.observe(1, mapOf("alldebrid" to "shared-account"), 0L, 0L)
        val cached = CachedDirectDebridResolve(DirectDebridResolveResult.Success("https://cdn.example.test/A", null, null), old, 1_000L)
        val otherViewer = registry.observe(2, mapOf("alldebrid" to "shared-account"), 1L, 0L)
        assertFalse(cached.isReusable(otherViewer, 1_001L))
    }
    @Test fun `away-back and replaced-back origins stay stale despite matching profile and key`() {
        val registry = ServicePlaybackGrantRegistry({ 1L })
        val old = registry.observe(1, mapOf("alldebrid" to "account-A"), 0L, 0L)
        val cached = CachedDirectDebridResolve(DirectDebridResolveResult.Success("https://cdn.example.test/A", null, null), old, 1_000L)
        val returned = registry.observe(1, mapOf("alldebrid" to "account-A"), 2L, 2L)
        assertFalse(cached.isReusable(returned, 1_001L))
    }
    @Test fun `expired or backwards-time cache entries cannot be regranted`() {
        val registry = ServicePlaybackGrantRegistry({ 1L })
        val origin = registry.observe(1, mapOf("alldebrid" to "account-A"))
        val cached = CachedDirectDebridResolve(DirectDebridResolveResult.Success("https://cdn.example.test/A", null, null), origin, 1_000L)
        assertFalse(cached.isReusable(origin, 999L))
        assertFalse(cached.isReusable(origin, 902_000L))
    }
}
