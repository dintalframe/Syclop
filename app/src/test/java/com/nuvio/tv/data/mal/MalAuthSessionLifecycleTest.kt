package com.nuvio.tv.data.mal

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class MalAuthSessionLifecycleTest {
    @Test fun `accepted callback remains completing until tokens account and durable state succeed`() = runTest {
        val h = Harness(this); val release = CompletableDeferred<Unit>()
        coEvery { h.api.exchange(any(), any(), any()) } coAnswers { release.await(); malTestTokens }
        val session = h.auth.connect(); h.callback(session); runCurrent()
        assertEquals(true, h.auth.session.value?.completing); assertFalse(h.store.state.value.connected)
        release.complete(Unit); runCurrent()
        assertTrue(h.store.state.value.connected); assertEquals("Connected to MyAnimeList.", h.auth.notice.value)
        advanceTimeBy(501); runCurrent(); assertNull(h.auth.session.value)
        assertEquals(listOf(MalAuthStage.SCAN_CREATED, MalAuthStage.CALLBACK_RECEIVED, MalAuthStage.TOKEN_EXCHANGE,
            MalAuthStage.ACCOUNT_READ, MalAuthStage.PERSIST, MalAuthStage.CONNECTED), h.stages)
    }
    @Test fun `user close during a noncooperative exchange prevents account read and persistence`() = runTest {
        val h = Harness(this); val release = CompletableDeferred<Unit>()
        coEvery { h.api.exchange(any(), any(), any()) } coAnswers { withContext(NonCancellable) { release.await(); malTestTokens } }
        val session = h.auth.connect(); h.callback(session); runCurrent(); h.auth.cancel()
        release.complete(Unit); runCurrent()
        assertFalse(h.store.state.value.connected); assertNull(h.auth.session.value)
        assertTrue(h.auth.notice.value.orEmpty().contains("cancelled"))
        coVerify(exactly = 0) { h.api.username(any()) }
    }
    @Test fun `profile change during account read cannot persist into either new or old profile`() = runTest {
        val h = Harness(this); val release = CompletableDeferred<Unit>()
        coEvery { h.api.username(any()) } coAnswers { withContext(NonCancellable) { release.await(); "Viewer" } }
        val session = h.auth.connect(); h.callback(session); runCurrent()
        h.profiles.value = 2; runCurrent(); release.complete(Unit); runCurrent()
        assertFalse(h.store.state.value.connected); assertEquals(2, h.store.state.value.scope.profileId)
        assertFalse(h.persistence.values.containsKey("account.p1")); assertFalse(h.persistence.values.containsKey("account.p2"))
    }
    @Test fun `five minute expiry is explicit and cannot leave stale scan notice`() = runTest {
        val h = Harness(this); h.auth.connect(); runCurrent()
        advanceTimeBy(MalRules.AUTHORIZATION_DURATION_MS + 1); runCurrent()
        assertNull(h.auth.session.value); assertTrue(h.auth.notice.value.orEmpty().contains("expired"))
        assertTrue(h.stages.contains(MalAuthStage.EXPIRED))
    }
    @Test fun `screen close reports cancellation rather than pretending a scan is pending`() = runTest {
        val h = Harness(this); h.auth.connect(); h.auth.cancel(MalAuthStage.SCREEN_CLOSED)
        assertNull(h.auth.session.value); assertTrue(h.auth.notice.value.orEmpty().contains("screen closed"))
        assertTrue(h.listenerStopped)
    }
    @Test fun `HTTP account failure leaves account disconnected and logs only safe stage class and status`() = runTest {
        val h = Harness(this); coEvery { h.api.username(any()) } throws MalApiException(401)
        h.callback(h.auth.connect()); runCurrent(); advanceTimeBy(501); runCurrent()
        assertFalse(h.store.state.value.connected); assertTrue(h.auth.notice.value.orEmpty().contains("failed"))
        assertTrue(h.messages.contains("stage=account_read class=MalApiException status=401"))
    }
    @Test fun `persistence failure cannot falsely publish connected and is distinguished from OAuth failure`() = runTest {
        val h = Harness(this); h.persistence.failWrites = true
        h.callback(h.auth.connect()); runCurrent(); advanceTimeBy(501); runCurrent()
        assertFalse(h.store.state.value.connected); assertNull(h.store.credentials(h.store.state.value.scope))
        assertTrue(h.auth.notice.value.orEmpty().contains("could not be saved"))
        assertTrue(h.messages.any { it == "stage=persist class=IOException" })
        assertTrue(h.messages.none { it.contains("private-test") || it.contains("private detail") || it.contains("http") })
    }
    @Test fun `duplicate callback cannot exchange the same authorization twice`() = runTest {
        val h = Harness(this); val release = CompletableDeferred<Unit>()
        coEvery { h.api.exchange(any(), any(), any()) } coAnswers { release.await(); malTestTokens }
        val session = h.auth.connect(); h.callback(session); runCurrent()
        assertThrows(IllegalArgumentException::class.java) { h.callback(session) }
        release.complete(Unit); runCurrent()
        coVerify(exactly = 1) { h.api.exchange(any(), any(), any()) }
    }
    private class Harness(scope: TestScope) {
        val persistence = MemoryMalAccountPersistence(); val profiles = MutableStateFlow(1)
        val store = MalAuthStore(persistence, profiles, scope.backgroundScope, malTestConfiguration, diagnose = { _, _ -> })
        val api = mockk<MalApiClient>()
        val stages = mutableListOf<MalAuthStage>(); val messages = mutableListOf<String>()
        var listenerStopped = false
        init {
            every { api.authorizationUrl(any(), any(), any()) } answers {
                "https://myanimelist.net/v1/oauth2/authorize?state=" + URLEncoder.encode(secondArg<String>(), "UTF-8")
            }
            coEvery { api.exchange(any(), any(), any()) } returns malTestTokens
            coEvery { api.username(any()) } returns "Viewer"
        }
        val auth = MalAuthRepository(store, api, { "192.168.1.104" }, scope.backgroundScope,
            { 1_000_000 + scope.testScheduler.currentTime }, { scope.testScheduler.currentTime },
            MalCallbackListenerFactory { _, _ -> object : MalCallbackListener {
                override fun start() = Unit
                override fun stop() { listenerStopped = true }
            } }, { stage, failure -> stages += stage; messages += MalAuthDiagnostics.message(stage, failure) })
        fun callback(session: MalAuthorizationSession) {
            val encodedState = URI(session.authorizationUrl).rawQuery.substringAfter("state=")
            val state = URLDecoder.decode(encodedState, "UTF-8")
            auth.submitCallback("http://192.168.1.104:17893/mal/callback?code=one-time-test-code&state=" + URLEncoder.encode(state, "UTF-8"))
        }
    }
}
