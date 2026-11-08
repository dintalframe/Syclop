package com.nuvio.tv.core.tracking

import java.io.IOException
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrackingPlaybackOriginResolverTest {
    @Test fun `bounded capture failure leaves a local writable origin with no remote publication`() = runTest {
        val result = TrackingPlaybackOriginResolver(backgroundScope).capture(1, prepared()) { throw IOException("fixture") }.await()
        assertEquals(1, result.profileId)
        assertFalse(result.isProfileCurrent())
        assertNull(result.grant(TrackingProviderId.TRAKT))
    }
    @Test fun `stalled capture times out rather than blocking final local progress indefinitely`() = runTest {
        val result = TrackingPlaybackOriginResolver(backgroundScope).capture(1, prepared()) { awaitCancellation() }
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(1, result.await().profileId)
        assertFalse(result.await().isProfileCurrent())
        assertNull(result.await().grant(TrackingProviderId.TRAKT))
    }
    @Test fun `timeout after local identity was captured preserves that identity but rejects publication`() = runTest {
        val stalledGrant = TrackingPlaybackOriginCapture(1, listOf(TrackingPlaybackGrantCapture { awaitCancellation() }))
        val result = TrackingPlaybackOriginResolver(backgroundScope).capture(1, stalledGrant) { "original-account" }
        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals("original-account", result.await().localPlaybackScope)
        assertFalse(result.await().isProfileCurrent())
        assertTrue(result.await().record().grants.isEmpty())
    }
    @Test fun `rejected recovered profile cannot become publishable after switching back`() = runTest {
        val profile = MutableStateFlow(2)
        val manager = mockk<ProfileManager> {
            every { activeProfileReady } returns MutableStateFlow(true)
            every { activeProfileId } returns profile
            every { activeProfileSelectionRevision } answers { profile.value.toLong() }
        }
        val coordinator = TrackingScrobbleCoordinator(TrackingProviderRegistry(emptyList()), profileManager = manager)
        val origin = coordinator.recoverPlaybackOrigin(TrackingPlaybackOriginRecord(1, emptyMap()))
        assertFalse(origin.isProfileCurrent())
        profile.value = 1
        assertFalse(origin.isProfileCurrent())
        assertTrue(origin.record().grants.isEmpty())
    }
    private fun prepared() = TrackingPlaybackOriginCapture(1, listOf(TrackingPlaybackGrantCapture {
        TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, "original-grant", { true })
    }))
}
