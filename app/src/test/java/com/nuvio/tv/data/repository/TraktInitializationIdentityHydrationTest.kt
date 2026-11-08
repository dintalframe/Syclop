package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.initialization.InitializationFailure
import com.nuvio.tv.core.initialization.InitializationReadException
import com.nuvio.tv.core.initialization.MemoryInitializationStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.trakt.TraktAppRegistration
import com.nuvio.tv.core.trakt.TraktAppRegistrationStore
import com.nuvio.tv.core.trakt.TraktTokenRefreshCoordinator
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.DebridCredentialCipher
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserSettingsResponseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class TraktInitializationIdentityHydrationTest {
    @Test
    fun `missing restored identity hydrates slug only without changing its grant`() = runTest {
        val h = Fixture()
        h.connect()
        val revision = h.auth.grantRevision(1)
        coEvery { h.api.getUserSettings(any()) } returns h.settings(slug = "viewer-slug")

        val account = h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())

        assertNull(account.username)
        assertEquals("viewer-slug", account.userSlug)
        assertTrue(account.isAuthenticated)
        assertEquals(revision, h.auth.grantRevision(1))
        assertEquals("synthetic-original-access", account.accessToken)
        assertEquals("viewer-slug", h.auth.getCurrentState(1).userSlug)
        coVerify(exactly = 1) { h.api.getUserSettings(any()) }
        coVerify(exactly = 0) { h.api.refreshToken(any()) }
    }

    @Test
    fun `usable cached username or slug does not require settings HTTP`() = runTest {
        for ((username, slug) in listOf("viewer-name" to null, null to "viewer-slug")) {
            val h = Fixture()
            h.connect()
            h.auth.saveUser(username, slug, 1)

            val account = h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())

            assertEquals(username, account.username)
            assertEquals(slug, account.userSlug)
            coVerify(exactly = 0) { h.api.getUserSettings(any()) }
        }
    }

    @Test
    fun `empty successful response rejects identity without clearing saved credentials`() = runTest {
        val h = Fixture()
        h.connect()
        val before = h.auth.getCurrentState(1)
        coEvery { h.api.getUserSettings(any()) } returns Response.success<TraktUserSettingsResponseDto>(null)

        expectFailure(InitializationFailure.INCOMPLETE) {
            h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
        }

        assertEquals(before, h.auth.getCurrentState(1))
        coVerify(exactly = 0) { h.notices.markTraktReconnectRequired() }
    }

    @Test
    fun `malformed identity is not persisted and a later retry can recover`() = runTest {
        val h = Fixture()
        h.connect()
        coEvery { h.api.getUserSettings(any()) } returns h.settings(username = "invalid\nname", slug = " ")
        expectFailure(InitializationFailure.INCOMPLETE) {
            h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
        }
        assertNull(h.auth.getCurrentState(1).username)
        assertNull(h.auth.getCurrentState(1).userSlug)

        coEvery { h.api.getUserSettings(any()) } returns h.settings(username = "viewer")
        assertEquals("viewer", h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope()).username)
        coVerify(exactly = 2) { h.api.getUserSettings(any()) }
    }

    @Test
    fun `network failure retains grant and explicit retry can hydrate`() = runTest {
        val h = Fixture()
        h.connect()
        val before = h.auth.getCurrentState(1)
        coEvery { h.api.getUserSettings(any()) } throws IOException("synthetic network failure")
        expectFailure(InitializationFailure.NETWORK) {
            h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
        }
        assertEquals(before, h.auth.getCurrentState(1))
        coVerify(exactly = 2) { h.api.getUserSettings(any()) }

        coEvery { h.api.getUserSettings(any()) } returns h.settings(slug = "recovered")
        assertEquals("recovered", h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope()).userSlug)
        coVerify(exactly = 3) { h.api.getUserSettings(any()) }
        coVerify(exactly = 0) { h.notices.markTraktReconnectRequired() }
    }

    @Test
    fun `retryable HTTP responses remain network failures and preserve credentials`() = runTest {
        for (status in listOf(429, 500, 503)) {
            val h = Fixture()
            h.connect()
            val before = h.auth.getCurrentState(1)
            coEvery { h.api.getUserSettings(any()) } answers { Response.error(status, "{}".toResponseBody()) }
            expectFailure(InitializationFailure.NETWORK) {
                h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
            }
            assertEquals(before, h.auth.getCurrentState(1))
            coVerify(exactly = 0) { h.notices.markTraktReconnectRequired() }
        }
    }

    @Test
    fun `forbidden settings response is unavailable and open circuit does not clear credentials`() = runTest {
        val h = Fixture()
        h.connect()
        val before = h.auth.getCurrentState(1)
        coEvery { h.api.getUserSettings(any()) } answers { Response.error(403, "{}".toResponseBody()) }
        repeat(2) {
            expectFailure(InitializationFailure.UNAVAILABLE) {
                h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
            }
        }
        assertEquals(before, h.auth.getCurrentState(1))
        coVerify(exactly = 1) { h.api.getUserSettings(any()) }
        coVerify(exactly = 0) { h.notices.markTraktReconnectRequired() }
    }

    @Test
    fun `settings unauthorized response refreshes same grant then hydrates with rotated token`() = runTest {
        val h = Fixture()
        h.connect()
        val revision = h.auth.grantRevision(1)
        val authorizations = mutableListOf<String>()
        coEvery { h.api.refreshToken(any()) } returns Response.success(h.token("rotated"))
        coEvery { h.api.getUserSettings(any()) } coAnswers {
            authorizations += firstArg<String>()
            if (authorizations.size == 1) Response.error(401, "{}".toResponseBody())
            else h.settings(slug = "same-viewer")
        }

        val account = h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())

        assertEquals("same-viewer", account.userSlug)
        assertEquals("synthetic-rotated-access", account.accessToken)
        assertEquals(revision, h.auth.grantRevision(1))
        assertEquals(listOf("Bearer synthetic-original-access", "Bearer synthetic-rotated-access"), authorizations)
        coVerify(exactly = 1) { h.api.refreshToken(any()) }
        coVerify(exactly = 0) { h.notices.markTraktReconnectRequired() }
    }

    @Test
    fun `cancellation during settings request propagates without saving identity`() = runTest {
        val h = Fixture()
        h.connect()
        val before = h.auth.getCurrentState(1)
        val entered = CompletableDeferred<Unit>()
        coEvery { h.api.getUserSettings(any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        var returned = false
        val job = launch {
            h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
            returned = true
        }
        entered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(before, h.auth.getCurrentState(1))
    }

    @Test
    fun `profile switch while settings request runs rejects late identity`() = runTest {
        val h = Fixture()
        h.connect()
        coEvery { h.api.getUserSettings(any()) } coAnswers {
            h.select(2)
            h.settings(slug = "old-account")
        }
        expectFailure(InitializationFailure.ACCOUNT_CHANGED) {
            h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
        }

        assertNull(h.auth.getCurrentState(1).userSlug)
        assertFalse(h.auth.getCurrentState(2).isAuthenticated)
        coVerify(exactly = 1) { h.api.getUserSettings(any()) }
    }

    @Test
    fun `away and back selection cannot adopt late settings even with same profile ID`() = runTest {
        val h = Fixture()
        h.connect()
        coEvery { h.api.getUserSettings(any()) } coAnswers {
            h.select(2)
            h.select(1)
            h.settings(slug = "old-account")
        }
        expectFailure(InitializationFailure.ACCOUNT_CHANGED) {
            h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
        }
        assertNull(h.auth.getCurrentState(1).userSlug)
    }

    @Test
    fun `same profile replacement grant cannot inherit late old settings identity`() = runTest {
        val h = Fixture()
        h.connect()
        coEvery { h.api.getUserSettings(any()) } coAnswers {
            h.connect("replacement")
            h.settings(username = "old-viewer", slug = "old-account")
        }
        expectFailure(InitializationFailure.ACCOUNT_CHANGED) {
            h.service.hydrateInitializationIdentity(h.service.captureInitializationReadScope())
        }

        val replacement = h.auth.getCurrentState(1)
        assertEquals("synthetic-replacement-access", replacement.accessToken)
        assertTrue(replacement.isAuthenticated)
        assertNull(replacement.username)
        assertNull(replacement.userSlug)
        coVerify(exactly = 0) { h.notices.markTraktReconnectRequired() }
    }

    private suspend fun expectFailure(reason: InitializationFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected initialization failure $reason")
        } catch (failure: InitializationReadException) {
            assertEquals(reason, failure.reason)
        }
    }

    private class Fixture {
        val activeProfile = MutableStateFlow(1)
        var selectionRevision = 0L
        private val stores = mutableMapOf<Int, MemoryInitializationStore>()
        private val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns this@Fixture.activeProfile
            every { activeProfileSelectionRevision } answers { selectionRevision }
        }
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers { stores.getOrPut(firstArg()) { MemoryInitializationStore() } }
        }
        private val cipher = mockk<DebridCredentialCipher> {
            every { encrypt(any(), any(), any()) } answers { "encrypted:" + firstArg<String>() }
            every { decrypt(any(), any(), any()) } answers { firstArg<String>().removePrefix("encrypted:") }
            every { isEncrypted(any()) } answers { firstArg<String>().startsWith("encrypted:") }
        }
        val auth = TraktAuthDataStore(factory, profiles, cipher)
        val api = mockk<TraktApi>()
        val notices = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        private val registration = TraktAppRegistration("synthetic-public-client")
        private val registrations = mockk<TraktAppRegistrationStore> {
            every { current() } returns this@Fixture.registration
        }
        val service = TraktAuthService(mockk<Context>(relaxed = true), api, auth, notices,
            registrations, TraktTokenRefreshCoordinator())

        suspend fun connect(label: String = "original") {
            auth.saveToken(token(label), 1, registration.clientId)
        }

        fun token(label: String) = TraktTokenResponseDto("synthetic-$label-access", "Bearer", 604800,
            "synthetic-$label-refresh", createdAt = System.currentTimeMillis() / 1000L)

        fun select(profile: Int) {
            selectionRevision++
            activeProfile.value = profile
        }

        fun settings(username: String? = null, slug: String? = null): Response<TraktUserSettingsResponseDto> =
            Response.success(TraktUserSettingsResponseDto(TraktUserDto(username, TraktIdsDto(slug = slug))))
    }
}
