package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeDebridFallbackRouterTest {
    private fun settings() = DebridSettings(enabled = true, allDebridApiKey = "synthetic-ad", realDebridApiKey = "synthetic-rd",
        torboxApiKey = "synthetic-tb", premiumizeApiKey = "synthetic-pm", preferredResolverProviderId = "alldebrid")
    private fun accounts() = NativeDebridFallbackRouter.accounts(settings(), true)

    @Test fun `preferred AD then RD then remaining registered accounts each once`() {
        assertEquals(listOf("alldebrid", "realdebrid", "torbox", "premiumize"), accounts().map { it.provider.id })
        assertEquals("alldebrid", settings().activeResolverProviderId)
    }
    @Test fun `disabled fallback and background preparation stay on preferred account`() {
        assertEquals(listOf("alldebrid"), NativeDebridFallbackRouter.accounts(settings(), false).map { it.provider.id })
        assertEquals(listOf("alldebrid"), NativeDebridFallbackRouter.accounts(settings().copy(connectedServiceFallbackEnabled = false), true).map { it.provider.id })
    }
    @Test fun `only connected providers participate and preferred RD is not duplicated`() {
        assertEquals(listOf("realdebrid", "premiumize"), NativeDebridFallbackRouter.accounts(settings().copy(
            preferredResolverProviderId = "realdebrid", allDebridApiKey = "", torboxApiKey = ""), true).map { it.provider.id })
    }
    @Test fun `not cached then success stops immediately and retains actual successful provider`() = runTest {
        val visited = mutableListOf<String>()
        val result = NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) {
            NativeDebridAttempt(if (it.provider.id == "alldebrid") DirectDebridResolveResult.NotCached
                else DirectDebridResolveResult.Success("https://cdn.example.test/file", "movie.mkv", 123L))
        }
        assertEquals(listOf("alldebrid", "realdebrid"), visited)
        assertEquals("realdebrid", result.providerId)
        assertEquals(123L, (result.result as DirectDebridResolveResult.Success).videoSize)
    }
    @Test fun `service failure including rejected service does not block another connected service`() = runTest {
        val visited = mutableListOf<String>()
        val result = NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) {
            NativeDebridAttempt(if (it.provider.id == "alldebrid") DirectDebridResolveResult.Error else DirectDebridResolveResult.NotCached)
        }
        assertEquals(4, visited.size)
        assertEquals(DirectDebridResolveResult.Error, result.result)
    }
    @Test fun `all services reporting not ready remains not cached rather than inventing a service error`() = runTest {
        val result = NativeDebridFallbackRouter.resolve(accounts(), { true }, {}) { NativeDebridAttempt(DirectDebridResolveResult.NotCached) }
        assertEquals(DirectDebridResolveResult.NotCached, result.result)
    }
    @Test fun `stale ambiguous selection and missing captured credential abort globally`() = runTest {
        for (terminal in listOf(DirectDebridResolveResult.Stale, DirectDebridResolveResult.MissingApiKey)) {
            val visited = mutableListOf<String>()
            assertEquals(terminal, NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) { NativeDebridAttempt(terminal) }.result)
            assertEquals(listOf("alldebrid"), visited)
        }
    }
    @Test fun `profile or credential change after a service result prevents any second request`() = runTest {
        var current = true
        val visited = mutableListOf<String>()
        try {
            NativeDebridFallbackRouter.resolve(accounts(), { current }, visited::add) {
                current = false; NativeDebridAttempt(DirectDebridResolveResult.NotCached)
            }
            fail("Expected stale operation cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("alldebrid"), visited)
    }
    @Test fun `cancellation never turns into service failure or starts another provider`() = runTest {
        val visited = mutableListOf<String>()
        try {
            NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) { throw CancellationException("cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("alldebrid"), visited)
    }
    @Test fun `raw valid hash is eligible while direct URLs and malformed hashes are not`() {
        val raw = Stream(null, null, null, null, null, "a".repeat(40), null, null, null, "Synthetic", null)
        assertTrue(NativeDebridFallbackRouter.neutralHashSource(raw))
        assertFalse(NativeDebridFallbackRouter.neutralHashSource(raw.copy(infoHash = "bad")))
        assertFalse(NativeDebridFallbackRouter.neutralHashSource(raw.copy(url = "https://provider.example.test/private")))
    }

    @Test fun `processing remains processing when later service has unknown availability`() = runTest {
        val result = NativeDebridFallbackRouter.resolve(accounts().take(2), { true }, {}) {
            NativeDebridAttempt(DirectDebridResolveResult.Failure(DirectDebridFailure(
                if (it.provider.id == "alldebrid") DirectDebridFailureReason.PROCESSING else DirectDebridFailureReason.UNAVAILABLE,
                it.provider.id)))
        }
        assertEquals("alldebrid", result.providerId)
        assertEquals(DirectDebridFailureReason.PROCESSING, (result.result as DirectDebridResolveResult.Failure).detail.reason)
    }

    @Test fun `expired account and rate limit may use another captured service but wrong episode cannot`() = runTest {
        for (reason in listOf(DirectDebridFailureReason.AUTH_EXPIRED, DirectDebridFailureReason.RATE_LIMITED,
            DirectDebridFailureReason.TIMED_OUT, DirectDebridFailureReason.WRONG_EPISODE)) {
            val visited = mutableListOf<String>()
            val route = NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) {
                NativeDebridAttempt(if (it.provider.id == "alldebrid")
                    DirectDebridResolveResult.Failure(DirectDebridFailure(reason, "alldebrid"))
                    else DirectDebridResolveResult.Success("https://cdn.example.test/file", "movie.mkv", 123L))
            }
            if (reason == DirectDebridFailureReason.WRONG_EPISODE) {
                assertEquals(listOf("alldebrid"), visited)
                assertTrue(route.result is DirectDebridResolveResult.Failure)
            } else {
                assertEquals(listOf("alldebrid", "realdebrid"), visited)
                assertTrue(route.result is DirectDebridResolveResult.Success)
            }
        }
    }
}
