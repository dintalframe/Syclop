package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.ServicePlaybackGrantRegistry
import com.nuvio.tv.core.streams.ServicePlaybackOrigin
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DirectDebridResolutionBudgetTest {
    @Test fun `per service deadline tries the next captured account within the overall deadline`() = runTest {
        val h = Harness(this)
        coEvery { h.ad.resolve(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { h.rd.resolve(any(), any(), any(), any()) } returns h.success
        val request = async { h.resolver.resolve(source(), null, null) }
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(h.success, request.await())
        coVerify(exactly = 1) { h.ad.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 1) { h.rd.resolve(any(), any(), any(), any()) }
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test fun `overall deadline bounds all fallback attempts and never grants a timed out result`() = runTest {
        val h = Harness(this, overall = 1_500L)
        coEvery { h.ad.resolve(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { h.rd.resolve(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        val result = h.resolver.resolve(source(), null, null)
        assertEquals(DirectDebridFailureReason.TIMED_OUT, (result as DirectDebridResolveResult.Failure).detail.reason)
        assertEquals(1_500L, testScheduler.currentTime)
        assertFalse(h.resolver.isPlaybackAuthorized(h.success.url))
        coVerify(exactly = 1) { h.ad.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 1) { h.rd.resolve(any(), any(), any(), any()) }
    }

    @Test fun `cancelling the only waiter cancels its service work and cannot start fallback`() = runTest {
        val h = Harness(this)
        val cancelled = CompletableDeferred<Unit>()
        coEvery { h.ad.resolve(any(), any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val request = async { h.resolver.resolve(source(), null, null) }
        runCurrent(); request.cancelAndJoin(); runCurrent()
        assertTrue(cancelled.isCompleted)
        advanceTimeBy(4_000); runCurrent()
        coVerify(exactly = 0) { h.rd.resolve(any(), any(), any(), any()) }
    }

    @Test fun `a second waiter retains the bounded shared service operation when first cancels`() = runTest {
        val h = Harness(this)
        val response = CompletableDeferred<DirectDebridResolveResult>()
        coEvery { h.ad.resolve(any(), any(), any(), any()) } coAnswers { response.await() }
        val first = async { h.resolver.resolve(source(), null, null) }
        runCurrent()
        val second = async { h.resolver.resolve(source(), null, null) }
        runCurrent(); first.cancelAndJoin(); runCurrent()
        response.complete(h.success)
        assertEquals(h.success, second.await())
        coVerify(exactly = 1) { h.ad.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.rd.resolve(any(), any(), any(), any()) }
    }

    @Test fun `rate limit cooldown applies to its account snapshot across sources then expires`() = runTest {
        val h = Harness(this)
        coEvery { h.ad.resolve(any(), any(), any(), any()) } returns failure(DirectDebridFailureReason.RATE_LIMITED, 10L)
        coEvery { h.rd.resolve(any(), any(), any(), any()) } returns DirectDebridResolveResult.NotCached
        val first = h.resolver.resolve(source(), null, null)
        assertEquals(DirectDebridFailureReason.RATE_LIMITED, (first as DirectDebridResolveResult.Failure).detail.reason)
        assertEquals(first, h.resolver.resolve(source("b"), null, null))
        coVerify(exactly = 1) { h.ad.resolve(any(), any(), any(), any()) }
        advanceTimeBy(10_001L); runCurrent()
        h.resolver.resolve(source("c"), null, null)
        coVerify(exactly = 2) { h.ad.resolve(any(), any(), any(), any()) }
        h.credentials.incrementAndGet()
        h.settings.value = h.settings.value.copy(allDebridApiKey = "synthetic-new-ad-account")
        runCurrent()
        h.resolver.resolve(source("d"), null, null)
        coVerify(exactly = 3) { h.ad.resolve(any(), any(), any(), any()) }
    }

    @Test fun `wrong episode is terminal without service fallback or a playback grant`() = runTest {
        val h = Harness(this)
        coEvery { h.ad.resolve(any(), any(), any(), any()) } returns failure(DirectDebridFailureReason.WRONG_EPISODE)
        val result = h.resolver.resolve(source(), 1, 4)
        assertEquals(DirectDebridFailureReason.WRONG_EPISODE, (result as DirectDebridResolveResult.Failure).detail.reason)
        coVerify(exactly = 0) { h.rd.resolve(any(), any(), any(), any()) }
        assertFalse(h.resolver.isPlaybackAuthorized(h.success.url))
    }

    @Test fun `base32 neutral source uses the same canonical hex hash for cache check and service resolution`() = runTest {
        val h = Harness(this, initialSettings = DebridSettings(enabled = true,
            preferredResolverProviderId = "torbox", torboxApiKey = "synthetic-tb"))
        val canonical = "0".repeat(40)
        coEvery { h.local.isCached(any(), canonical) } returns true
        var selected: Stream? = null
        coEvery { h.tb.resolve(any(), any(), any(), any()) } coAnswers {
            selected = firstArg(); h.success
        }
        assertEquals(h.success, h.resolver.resolve(source().copy(infoHash = "A".repeat(32)), null, null))
        coVerify(exactly = 1) { h.local.isCached(any(), canonical) }
        assertEquals(canonical, selected!!.clientResolve!!.infoHash)
        assertEquals("magnet:?xt=urn:btih:$canonical", selected!!.clientResolve!!.magnetUri)
    }

    @Test fun `selected cache rate limit skips service resolve and uses account cooldown before fallback`() = runTest {
        val h = Harness(this, initialSettings = DebridSettings(enabled = true,
            preferredResolverProviderId = "torbox", torboxApiKey = "synthetic-tb", realDebridApiKey = "synthetic-rd"))
        coEvery { h.local.isCached(any(), any()) } throws DirectDebridHttpException(429, 120L)
        coEvery { h.rd.resolve(any(), any(), any(), any()) } returns h.success
        assertEquals(h.success, h.resolver.resolve(source(), null, null))
        assertEquals(h.success, h.resolver.resolve(source("b"), null, null))
        val rateLimited = h.resolver.resolve(source("c"), null, null, allowFallback = false) as DirectDebridResolveResult.Failure
        assertEquals(DirectDebridFailureReason.RATE_LIMITED, rateLimited.detail.reason)
        assertEquals(120L, rateLimited.detail.retryAfterSeconds)
        coVerify(exactly = 1) { h.local.isCached(any(), any()) }
        coVerify(exactly = 0) { h.tb.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 2) { h.rd.resolve(any(), any(), any(), any()) }
    }

    @Test fun `late shorter selected resolution retry after cannot shorten an existing account cooldown`() = runTest {
        val h = Harness(this, initialSettings = DebridSettings(enabled = true,
            preferredResolverProviderId = "alldebrid", allDebridApiKey = "synthetic-ad"))
        val longEntered = CompletableDeferred<Unit>()
        val shortEntered = CompletableDeferred<Unit>()
        val releaseLong = CompletableDeferred<Unit>()
        val releaseShort = CompletableDeferred<Unit>()
        coEvery { h.ad.resolve(any(), any(), any(), any()) } coAnswers {
            when (firstArg<Stream>().getEffectiveInfoHash()) {
                "a".repeat(40) -> { longEntered.complete(Unit); releaseLong.await(); failure(DirectDebridFailureReason.RATE_LIMITED, 120L) }
                "b".repeat(40) -> { shortEntered.complete(Unit); releaseShort.await(); failure(DirectDebridFailureReason.RATE_LIMITED, 60L) }
                else -> failure(DirectDebridFailureReason.UNAVAILABLE)
            }
        }
        val longer = async { h.resolver.resolve(source(), null, null) }
        val shorter = async { h.resolver.resolve(source("b"), null, null) }
        longEntered.await(); shortEntered.await()
        releaseLong.complete(Unit)
        assertEquals(120L, (longer.await() as DirectDebridResolveResult.Failure).detail.retryAfterSeconds)
        releaseShort.complete(Unit)
        assertEquals(60L, (shorter.await() as DirectDebridResolveResult.Failure).detail.retryAfterSeconds)
        advanceTimeBy(61_000L); runCurrent()
        val later = h.resolver.resolve(source("c"), null, null) as DirectDebridResolveResult.Failure
        assertEquals(DirectDebridFailureReason.RATE_LIMITED, later.detail.reason)
        assertEquals(59L, later.detail.retryAfterSeconds)
        coVerify(exactly = 2) { h.ad.resolve(any(), any(), any(), any()) }
    }

    private class Harness(scope: TestScope, overall: Long = 3_000L,
        initialSettings: DebridSettings = DebridSettings(enabled = true, preferredResolverProviderId = "alldebrid",
            allDebridApiKey = "synthetic-ad", realDebridApiKey = "synthetic-rd")) {
        val settings = MutableStateFlow(initialSettings)
        val profile = MutableStateFlow(1)
        val credentials = AtomicLong(1L)
        val ad = mockk<AllDebridDirectDebridResolver>()
        val rd = mockk<RealDebridDirectDebridResolver>()
        val tb = mockk<TorboxDirectDebridResolver>()
        val local = mockk<LocalDebridService>()
        val success = DirectDebridResolveResult.Success("https://cdn.example.test/video.mkv", "video.mkv", 123L)
        val resolver: DirectDebridResolver
        init {
            val store = mockk<DebridSettingsDataStore>()
            every { store.activeProfileId } returns profile
            every { store.activeProfileSelectionRevision } returns 1L
            every { store.activeCredentialRevision } answers { credentials.get() }
            every { store.settings } returns settings
            coEvery { store.credentialForProfile(any(), any()) } answers { settings.value.apiKeyFor(secondArg()) }
            val grants = ServicePlaybackGrantRegistry({ scope.testScheduler.currentTime }, requireLiveOrigin = true)
            grants.bindLiveOrigin { ServicePlaybackOrigin(profile.value, 1L, credentials.get()) }
            resolver = DirectDebridResolver(store, tb, rd, ad, mockk(), local, scope.backgroundScope, grants,
                overallBudgetMillis = overall, serviceBudgetMillis = 1_000L, clock = { scope.testScheduler.currentTime })
        }
    }

    private fun failure(reason: DirectDebridFailureReason, seconds: Long? = null) =
        DirectDebridResolveResult.Failure(DirectDebridFailure(reason, "alldebrid", seconds))
    private fun source(hashCharacter: String = "a") = Stream("Synthetic", "Movie", null, null, null,
        hashCharacter.repeat(40), null, null, null, "Synthetic", null)
}
