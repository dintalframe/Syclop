package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
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
        var first: String? = null
        val result = NativeDebridFallbackRouter.resolve(accounts(), { true }, {
            if (first == null) first = it
            visited.add(it)
        }) {
            NativeDebridAttempt(if (it.provider.id == first) DirectDebridResolveResult.NotCached
                else DirectDebridResolveResult.Success("https://cdn.example.test/file", "movie.mkv", 123L))
        }
        assertEquals(2, visited.size)
        assertEquals(visited.last(), result.providerId)
        assertEquals(123L, (result.result as DirectDebridResolveResult.Success).videoSize)
    }
    @Test fun `service failure including rejected service does not block another connected service`() = runTest {
        val visited = mutableListOf<String>()
        val result = NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) {
            NativeDebridAttempt(DirectDebridResolveResult.Error)
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
            assertEquals(1, visited.size)
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
        assertEquals(1, visited.size)
    }
    @Test fun `cancellation never turns into service failure or starts another provider`() = runTest {
        val visited = mutableListOf<String>()
        try {
            NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) { throw CancellationException("cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, visited.size)
    }
    @Test fun `raw valid hash is eligible while direct URLs and malformed hashes are not`() {
        val raw = Stream(null, null, null, null, null, "a".repeat(40), null, null, null, "Synthetic", null)
        assertTrue(NativeDebridFallbackRouter.neutralHashSource(raw))
        assertFalse(NativeDebridFallbackRouter.neutralHashSource(raw.copy(infoHash = "bad")))
        assertFalse(NativeDebridFallbackRouter.neutralHashSource(raw.copy(url = "https://provider.example.test/private")))
        assertFalse(NativeDebridFallbackRouter.neutralHashSource(raw.copy(clientResolve = io.mockk.mockk(relaxed = true))))
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

    @Test fun `expired account and rate limit are isolated but wrong episode aborts globally`() = runTest {
        for (reason in listOf(DirectDebridFailureReason.AUTH_EXPIRED, DirectDebridFailureReason.RATE_LIMITED,
            DirectDebridFailureReason.TIMED_OUT, DirectDebridFailureReason.WRONG_EPISODE)) {
            val visited = mutableListOf<String>()
            var first: String? = null
            val route = NativeDebridFallbackRouter.resolve(accounts(), { true }, {
                if (first == null) first = it
                visited.add(it)
            }) {
                NativeDebridAttempt(if (it.provider.id == first)
                    DirectDebridResolveResult.Failure(DirectDebridFailure(reason, it.provider.id))
                    else DirectDebridResolveResult.Success("https://cdn.example.test/file", "movie.mkv", 123L))
            }
            if (reason == DirectDebridFailureReason.WRONG_EPISODE) {
                assertEquals(1, visited.size)
                assertTrue(route.result is DirectDebridResolveResult.Failure)
            } else {
                assertEquals(2, visited.size)
                assertTrue(route.result is DirectDebridResolveResult.Success)
            }
        }
    }

    @Test fun `first valid service winner cancels slow loser before returning and prevents third dispatch`() = runTest {
        val visited = mutableListOf<String>()
        var first: String? = null
        var loserCancelled = false
        val start = testScheduler.currentTime
        val route = NativeDebridFallbackRouter.resolve(accounts(), { true }, {
            if (first == null) first = it
            visited.add(it)
        }) { account ->
            if (account.provider.id == first) {
                try {
                    delay(5000)
                    NativeDebridAttempt(DirectDebridResolveResult.NotCached)
                } finally {
                    loserCancelled = !currentCoroutineContext().isActive
                }
            } else NativeDebridAttempt(DirectDebridResolveResult.Success("https://cdn.example.test/winner", "movie.mkv", 42L))
        }
        assertEquals(2, visited.size)
        assertEquals(visited[1], route.providerId)
        assertTrue(route.result is DirectDebridResolveResult.Success)
        assertTrue(loserCancelled)
        assertTrue(testScheduler.currentTime - start < 5000)
    }

    @Test fun `every search considers the distinct connected pool and rotates the leading account fairly`() = runTest {
        val leads = mutableListOf<String>()
        repeat(4) {
            val visited = mutableListOf<String>()
            val route = NativeDebridFallbackRouter.resolve(accounts() + accounts().first(), { true }, visited::add) {
                NativeDebridAttempt(DirectDebridResolveResult.NotCached)
            }
            assertEquals(4, visited.size)
            assertEquals(4, visited.toSet().size)
            assertEquals(DirectDebridResolveResult.NotCached, route.result)
            leads += visited.first()
        }
        assertEquals(accounts().map { it.provider.id }.toSet(), leads.toSet())
    }

    @Test fun `no more than three service requests run concurrently and queued fourth can win`() = runTest {
        var active = 0
        var maximum = 0
        var dispatched = 0
        val visited = mutableListOf<String>()
        val route = NativeDebridFallbackRouter.resolve(accounts(), { true }, visited::add) { account ->
            val ordinal = ++dispatched
            active++
            maximum = maxOf(maximum, active)
            try {
                delay(1000)
                NativeDebridAttempt(if (ordinal == 4)
                    DirectDebridResolveResult.Success("https://cdn.example.test/fourth", "movie.mkv", 42L)
                    else DirectDebridResolveResult.NotCached)
            } finally { active-- }
        }
        assertEquals(4, dispatched)
        assertEquals(3, maximum)
        assertEquals(0, active)
        assertEquals(visited.last(), route.providerId)
        assertTrue(route.result is DirectDebridResolveResult.Success)
    }

    @Test fun `typed all-failure priority is independent of child completion order`() = runTest {
        var ordinal = 0
        val reasons = listOf(DirectDebridFailureReason.PROCESSING, DirectDebridFailureReason.RATE_LIMITED,
            DirectDebridFailureReason.UNAVAILABLE)
        val route = NativeDebridFallbackRouter.resolve(accounts().take(3), { true }, {}) { account ->
            val reason = reasons[ordinal++]
            delay(if (reason == DirectDebridFailureReason.PROCESSING) 1000 else 1)
            NativeDebridAttempt(DirectDebridResolveResult.Failure(DirectDebridFailure(reason, account.provider.id)))
        }
        assertEquals(3, ordinal)
        assertEquals(DirectDebridFailureReason.PROCESSING, (route.result as DirectDebridResolveResult.Failure).detail.reason)
    }

    @Test fun `ordinary child failure cannot cancel another valid captured service`() = runTest {
        var first: String? = null
        val visited = mutableListOf<String>()
        val route = NativeDebridFallbackRouter.resolve(accounts(), { true }, {
            if (first == null) first = it
            visited.add(it)
        }) {
            if (it.provider.id == first) throw IOException("synthetic service failure")
            NativeDebridAttempt(DirectDebridResolveResult.Success("https://cdn.example.test/winner", "movie.mkv", 42L))
        }
        assertEquals(2, visited.size)
        assertEquals(visited[1], route.providerId)
        assertTrue(route.result is DirectDebridResolveResult.Success)
    }

    @Test fun `external cancellation cancels in-flight request and every pending stagger`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        var cancelled = 0
        val job = launch {
            NativeDebridFallbackRouter.resolve(accounts(), { true }, {}) {
                attempts++
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
        assertEquals(1, cancelled)
    }

    @Test fun `changed owner rejects even an otherwise valid service winner`() = runTest {
        var current = true
        var attempted = 0
        try {
            NativeDebridFallbackRouter.resolve(accounts(), { current }, {}) {
                attempted++
                current = false
                NativeDebridAttempt(DirectDebridResolveResult.Success("https://cdn.example.test/winner", "movie.mkv", 42L))
            }
            fail("Changed viewer accepted a late winner")
        } catch (_: CancellationException) { }
        assertEquals(1, attempted)
    }

    @Test fun `single captured service path dispatches only once without rotation or stagger`() = runTest {
        val visited = mutableListOf<String>()
        val start = testScheduler.currentTime
        val route = NativeDebridFallbackRouter.resolve(accounts().take(1), { true }, visited::add) {
            NativeDebridAttempt(DirectDebridResolveResult.NotCached)
        }
        assertEquals(listOf(accounts().first().provider.id), visited)
        assertEquals(DirectDebridResolveResult.NotCached, route.result)
        assertEquals(start, testScheduler.currentTime)
    }
    @Test fun `invalid typed success cannot win or prevent another service resolving`() = runTest {
        var attempts = 0
        val route = NativeDebridFallbackRouter.resolve(accounts(), { true }, {}) {
            attempts++
            NativeDebridAttempt(DirectDebridResolveResult.Success(
                if (attempts == 1) "file:///invalid-provider-response" else "https://cdn.example.test/winner", "movie.mkv", 42L))
        }
        assertEquals(2, attempts)
        assertEquals("https://cdn.example.test/winner", (route.result as DirectDebridResolveResult.Success).url)
    }

}
