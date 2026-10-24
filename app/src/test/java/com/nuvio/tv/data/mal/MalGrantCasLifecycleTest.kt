package com.nuvio.tv.data.mal

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MalGrantCasLifecycleTest {
    @Test fun `successful login creates a new same-profile grant identity`() = runTest {
        val h = Harness(this); val before = h.store.state.value.scope
        assertTrue(h.store.complete(before, h.a, "A"))
        val completed = h.store.state.value.scope
        assertEquals(before.profileId, completed.profileId)
        assertTrue(completed.generation > before.generation)
        assertFalse(h.store.isCurrent(before)); assertNull(h.store.credentials(before))
        assertEquals(h.a, h.store.credentials(completed))
    }
    @Test fun `old refresh and logout cannot overwrite or clear a replacement login`() = runTest {
        val h = Harness(this); val old = h.install(h.a)
        assertTrue(h.store.complete(old, h.b, "B"))
        val replacement = h.store.state.value.scope
        assertFalse(h.store.replaceTokens(old, h.a, h.rotated))
        assertFalse(h.store.signOutIfTokensCurrent(old, h.a))
        assertFalse(h.store.signOut(old))
        assertEquals(h.b, h.store.credentials(replacement)); assertEquals("B", h.store.state.value.username)
    }
    @Test fun `same-grant refresh uses token compare and set to reject a stale response`() = runTest {
        val h = Harness(this); val grant = h.install(h.a)
        assertTrue(h.store.replaceTokens(grant, h.a, h.rotated))
        assertFalse(h.store.replaceTokens(grant, h.a, h.a.copy(accessToken = "stale-refresh")))
        assertEquals(h.rotated, h.store.credentials(grant))
    }
    @Test fun `old401 cannot revoke a successfully rotated token of the same grant`() = runTest {
        val h = Harness(this); val grant = h.install(h.a)
        assertTrue(h.store.replaceTokens(grant, h.a, h.rotated))
        assertFalse(h.store.signOutIfTokensCurrent(grant, h.a))
        assertTrue(h.store.state.value.connected); assertEquals(h.rotated, h.store.credentials(grant))
    }
    @Test fun `explicit logout disconnects its current grant and invalidates old operations`() = runTest {
        val h = Harness(this); val grant = h.install(h.a)
        assertTrue(h.auth.signOut(grant))
        assertFalse(h.store.state.value.connected); assertNull(h.store.credentials(grant))
        assertFalse(h.store.isCurrent(grant))
        assertTrue(h.auth.notice.value.orEmpty().contains("disconnected"))
    }
    @Test fun `late old refresh success cannot replace a new login on the same viewer`() = runTest {
        val h = Harness(this); h.install(h.a.copy(expiresAtMs = 1L))
        val release = CompletableDeferred<Unit>()
        coEvery { h.api.refresh(any(), any()) } coAnswers { release.await(); h.rotated }
        val task = async { runCatching { h.auth.authorized { "old-result" } } }
        runCurrent()
        val beforeNew = h.store.state.value.scope
        assertTrue(h.store.complete(beforeNew, h.b, "B"))
        val replacement = h.store.state.value.scope
        release.complete(Unit); runCurrent()
        assertTrue(task.await().isFailure)
        assertEquals(h.b, h.store.credentials(replacement)); assertEquals("B", h.store.state.value.username)
    }
    @Test fun `late old refresh401 cannot sign out a completed replacement login`() = runTest {
        val h = Harness(this); h.install(h.a.copy(expiresAtMs = 1L))
        val release = CompletableDeferred<Unit>()
        coEvery { h.api.refresh(any(), any()) } coAnswers { release.await(); throw MalApiException(401) }
        val task = async { runCatching { h.auth.authorized { "old-result" } } }
        runCurrent(); assertTrue(h.store.complete(h.store.state.value.scope, h.b, "B"))
        val replacement = h.store.state.value.scope
        release.complete(Unit); runCurrent(); assertTrue(task.await().isFailure)
        assertTrue(h.store.state.value.connected); assertEquals(h.b, h.store.credentials(replacement))
    }
    @Test fun `old authorized401 cannot replay an earlier action using a new account`() = runTest {
        val h = Harness(this); h.install(h.a); var calls = 0
        val result = runCatching { h.auth.authorized<Unit> { token ->
            calls++; assertEquals(h.a.accessToken, token)
            assertTrue(h.store.complete(h.store.state.value.scope, h.b, "B"))
            throw MalApiException(401)
        } }
        assertTrue(result.isFailure); assertEquals(1, calls)
        coVerify(exactly = 0) { h.api.refresh(any(), any()) }
        assertEquals(h.b, h.store.credentials(h.store.state.value.scope))
    }
    @Test fun `old successful account result is rejected after replacement login`() = runTest {
        val h = Harness(this); h.install(h.a)
        val result = runCatching { h.auth.authorized {
            assertTrue(h.store.complete(h.store.state.value.scope, h.b, "B"))
            "old-private-library"
        } }
        assertTrue(result.isFailure)
        assertEquals(h.b, h.store.credentials(h.store.state.value.scope))
    }
    @Test fun `same-grant401 reuses a concurrent refresh without another token rotation`() = runTest {
        val h = Harness(this); val grant = h.install(h.a); var calls = 0
        val result = h.auth.authorized { token ->
            calls++
            if (calls == 1) {
                assertEquals(h.a.accessToken, token)
                assertTrue(h.store.replaceTokens(grant, h.a, h.rotated))
                throw MalApiException(401)
            }
            assertEquals(h.rotated.accessToken, token); "same-account-result"
        }
        assertEquals("same-account-result", result); assertEquals(2, calls)
        coVerify(exactly = 0) { h.api.refresh(any(), any()) }
    }
    @Test fun `queued explicit logout cannot disconnect a later completed grant`() = runTest {
        val h = Harness(this); val capturedLogout = h.install(h.a)
        assertTrue(h.store.complete(capturedLogout, h.b, "B"))
        assertFalse(h.auth.signOut(capturedLogout))
        assertTrue(h.store.state.value.connected); assertEquals(h.b, h.store.credentials(h.store.state.value.scope))
    }
    @Test fun `successful completion retires pending before observer and preserves Connected notice`() = runTest {
        val h = Harness(this); val before = h.store.state.value.scope
        h.callback(h.auth.connect()); runCurrent()
        assertTrue(h.store.state.value.connected)
        assertTrue(h.store.state.value.scope.generation > before.generation)
        assertNull(h.auth.session.value)
        assertEquals("Connected to MyAnimeList.", h.auth.notice.value)
        assertFalse(h.stages.contains(MalAuthStage.ACCOUNT_CHANGED))
        advanceTimeBy(501); runCurrent()
        assertTrue(h.listenerStopped); assertEquals("Connected to MyAnimeList.", h.auth.notice.value)
    }
    @Test fun `completed account cannot be cancelled by the retired authorization expiry timer`() = runTest {
        val h = Harness(this); h.callback(h.auth.connect()); runCurrent()
        advanceTimeBy(MalRules.AUTHORIZATION_DURATION_MS + 1L); runCurrent()
        assertTrue(h.store.state.value.connected); assertNull(h.auth.session.value)
        assertEquals("Connected to MyAnimeList.", h.auth.notice.value)
        assertFalse(h.stages.contains(MalAuthStage.EXPIRED))
    }

    private class Harness(scope: TestScope) {
        val a = MalTokens("account-A-access", "account-A-refresh", Long.MAX_VALUE)
        val b = MalTokens("account-B-access", "account-B-refresh", Long.MAX_VALUE)
        val rotated = MalTokens("account-A-rotated", "account-A-refresh-rotated", Long.MAX_VALUE)
        val store = MalAuthStore(MemoryMalAccountPersistence(), MutableStateFlow(1), scope.backgroundScope,
            malTestConfiguration, diagnose = { _, _ -> })
        val api = mockk<MalApiClient>()
        var listenerStopped = false
        val stages = mutableListOf<MalAuthStage>()
        init {
            every { api.authorizationUrl(any(), any(), any()) } answers {
                "https://myanimelist.net/v1/oauth2/authorize?state=" + URLEncoder.encode(secondArg<String>(), "UTF-8")
            }
            coEvery { api.exchange(any(), any(), any()) } returns a
            coEvery { api.username(any()) } returns "A"
        }
        val auth = MalAuthRepository(store, api, { "192.168.1.104" }, scope.backgroundScope,
            { 1_000_000L + scope.testScheduler.currentTime }, { scope.testScheduler.currentTime },
            MalCallbackListenerFactory { _, _ -> object : MalCallbackListener {
                override fun start() = Unit
                override fun stop() { listenerStopped = true }
            } }, diagnose = { stage, _ -> stages += stage }, liveSelection = { 1 to 0L })
        fun install(tokens: MalTokens): MalScope {
            assertTrue(store.complete(store.state.value.scope, tokens, "A"))
            return store.state.value.scope
        }
        fun callback(session: MalAuthorizationSession) {
            val encoded = URI(session.authorizationUrl).rawQuery.substringAfter("state=")
            val state = URLDecoder.decode(encoded, "UTF-8")
            auth.submitCallback("http://192.168.1.104:17893/mal/callback?code=synthetic-code&state=" + URLEncoder.encode(state, "UTF-8"))
        }
    }
}
