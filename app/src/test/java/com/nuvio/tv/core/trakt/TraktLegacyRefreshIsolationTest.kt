package com.nuvio.tv.core.trakt

import android.content.Context
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.TraktAuthService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TraktLegacyRefreshIsolationTest {
    @Test fun `late legacy refresh cannot resurrect a logged out grant`() = runTest {
        val h = Fixture()
        coEvery { h.api.refreshToken(any()) } coAnswers {
            h.authState = TraktAuthState(profileId = 1); h.revision++
            Response.success(h.token("late"))
        }
        assertFalse(h.service.refreshTokenIfNeeded(true))
        assertFalse(h.authState.isAuthenticated)
        coVerify(exactly = 0) { h.store.saveToken(any(), any(), any()) }
    }
    @Test fun `terminal old refresh cannot clear a newly connected account`() = runTest {
        val h = Fixture()
        coEvery { h.api.refreshToken(any()) } coAnswers {
            h.authState = h.authState.copy(accessToken = "new-access", refreshToken = "new-refresh"); h.revision++
            Response.error(400, "{}".toResponseBody())
        }
        assertFalse(h.service.refreshTokenIfNeeded(true))
        assertEquals("new-access",h.authState.accessToken)
        coVerify(exactly = 0) { h.notices.markTraktReconnectRequired() }
    }
    @Test fun `same captured grant still refreshes successfully without changing its revision`() = runTest {
        val h = Fixture(); coEvery { h.api.refreshToken(any()) } returns Response.success(h.token("fresh"))
        assertTrue(h.service.refreshTokenIfNeeded(true))
        assertEquals("fresh-access",h.authState.accessToken); assertEquals(0L,h.revision)
    }
    @Test fun `terminal current grant is conditionally cleared and receives reconnect notice`() = runTest {
        val h = Fixture(); coEvery { h.api.refreshToken(any()) } returns Response.error(401,"{}".toResponseBody())
        assertFalse(h.service.refreshTokenIfNeeded(true)); assertFalse(h.authState.isAuthenticated)
        coVerify(exactly = 1) { h.notices.markTraktReconnectRequired() }
    }
    @Test fun `generic request cannot retry viewer A401 under viewer B`() = runTest {
        val h = Fixture(); var calls=0
        val response = h.service.executeAuthorizedRequest<Unit> {
            calls++; h.profile=2; h.selectionRevision++
            Response.error(401,"{}".toResponseBody())
        }
        assertNull(response); assertEquals(1,calls)
        coVerify(exactly = 0) { h.api.refreshToken(any()) }
    }
    @Test fun `generic request detects A to B to A revision even when ID is unchanged`() = runTest {
        val h = Fixture(); var calls=0
        assertNull(h.service.executeAuthorizedRequest<Unit> {
            calls++; h.selectionRevision += 2
            Response.error(401,"{}".toResponseBody())
        })
        assertEquals(1,calls); coVerify(exactly = 0) { h.api.refreshToken(any()) }
    }
    @Test fun `generic request stops retry after same-profile account replacement`() = runTest {
        val h = Fixture(); var calls=0
        assertNull(h.service.executeAuthorizedRequest<Unit> {
            calls++; h.revision++; h.authState=h.authState.copy(accessToken="replacement",refreshToken="replacement-refresh")
            Response.error(401,"{}".toResponseBody())
        })
        assertEquals(1,calls); assertEquals("replacement",h.authState.accessToken)
    }
    @Test fun `write queued in pacing delay cannot adopt viewer B`() = runTest {
        val h=Fixture(); var calls=0
        h.service.executeAuthorizedWriteRequest { calls++; Response.success(Unit) }
        val second=launch { assertNull(h.service.executeAuthorizedWriteRequest { calls++; Response.success(Unit) }) }
        runCurrent(); h.profile=2; h.selectionRevision++
        second.join(); assertEquals(1,calls)
    }
    @Test fun `queued write detects away and back while waiting in pacing delay`() = runTest {
        val h=Fixture(); var calls=0
        h.service.executeAuthorizedWriteRequest { calls++; Response.success(Unit) }
        val second=launch { assertNull(h.service.executeAuthorizedWriteRequest { calls++; Response.success(Unit) }) }
        runCurrent(); h.selectionRevision+=2
        second.join(); assertEquals(1,calls)
    }
    private class Fixture {
        var profile=1; var selectionRevision=0L; var revision=0L
        var authState=TraktAuthState("old-access","old-refresh","Bearer",System.currentTimeMillis()/1000,604800,
            registrationClientId="test-public-client",profileId=1)
        val api=mockk<TraktApi>()
        val notices=mockk<AuthSessionNoticeDataStore>(relaxed=true)
        val store=mockk<TraktAuthDataStore> {
            every { activeProfileId } answers { profile }
            every { activeProfileSelectionRevision } answers { selectionRevision }
            every { grantRevision(any()) } answers { revision }
            coEvery { getCurrentState() } coAnswers { authState }
            coEvery { getCurrentState(any()) } coAnswers { authState }
            coEvery { saveRefreshedTokenIfCurrent(any(),any(),any(),any()) } coAnswers {
                val expected=secondArg<TraktAuthState>(); val expectedRevision=thirdArg<Long>(); val current=arg<()->Boolean>(3)
                if(!current() || authState!=expected || revision!=expectedRevision) false else {
                    val token=firstArg<TraktTokenResponseDto>()
                    authState=authState.copy(accessToken=token.accessToken,refreshToken=token.refreshToken,createdAt=token.createdAt,expiresIn=token.expiresIn)
                    true
                }
            }
            coEvery { clearAuthIfCurrent(any(),any(),any()) } coAnswers {
                if(!thirdArg<()->Boolean>()() || authState!=firstArg<TraktAuthState>() || revision!=secondArg<Long>()) false
                else { authState=TraktAuthState(profileId=1); revision++; true }
            }
        }
        val registration=mockk<TraktAppRegistrationStore> { every { current() } returns TraktAppRegistration("test-public-client") }
        val service=TraktAuthService(mockk<Context>(relaxed=true),api,store,notices,registration,TraktTokenRefreshCoordinator())
        fun token(prefix:String)=TraktTokenResponseDto("$prefix-access","Bearer",604800,"$prefix-refresh",createdAt=System.currentTimeMillis()/1000)
    }
}
