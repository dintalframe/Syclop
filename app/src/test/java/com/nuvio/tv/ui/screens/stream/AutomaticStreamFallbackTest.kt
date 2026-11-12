package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.core.debrid.DirectDebridFailure
import com.nuvio.tv.core.debrid.DirectDebridFailureReason
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.core.debrid.TurkishBroadcastEpisode
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticStreamFallbackTest {
    @Test fun `automatic tries ranked distinct hashes and stops at first playable result`() = runTest {
        val first = stream('1')
        val duplicate = first.copy(addonName = "Other addon", name = "Renamed", fileIdx = 9)
        val second = stream('2')
        val third = stream('3')
        val calls = mutableListOf<Stream>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = resolveAutomaticStreamFallback(initial = first,
            ranked = listOf(first, duplicate, second, third), eligible = { true }, selectNext = { it.firstOrNull() },
            isCurrent = { true }, onAttempt = { attempt, total -> progress += attempt to total }) {
            calls += it
            if (it == second) AutomaticStreamAttempt(playback = "verified playback")
            else failure(DirectDebridFailureReason.SOURCE_REJECTED)
        }
        assertEquals(listOf(first, second), calls)
        assertEquals(listOf(1 to 3, 2 to 3), progress)
        assertEquals("verified playback", result.playback)
    }

    @Test fun `automatic exhausts every distinct source for source local failures`() = runTest {
        for (reason in listOf(DirectDebridFailureReason.PROCESSING, DirectDebridFailureReason.UNAVAILABLE,
            DirectDebridFailureReason.SOURCE_REJECTED, DirectDebridFailureReason.WRONG_EPISODE)) {
            val streams = listOf(stream('1'), stream('2'), stream('3'), stream('4'))
            val calls = mutableListOf<Stream>()
            val result = resolveAutomaticStreamFallback(initial = streams.first(), ranked = streams,
                eligible = { true },
                selectNext = { it.firstOrNull() }, isCurrent = { true }) {
                calls += it
                failure<String>(reason)
            }
            assertEquals(reason.name, streams, calls)
            assertEquals(reason, (result.failure as DirectDebridResolveResult.Failure).detail.reason)
        }
    }

    @Test fun `a source without broadcast proof cannot suppress a proven alias of the same hash`() = runTest {
        val plain = stream('1')
        val proven = plain.copy(nativeTurkishBroadcastEpisode =
            TurkishBroadcastEpisode("tt1286677:2:1", 2, 1, 39, "Aşk-ı Memnu", 2008))
        val calls = mutableListOf<Stream>()
        val result = resolveAutomaticStreamFallback(initial = plain, ranked = listOf(plain, proven),
            eligible = { true }, selectNext = { it.firstOrNull() }, isCurrent = { true }) {
            calls += it
            if (it == proven) AutomaticStreamAttempt(playback = "verified episode")
            else failure(DirectDebridFailureReason.WRONG_EPISODE)
        }
        assertEquals(listOf(plain, proven), calls)
        assertEquals("verified episode", result.playback)
    }

    @Test fun `auth rate limit timeout network and unclassified results never try another source`() = runTest {
        val terminal = listOf(DirectDebridFailureReason.AUTH_EXPIRED, DirectDebridFailureReason.RATE_LIMITED,
            DirectDebridFailureReason.TIMED_OUT, DirectDebridFailureReason.NETWORK_FAILURE).map {
            DirectDebridResolveResult.Failure(DirectDebridFailure(it))
        } + listOf(DirectDebridResolveResult.MissingApiKey,
            DirectDebridResolveResult.Stale, DirectDebridResolveResult.Error)
        for (failure in terminal) {
            val first = stream('1')
            val calls = mutableListOf<Stream>()
            val result = resolveAutomaticStreamFallback(initial = first, ranked = listOf(first, stream('2')),
                eligible = { true },
                selectNext = { it.firstOrNull() }, isCurrent = { true }) {
                calls += it
                AutomaticStreamAttempt<String>(failure = failure)
            }
            assertEquals(listOf(first), calls)
            assertEquals(failure, result.failure)
        }
    }

    @Test fun `uncached first source advances to the next distinct playable source`() = runTest {
        val first = stream('1')
        val second = stream('2')
        val calls = mutableListOf<Stream>()
        val result = resolveAutomaticStreamFallback(initial = first, ranked = listOf(first, second),
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { true }) {
            calls += it
            if (it == first) AutomaticStreamAttempt(failure = DirectDebridResolveResult.NotCached)
            else AutomaticStreamAttempt(playback = "playable second source")
        }
        assertEquals(listOf(first, second), calls)
        assertEquals("playable second source", result.playback)
    }

    @Test fun `manual selection recovers from refusal to a later playable source`() = runTest {
        val first = stream('1')
        val second = stream('2')
        val calls = mutableListOf<Stream>()
        val result = resolveAutomaticStreamFallback(initial = first, ranked = listOf(first, second),
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { true }) {
            calls += it
            if (it == first) failure(DirectDebridFailureReason.SOURCE_REJECTED)
            else AutomaticStreamAttempt(playback = "manual recovery")
        }
        assertEquals(listOf(first, second), calls)
        assertEquals("manual recovery", result.playback)
    }

    @Test fun `success after five refused sources is not limited by an attempt cap`() = runTest {
        val streams = ('1'..'6').map(::stream)
        val calls = mutableListOf<Stream>()
        val result = resolveAutomaticStreamFallback(initial = streams.first(), ranked = streams,
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { true }) {
            calls += it
            if (it == streams.last()) AutomaticStreamAttempt(playback = "sixth source")
            else failure(DirectDebridFailureReason.SOURCE_REJECTED)
        }
        assertEquals(streams, calls)
        assertEquals("sixth source", result.playback)
    }

    @Test fun `fallback retains native eligibility source and regex restrictions`() = runTest {
        val first = stream('1').copy(name = "1080p")
        val ineligible = stream('2').copy(name = "1080p")
        val excludedAddon = stream('3').copy(addonName = "Disabled", name = "1080p")
        val excludedRegex = stream('4').copy(name = "480p")
        val allowed = stream('5').copy(name = "1080p")
        val calls = mutableListOf<Stream>()
        resolveAutomaticStreamFallback(initial = first,
            ranked = listOf(first, ineligible, excludedAddon, excludedRegex, allowed),
            eligible = { it != ineligible },
            selectNext = { StreamAutoPlaySelector.selectAutoPlayStream(it, StreamAutoPlayMode.REGEX_MATCH,
                "1080p", StreamAutoPlaySource.INSTALLED_ADDONS_ONLY, setOf("Addon"), setOf("Addon"), emptySet()) },
            isCurrent = { true }) {
            calls += it
            failure<String>(DirectDebridFailureReason.UNAVAILABLE)
        }
        assertEquals(listOf(first, allowed), calls)
    }

    @Test fun `canonical base32 and hex aliases cannot retry the denied hash`() = runTest {
        val first = stream('0')
        val alias = first.copy(infoHash = "A".repeat(32), addonName = "Other addon")
        val next = stream('2')
        val calls = mutableListOf<Stream>()
        resolveAutomaticStreamFallback(initial = first, ranked = listOf(alias, next),
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { true }) {
            calls += it
            failure<String>(DirectDebridFailureReason.SOURCE_REJECTED)
        }
        assertEquals(listOf(first, next), calls)
    }

    @Test fun `each source has its own deadline without a whole queue budget`() = runTest {
        val streams = listOf(stream('1'), stream('2'), stream('3'))
        val calls = mutableListOf<Stream>()
        val result = resolveAutomaticStreamFallback(initial = streams.first(), ranked = streams,
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { true }, budgetMillis = 1_000) {
            calls += it
            delay(600)
            failure<String>(DirectDebridFailureReason.PROCESSING)
        }
        assertEquals(streams, calls)
        assertEquals(1_800L, testScheduler.currentTime)
        assertEquals(DirectDebridFailureReason.PROCESSING,
            (result.failure as DirectDebridResolveResult.Failure).detail.reason)
    }

    @Test fun `one source reaching its deadline terminates the queue`() = runTest {
        val first = stream('1')
        val calls = mutableListOf<Stream>()
        val result = resolveAutomaticStreamFallback<String>(initial = first, ranked = listOf(first, stream('2')),
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { true }, budgetMillis = 1_000) {
            calls += it
            awaitCancellation()
        }
        assertEquals(listOf(first), calls)
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(DirectDebridFailureReason.TIMED_OUT,
            (result.failure as DirectDebridResolveResult.Failure).detail.reason)
    }

    @Test fun `sources arriving during recovery are excluded from the finite snapshot`() = runTest {
        val first = stream('1')
        val second = stream('2')
        val ranked = mutableListOf(first, second)
        val calls = mutableListOf<Stream>()
        resolveAutomaticStreamFallback(initial = first, ranked = ranked,
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { true }) {
            calls += it
            ranked += stream('3')
            failure<String>(DirectDebridFailureReason.UNAVAILABLE)
        }
        assertEquals(listOf(first, second), calls)
    }

    @Test fun `profile or selection change drops late success and cannot advance`() = runTest {
        var current = true
        var calls = 0
        val first = stream('1')
        val result = resolveAutomaticStreamFallback(initial = first, ranked = listOf(first, stream('2')),
            eligible = { true },
            selectNext = { it.firstOrNull() }, isCurrent = { current }) {
            calls++
            current = false
            AutomaticStreamAttempt(playback = "stale URL")
        }
        assertEquals(1, calls)
        assertNull(result.playback)
        assertNull(result.failure)
    }

    @Test fun `cancelling automatic selection cancels active work and never advances`() = runTest {
        val first = stream('1')
        var calls = 0
        var cancelled = false
        val job = async {
            resolveAutomaticStreamFallback<String>(initial = first, ranked = listOf(first, stream('2')),
                eligible = { true },
                selectNext = { it.firstOrNull() }, isCurrent = { true }) {
                calls++
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(cancelled)
        try { job.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    private fun <T> failure(reason: DirectDebridFailureReason) = AutomaticStreamAttempt<T>(
        failure = DirectDebridResolveResult.Failure(DirectDebridFailure(reason)))

    private fun stream(hash: Char) = Stream(name = "Source", title = null, description = null,
        url = null, ytId = null, infoHash = hash.toString().repeat(40), fileIdx = null,
        externalUrl = null, behaviorHints = null, addonName = "Addon", addonLogo = null)
}
