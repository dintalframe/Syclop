package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TraktPlaybackScopeTest {
    @Test
    fun `scope survives ordinary token rotation without containing username or credentials`() {
        val state = state()
        val scope = traktLocalPlaybackScope(state)
        assertNotNull(scope)
        assertEquals(scope, traktLocalPlaybackScope(state.copy(accessToken = "rotated", refreshToken = "rotated-refresh")))
        assertEquals(scope, traktLocalPlaybackScope(state.copy(userSlug = "  ACCOUNT-A  ")))
        assertFalse(scope!!.contains("account-a"))
        assertFalse(scope.contains(state.accessToken!!))
    }

    @Test
    fun `scope separates account profile and registration`() {
        val state = state()
        val scope = traktLocalPlaybackScope(state)
        assertNotEquals(scope, traktLocalPlaybackScope(state.copy(userSlug = "account-b")))
        assertNotEquals(scope, traktLocalPlaybackScope(state.copy(profileId = 2)))
        assertNotEquals(scope, traktLocalPlaybackScope(state.copy(registrationClientId = "other-public-client")))
    }

    @Test
    fun `missing identity unauthenticated state and malformed public identity fail closed`() {
        assertNull(traktLocalPlaybackScope(state().copy(userSlug = null)))
        assertNull(traktLocalPlaybackScope(state().copy(refreshToken = null)))
        assertNull(traktLocalPlaybackScope(state().copy(userSlug = "wrong\nidentity")))
        assertNull(traktLocalPlaybackScope(state().copy(registrationClientId = null)))
    }

    @Test
    fun `provider capture rejects login change while auth storage read is suspended`() = runTest {
        var revision = 1L
        val auth = auth { revision }
        coEvery { auth.getCurrentState(1) } coAnswers {
            revision++
            state().copy(userSlug = "account-b")
        }
        assertNull(provider(auth).captureLocalPlaybackScope(1))
    }

    @Test
    fun `provider capture rejects profile change while auth storage read is suspended`() = runTest {
        var profile = 1
        val auth = auth { 1L }
        every { auth.activeProfileId } answers { profile }
        every { auth.activeProfileSelectionRevision } answers { profile.toLong() }
        coEvery { auth.getCurrentState(1) } coAnswers {
            profile = 2
            state()
        }
        assertNull(provider(auth).captureLocalPlaybackScope(1))
    }

    @Test
    fun `provider captures stable identity for the current account`() = runTest {
        val auth = auth { 1L }
        coEvery { auth.getCurrentState(1) } returns state()
        assertEquals(traktLocalPlaybackScope(state()), provider(auth).captureLocalPlaybackScope(1))
    }

    private fun auth(revision: () -> Long) = mockk<TraktAuthDataStore>(relaxed = true) {
        every { state } returns flowOf(state())
        every { isEffectivelyAuthenticated } returns flowOf(true)
        every { activeProfileId } returns 1
        every { activeProfileSelectionRevision } returns 1L
        every { grantRevision(any()) } answers { revision() }
    }

    private fun provider(auth: TraktAuthDataStore) = TraktTrackingProgressProvider(
        service = mockk<TraktProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList<WatchProgress>())
            every { observeRemoteProgressLoaded() } returns flowOf(true)
            every { observeAllWatchedMovieIds() } returns flowOf(emptySet())
            every { observeWatchedShowSeeds() } returns flowOf(emptyList())
        },
        layoutPreferences = mockk<LayoutPreferenceDataStore> {
            every { nextUpFromFurthestEpisode } returns MutableStateFlow(true)
        },
        authDataStore = auth
    )

    private fun state() = TraktAuthState(
        accessToken = "synthetic-access", refreshToken = "synthetic-refresh", userSlug = "account-a",
        registrationClientId = "synthetic-public-client", profileId = 1
    )
}
