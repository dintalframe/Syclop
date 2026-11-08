package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MdbListPlaybackOriginTest {
    @Test fun `accepted playback grant cannot scrobble using replacement MDBList account`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val adapter = MdbListTrackingScrobbler(h.repository, MdbListScrobbleService(h.http.api, h.repository), h.http.store)
        val grant = checkNotNull(adapter.preparePlaybackGrant(1)?.resolve())
        h.http.connected(accessToken = "replacement", refreshToken = "replacement-refresh")
        val result = runCatching { grant.execute { adapter.scrobble(TrackingScrobbleAction.STOP, event()) } }
        assertTrue(result.exceptionOrNull() is TrackingPlaybackAccountChangedException)
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test fun `MDBList captured playback scope survives token refresh and keeps exact media`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val adapter = MdbListTrackingScrobbler(h.repository, MdbListScrobbleService(h.http.api, h.repository), h.http.store)
        val grant = checkNotNull(adapter.preparePlaybackGrant(1)?.resolve())
        val previous = checkNotNull(h.http.store.authorization())
        assertTrue(h.http.store.refreshTokens(MdbListTokens("rotated", "rotated-refresh", h.http.now + 999999L), previous))
        h.http.reply(body = """{"action":"pause","progress":45,"paused_at":"2026-09-06T01:00:00Z"}""")
        grant.execute { adapter.scrobble(TrackingScrobbleAction.STOP, event()) }
        assertEquals(listOf("/scrobble/stop"), h.http.engine.requests.map { it.path })
        assertEquals("rotated", h.http.engine.requests.single().accessToken)
        assertEquals(45f, h.repository.currentSnapshot()!!.playback.single().progress, 0f)
    }

    private fun event() = TrackingScrobbleEvent(TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt1")), 45.0)
}
