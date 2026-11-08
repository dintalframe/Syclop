package com.nuvio.tv.data.mal

import com.nuvio.tv.core.tracking.*
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MalPlaybackOriginTest {
    @Test fun `delayed outgoing video cannot replace the newer video's completion owner`() = runTest {
        val store = MalAuthStore(MemoryMalAccountPersistence(), MutableStateFlow(1), backgroundScope,
            malTestConfiguration, diagnose = { _, _ -> })
        runCurrent()
        assertTrue(store.complete(store.state.value.scope, tokens("A"), "A"))
        val native = store.state.value.scope
        val repository = mockk<MalRepository>(relaxed = true)
        val auth = mockk<MalAuthRepository>()
        every { auth.store } returns store
        every { auth.state } returns store.state
        val playback = MalPlaybackSync(auth, repository, backgroundScope)
        val grant = checkNotNull(playback.preparePlaybackGrant(1)?.resolve())
        val origin = TrackingPlaybackOrigin(1, mapOf(TrackingProviderId.MAL to grant), null)
        val outgoing = event(1L)
        val current = event(2L)
        playback.record(TrackingScrobbleAction.START, outgoing, origin)
        playback.record(TrackingScrobbleAction.START, current, origin)
        playback.record(TrackingScrobbleAction.START, outgoing, origin) // delayed old START
        playback.record(TrackingScrobbleAction.STOP, outgoing, origin)
        playback.record(TrackingScrobbleAction.STOP, current, origin)
        runCurrent()
        coVerify(exactly = 1) { repository.syncCompletedEpisode(native, 1L, 95.0, 1, 3) }
        coVerify(exactly = 1) { repository.syncCompletedEpisode(native, 2L, 95.0, 1, 3) }
    }

    @Test fun `queued completion cannot use replacement MAL login`() = runTest {
        val store = MalAuthStore(MemoryMalAccountPersistence(), MutableStateFlow(1), backgroundScope,
            malTestConfiguration, diagnose = { _, _ -> })
        runCurrent()
        assertTrue(store.complete(store.state.value.scope, tokens("A"), "A"))
        val repository = mockk<MalRepository>(relaxed = true)
        val auth = mockk<MalAuthRepository>()
        every { auth.store } returns store
        every { auth.state } returns store.state
        val playback = MalPlaybackSync(auth, repository, backgroundScope)
        val grant = checkNotNull(playback.preparePlaybackGrant(1)?.resolve())
        val origin = TrackingPlaybackOrigin(1, mapOf(TrackingProviderId.MAL to grant), null)
        playback.record(TrackingScrobbleAction.STOP, event(1L), origin)
        assertTrue(store.complete(store.state.value.scope, tokens("B"), "B"))
        runCurrent()
        coVerify(exactly = 0) { repository.syncCompletedEpisode(any(), any(), any(), any(), any()) }
    }

    @Test fun `queued same-grant completion survives access-token rotation`() = runTest {
        val store = MalAuthStore(MemoryMalAccountPersistence(), MutableStateFlow(1), backgroundScope,
            malTestConfiguration, diagnose = { _, _ -> })
        runCurrent()
        val initial = tokens("A")
        assertTrue(store.complete(store.state.value.scope, initial, "A"))
        val native = store.state.value.scope
        val repository = mockk<MalRepository>(relaxed = true)
        val auth = mockk<MalAuthRepository>()
        every { auth.store } returns store
        every { auth.state } returns store.state
        val playback = MalPlaybackSync(auth, repository, backgroundScope)
        val grant = checkNotNull(playback.preparePlaybackGrant(1)?.resolve())
        playback.record(TrackingScrobbleAction.STOP, event(1L),
            TrackingPlaybackOrigin(1, mapOf(TrackingProviderId.MAL to grant), null))
        assertTrue(store.replaceTokens(native, initial, tokens("A-rotated")))
        runCurrent()
        coVerify(exactly = 1) { repository.syncCompletedEpisode(native, 1L, 95.0, 1, 3) }
    }

    private fun tokens(prefix: String) = MalTokens("$prefix-access", "$prefix-refresh", Long.MAX_VALUE)
    private fun event(id: Long) = TrackingScrobbleEvent(TrackingMediaReference(
        kind = TrackingMediaKind.ANIME, ids = TrackingExternalIds(mal = id),
        episode = TrackingEpisode(season = 1, number = 3)), 95.0)
}
