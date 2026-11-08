package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.core.trakt.TraktAppRegistration
import com.nuvio.tv.core.trakt.TraktAppRegistrationStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class TraktPlaybackOriginTest {
    @Test fun `mapping suspended before dispatch cannot send old playback to new account`() = runTest {
        var current = true
        val mapping = mockk<TraktEpisodeMappingService>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { mapping.resolveEpisodeMapping(any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit); release.await(); null
        }
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val adapter = TraktTrackingScrobbler(service, mapping)
        val grant = TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, "grant-A", { current })
        val task = async { runCatching { grant.execute {
            adapter.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(TrackingMediaReference(
                kind = TrackingMediaKind.SHOW, ids = TrackingExternalIds(imdb = "tt101"),
                episode = TrackingEpisode(1, 2)), 90.0))
        } } }
        runCurrent(); assertTrue(entered.isCompleted)
        current = false
        release.complete(Unit)
        assertTrue(task.await().exceptionOrNull() is TrackingPlaybackAccountChangedException)
        coVerify(exactly = 0) { service.scrobbleStop(any(), any()) }
    }

    @Test fun `Trakt response cannot retry401 after playback origin changes while native grant observer lags`() = runTest {
        val auth = authStore()
        val service = authService(auth)
        var current = true
        var calls = 0
        val grant = TrackingPlaybackGrant(TrackingProviderId.TRAKT, 1, "grant-A", { current })
        val result = withContext(grant) { service.executeAuthorizedWriteRequest<String> {
            calls++; current = false
            Response.error(401, "{}".toResponseBody())
        } }
        assertNull(result)
        assertEquals(1, calls)
    }

    @Test fun `Trakt playback grant accepts rotated credentials without selecting a new grant`() = runTest {
        val auth = authStore()
        var state = account("initial")
        coEvery { auth.getCurrentState(1) } answers { state }
        coEvery { auth.getCurrentState() } answers { state }
        val service = authService(auth)
        val captured = checkNotNull(service.preparePlaybackGrant(1)?.resolve())
        state = account("rotated")
        var sent: String? = null
        val result = captured.execute { service.executeAuthorizedWriteRequest<String> {
            sent = it; Response.success("ok")
        } }
        assertEquals("Bearer rotated", sent)
        assertEquals("ok", result?.body())
    }

    private fun authStore() = mockk<TraktAuthDataStore> {
        every { activeProfileId } returns 1
        every { activeProfileSelectionRevision } returns 1L
        every { grantRevision(1) } returns 1L
        coEvery { getCurrentState(1) } returns account("initial")
        coEvery { getCurrentState() } returns account("initial")
        coEvery { capturePlaybackGrantId(1) } returns "grant-A"
        every { isPlaybackGrantCurrent(1, "grant-A") } returns true
    }
    private fun authService(auth: TraktAuthDataStore) = TraktAuthService(mockk(relaxed = true), mockk(), auth,
        mockk(relaxed = true), mockk<TraktAppRegistrationStore> {
            every { current() } returns TraktAppRegistration("syclop-client-id-123456")
        })
    private fun account(token: String) = TraktAuthState(accessToken = token, refreshToken = "refresh",
        createdAt = System.currentTimeMillis() / 1000L, expiresIn = 604800,
        registrationClientId = "syclop-client-id-123456")
}

