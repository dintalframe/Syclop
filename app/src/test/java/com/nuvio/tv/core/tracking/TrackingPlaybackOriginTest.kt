package com.nuvio.tv.core.tracking

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingPlaybackOriginTest {
    private val firstGrant = "00000000-0000-0000-0000-000000000001"
    private val replacementGrant = "00000000-0000-0000-0000-000000000002"
    private val simklGrant = "00000000-0000-0000-0000-000000000003"

    @Test fun `replacement during durable identity resolution cannot retarget a captured account`() = runTest {
        var nativeRevision = 1
        val pinnedRevision = nativeRevision
        val release = CompletableDeferred<Unit>()
        val captured = TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, firstGrant,
            current = { nativeRevision == pinnedRevision })
        val origin = async {
            TrackingPlaybackOriginCapture(1, listOf(TrackingPlaybackGrantCapture {
                release.await()
                captured
            })).resolve("original-account-progress")
        }
        runCurrent()
        nativeRevision++
        release.complete(Unit)
        runCurrent()

        assertNull(origin.await().grant(TrackingProviderId.TRAKT))
        assertEquals("original-account-progress", origin.await().localPlaybackScope)
        assertTrue(origin.await().record().grants.isEmpty())
    }

    @Test fun `failed provider capture preserves independently valid providers`() = runTest {
        val origin = TrackingPlaybackOriginCapture(1, listOf(
            TrackingPlaybackGrantCapture { throw IOException("unavailable fixture storage") },
            TrackingPlaybackGrantCapture { TrackingPlaybackGrant(TrackingProviderId.SIMKL, 1, simklGrant, { true }) }
        )).resolve()

        assertNull(origin.grant(TrackingProviderId.TRAKT))
        assertEquals(simklGrant, origin.grant(TrackingProviderId.SIMKL)?.grantId)
        assertEquals(mapOf("simkl" to simklGrant), origin.record().grants)
    }

    @Test fun `capture cancellation is propagated rather than becoming a disconnected provider`() = runTest {
        val cancellation = CancellationException("cancelled fixture capture")
        val failure = runCatching {
            TrackingPlaybackGrantCapture { throw cancellation }.resolve()
        }.exceptionOrNull()
        assertSame(cancellation, failure)
    }

    @Test fun `a grant from another profile cannot enter the origin`() = runTest {
        val origin = TrackingPlaybackOriginCapture(1, listOf(TrackingPlaybackGrantCapture {
            TrackingPlaybackGrant(TrackingProviderId.TRAKT, 2, firstGrant, { true })
        })).resolve()
        assertNull(origin.grant(TrackingProviderId.TRAKT))
        assertTrue(origin.record().grants.isEmpty())
    }

    @Test fun `profile A to B to A invalidates queued work for the former selection`() = runTest {
        var profile = 1
        var selectionRevision = 0
        val pinnedRevision = selectionRevision
        val origin = TrackingPlaybackOriginCapture(1, listOf(TrackingPlaybackGrantCapture {
            TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, firstGrant, { true })
        }), selectionCurrent = { profile == 1 && selectionRevision == pinnedRevision }).resolve()
        val queuedGrant = checkNotNull(origin.grant(TrackingProviderId.TRAKT))
        profile = 2
        selectionRevision++
        assertNull(origin.grant(TrackingProviderId.TRAKT))
        profile = 1
        selectionRevision++
        var requests = 0
        val failure = runCatching { queuedGrant.execute { requests++ } }.exceptionOrNull()

        assertNull(origin.grant(TrackingProviderId.TRAKT))
        assertTrue(failure is TrackingPlaybackAccountChangedException)
        assertEquals(0, requests)
    }

    @Test fun `replacement before queued execution prevents the request from starting`() = runTest {
        var current = true
        val grant = TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, firstGrant, { current })
        var requests = 0
        val queued = async { runCatching { grant.execute { requests++ } } }
        current = false
        runCurrent()

        assertTrue(queued.await().exceptionOrNull() is TrackingPlaybackAccountChangedException)
        assertEquals(0, requests)
    }

    @Test fun `replacement while a response is suspended rejects its acknowledgement`() = runTest {
        var current = true
        val release = CompletableDeferred<Unit>()
        val grant = TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, firstGrant, { current })
        var requests = 0
        var acknowledgements = 0
        val pending = async {
            runCatching {
                grant.execute {
                    requests++
                    release.await()
                    "fixture receipt"
                }.also { acknowledgements++ }
            }
        }
        runCurrent()
        assertEquals(1, requests)
        current = false
        release.complete(Unit)
        runCurrent()

        assertTrue(pending.await().exceptionOrNull() is TrackingPlaybackAccountChangedException)
        assertEquals(0, acknowledgements)
    }

    @Test fun `one replaced provider cannot cancel another queued provider mutation`() = runTest {
        var traktCurrent = true
        val grants = listOf(
            TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, firstGrant, { traktCurrent }),
            TrackingPlaybackGrant(TrackingProviderId.SIMKL, 1, simklGrant, { true })
        )
        val written = mutableListOf<TrackingProviderId>()
        val queuedScrobblers = grants.map { grant -> object : TrackingScrobbler {
            override val providerId = grant.providerId
            override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
                grant.execute { written += providerId }
            }
        } }
        traktCurrent = false
        val failures = dispatchTrackingScrobble(queuedScrobblers, TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(TrackingMediaReference(TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0000001")), 75.0))

        assertEquals(listOf(TrackingProviderId.SIMKL), written)
        assertEquals(listOf(TrackingProviderId.TRAKT), failures.map { it.providerId })
        assertTrue(failures.single().cause is TrackingPlaybackAccountChangedException)
    }

    @Test fun `current grant crosses suspensions and remains available at request checkpoints`() = runTest {
        var current = true
        val release = CompletableDeferred<Unit>()
        val grant = TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, firstGrant, { current })
        val pending = async {
            grant.execute {
                checkpointTrackingPlaybackOrigin(TrackingProviderId.TRAKT)
                release.await()
                checkpointTrackingPlaybackOrigin(TrackingProviderId.TRAKT)
                "accepted"
            }
        }
        runCurrent()
        // The captured account remains current while its ordinary asynchronous work completes.
        assertTrue(current)
        release.complete(Unit)
        runCurrent()
        assertEquals("accepted", pending.await())
    }

    @Test fun `cold recovery waits for the original profile then accepts its unchanged durable grant`() = runTest {
        var loadedProfile = 0
        var captures = 0
        val pending = async {
            recoverTrackingPlaybackGrant(1, firstGrant, { loadedProfile }) {
                captures++
                TrackingPlaybackGrantCapture {
                    TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, firstGrant, { loadedProfile == 1 })
                }
            }
        }
        runCurrent()
        assertEquals(0, captures)
        assertFalse(pending.isCompleted)
        loadedProfile = 1
        advanceTimeBy(20)
        runCurrent()

        assertEquals(firstGrant, pending.await()?.grantId)
        assertEquals(1, captures)
    }

    @Test fun `cold recovery rejects a different durable grant after profile loading`() = runTest {
        var loadedProfile = 0
        val pending = async {
            recoverTrackingPlaybackGrant(1, firstGrant, { loadedProfile }) {
                TrackingPlaybackGrantCapture {
                    TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, replacementGrant, { true })
                }
            }
        }
        runCurrent()
        loadedProfile = 1
        advanceTimeBy(20)
        runCurrent()
        assertNull(pending.await())
    }

    @Test fun `unavailable recovery is bounded and never captures a different loaded profile`() = runTest {
        var captures = 0
        val pending = async {
            recoverTrackingPlaybackGrant(1, firstGrant, { 2 }) {
                captures++
                TrackingPlaybackGrantCapture { error("Wrong profile must not be captured") }
            }
        }
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        assertTrue(pending.isCompleted)
        assertNull(pending.await())
        assertEquals(0, captures)
    }
}
