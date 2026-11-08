package com.nuvio.tv.core.initialization

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktProgressService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TraktInitializationParticipantHydrationTest {
    @Test fun `restored grant missing identity hydrates before marking its actual import`() = runTest {
        val h = Fixture()
        coEvery { h.auth.hydrateInitializationIdentity(h.owner) } coAnswers {
            h.account = h.account.copy(userSlug = "current-account")
            h.account
        }
        val engine = FirstUseInitialization(h.markers)
        engine.reset(1, 0); engine.run(1, 0, listOf(h.participant)) { true }
        assertEquals(InitializationPhase.READY, engine.state.value.phase)
        assertEquals(7, engine.state.value.stages.single().count)
        assertTrue(h.markers.contains(InitializationMarkerKey.fromAccount(1, InitializationProvider.TRAKT, "slug:current-account")!!))
        coVerify(exactly = 1) { h.auth.hydrateInitializationIdentity(h.owner) }
        coVerify(exactly = 1) { h.progress.awaitInitializationImport(any()) }
        coVerify(exactly = 1) { h.library.awaitInitializationImport(any()) }
    }

    @Test fun `known account identity never starts an extra identity request`() = runTest {
        val h = Fixture(); h.account = h.account.copy(userSlug = "known-account")
        val captured = h.participant.capture(1, 0)!!
        assertEquals(InitializationMarkerKey.fromAccount(1, InitializationProvider.TRAKT, "slug:known-account"), captured.marker)
        coVerify(exactly = 0) { h.auth.hydrateInitializationIdentity(any()) }
    }

    @Test fun `failed identity hydration stays unmarked and later retry imports the preserved grant`() = runTest {
        val h = Fixture(); var failIdentity = true
        coEvery { h.auth.hydrateInitializationIdentity(h.owner) } coAnswers {
            if (failIdentity) throw InitializationReadException(InitializationFailure.NETWORK)
            h.account.copy(username = "current-account").also { h.account = it }
        }
        val engine = FirstUseInitialization(h.markers)
        engine.reset(1, 0); engine.run(1, 0, listOf(h.participant)) { true }
        assertEquals(InitializationFailure.NETWORK, engine.state.value.stages.single().failure)
        assertTrue(h.markerStore.data.first().asMap().isEmpty())
        coVerify(exactly = 0) { h.progress.awaitInitializationImport(any()) }
        assertTrue(h.account.isAuthenticated)
        failIdentity = false
        engine.reset(1, 0); engine.run(1, 0, listOf(h.participant)) { true }
        assertEquals(InitializationPhase.READY, engine.state.value.phase)
        assertTrue(h.markers.contains(InitializationMarkerKey.fromAccount(1, InitializationProvider.TRAKT, "name:current-account")!!))
    }

    @Test fun `profile selection loss after hydration cannot capture or import an old account`() = runTest {
        val h = Fixture()
        coEvery { h.auth.hydrateInitializationIdentity(h.owner) } coAnswers {
            h.selection = 1
            h.account.copy(userSlug = "old-account")
        }
        val failure = runCatching { h.participant.capture(1, 0) }.exceptionOrNull()
        assertTrue(failure is InitializationReadException)
        assertEquals(InitializationFailure.ACCOUNT_CHANGED, (failure as InitializationReadException).reason)
        coVerify(exactly = 0) { h.progress.awaitInitializationImport(any()) }
    }

    @Test fun `cancelled identity read propagates and cannot create a completion marker`() = runTest {
        val h = Fixture()
        coEvery { h.auth.hydrateInitializationIdentity(h.owner) } throws CancellationException("cancelled")
        val engine = FirstUseInitialization(h.markers); engine.reset(1, 0)
        val failure = runCatching { engine.run(1, 0, listOf(h.participant)) { true } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(h.markerStore.data.first().asMap().isEmpty())
    }

    private class Fixture {
        val profile = MutableStateFlow(1)
        var selection = 0L
        var account = TraktAuthState(accessToken = "synthetic-access", refreshToken = "synthetic-refresh", profileId = 1)
        val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns profile
            every { activeProfileSelectionRevision } answers { selection }
        }
        val data = mockk<TraktAuthDataStore> {
            every { state } answers { flowOf(account) }
            every { grantRevision(1) } returns 0L
            coEvery { getCurrentState(1) } answers { account }
        }
        val owner = InitializationReadScope(InitializationProvider.TRAKT, "synthetic-current-grant") {
            profile.value == 1 && selection == 0L
        }
        val auth = mockk<TraktAuthService> {
            coEvery { captureInitializationReadScope() } returns owner
            every { hasRequiredCredentials() } returns true
        }
        val progress = mockk<TraktProgressService> {
            coEvery { hideForeignInitializationProjection(any()) } returns Unit
            coEvery { awaitInitializationImport(any()) } returns InitializationReadResult(4)
        }
        val library = mockk<TraktLibraryService> {
            coEvery { hideForeignInitializationProjection(any()) } returns Unit
            coEvery { awaitInitializationImport(any()) } returns InitializationReadResult(3)
        }
        val markerStore = MemoryInitializationStore()
        val markers = InitializationPreferences(mockk<ProfileDataStoreFactory> {
            every { get(1, "first_use_initialization") } returns markerStore
        })
        val participant = TraktInitializationParticipant(profiles, data, auth, progress, library, markers)
    }
}
