package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingPlaybackGrant
import com.nuvio.tv.core.trakt.TraktAppRegistration
import com.nuvio.tv.core.trakt.TraktAppRegistrationStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleResponseDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TraktScrobbleGrantDeduplicationTest {
    @Test fun `same viewer media and progress for replacement grant sends to both original and new accounts`() = runTest {
        for (stop in listOf(false, true)) {
            val h = Harness()
            val original = h.capture()
            h.send(original, stop)
            h.grantId = "grant-B"
            h.revision++
            h.account = h.account.copy(accessToken = "account-B", refreshToken = "refresh-B")
            h.send(h.capture(), stop)
            assertEquals(listOf("Bearer account-A", "Bearer account-B"), h.sent)
        }
    }

    @Test fun `same grant access-token rotation preserves successful start and stop deduplication`() = runTest {
        for (stop in listOf(false, true)) {
            val h = Harness()
            val captured = h.capture()
            h.send(captured, stop)
            h.account = h.account.copy(accessToken = "account-A-rotated", refreshToken = "refresh-A-rotated")
            h.send(captured, stop)
            assertEquals(listOf("Bearer account-A"), h.sent)
            assertTrue(captured.isCurrent())
        }
    }

    private class Harness {
        var grantId = "grant-A"
        var revision = 1L
        var account = TraktAuthState(accessToken = "account-A", refreshToken = "refresh-A",
            createdAt = System.currentTimeMillis() / 1000L, expiresIn = 604800,
            registrationClientId = "syclop-client-id-123456")
        val sent = mutableListOf<String>()
        private val api = mockk<TraktApi>()
        private val store = mockk<TraktAuthDataStore> {
            every { activeProfileId } returns 1
            every { activeProfileSelectionRevision } returns 1L
            every { grantRevision(1) } answers { revision }
            coEvery { getCurrentState() } answers { account }
            coEvery { getCurrentState(1) } answers { account }
            coEvery { capturePlaybackGrantId(1) } answers { grantId }
            every { isPlaybackGrantCurrent(1, any()) } answers { secondArg<String>() == grantId }
        }
        private val auth = TraktAuthService(mockk(relaxed = true), api, store, mockk(relaxed = true),
            mockk<TraktAppRegistrationStore> {
                every { current() } returns TraktAppRegistration("syclop-client-id-123456")
            })
        private val service = TraktScrobbleService(api, auth, mockk(relaxed = true), mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
        }, { 10_000L })
        init {
            coEvery { api.scrobbleStart(any(), any()) } coAnswers {
                sent += firstArg<String>(); Response.success(TraktScrobbleResponseDto("start"))
            }
            coEvery { api.scrobbleStop(any(), any()) } coAnswers {
                sent += firstArg<String>(); Response.success(TraktScrobbleResponseDto("scrobble"))
            }
        }
        suspend fun capture() = checkNotNull(service.preparePlaybackGrant(1)?.resolve())
        suspend fun send(grant: TrackingPlaybackGrant, stop: Boolean) = grant.execute {
            val item = TraktScrobbleItem.Movie("Fixture", 2026, TraktIdsDto(imdb = "tt101"))
            if (stop) service.scrobbleStop(item, 90f) else service.scrobbleStart(item, 40f)
        }
    }
}
