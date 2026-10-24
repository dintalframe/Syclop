package com.nuvio.tv.data.mal

import io.mockk.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class MalLiveSelectionLifecycleTest {
    @Test fun `callback is rejected after away-back even before profile flow delivers`() = runTest {
        val h = Fixture(this); val session = h.auth.connect()
        h.selection = 1 to 2L // Same observed profile and MalScope, but a different synchronous selection revision.
        assertThrows(IllegalArgumentException::class.java) { h.callback(session) }
        assertFalse(h.store.state.value.connected); assertNull(h.auth.session.value)
        assertTrue(h.auth.notice.value.orEmpty().contains("active account changed"))
        coVerify(exactly = 0) { h.api.exchange(any(), any(), any()) }
    }
    @Test fun `late noncooperative exchange cannot read account or persist after unobserved selection change`() = runTest {
        val h = Fixture(this); val release = CompletableDeferred<Unit>()
        coEvery { h.api.exchange(any(), any(), any()) } coAnswers { withContext(NonCancellable) { release.await(); malTestTokens } }
        h.callback(h.auth.connect()); runCurrent(); h.selection = 1 to 2L
        release.complete(Unit); runCurrent()
        assertFalse(h.store.state.value.connected); assertNull(h.store.credentials(h.store.state.value.scope))
        coVerify(exactly = 0) { h.api.username(any()) }
    }
    @Test fun `authorized response is not published after selection changes without flow delivery`() = runTest {
        val h = Fixture(this); h.store.complete(h.store.state.value.scope, malTestTokens.copy(expiresAtMs = Long.MAX_VALUE), "Viewer")
        try {
            h.auth.authorized { h.selection = 1 to 2L; "private-account-result" }
            fail("Expected stale selection rejection")
        } catch (expected: IllegalArgumentException) { assertFalse(expected.toString().contains("private-account-result")) }
    }
    @Test fun `old selection401 cannot trigger refresh or replay using a newly active viewer`() = runTest {
        val h = Fixture(this); h.store.complete(h.store.state.value.scope, malTestTokens.copy(expiresAtMs = Long.MAX_VALUE), "Viewer")
        try {
            h.auth.authorized<Unit> { h.selection = 2 to 1L; throw MalApiException(401) }
            fail("Expected stale selection rejection")
        } catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { h.api.refresh(any(), any()) }
    }
    private class Fixture(scope: TestScope) {
        var selection = 1 to 0L
        val store = MalAuthStore(MemoryMalAccountPersistence(), MutableStateFlow(1), scope.backgroundScope,
            malTestConfiguration, diagnose = { _, _ -> })
        val api = mockk<MalApiClient>()
        init {
            every { api.authorizationUrl(any(), any(), any()) } answers { "https://myanimelist.net/v1/oauth2/authorize?state=" + URLEncoder.encode(secondArg<String>(), "UTF-8") }
            coEvery { api.exchange(any(), any(), any()) } returns malTestTokens
            coEvery { api.username(any()) } returns "Viewer"
        }
        val auth = MalAuthRepository(store, api, { "192.168.1.104" }, scope.backgroundScope,
            { 1_000_000L + scope.testScheduler.currentTime }, { scope.testScheduler.currentTime },
            MalCallbackListenerFactory { _, _ -> object : MalCallbackListener { override fun start() = Unit; override fun stop() = Unit } },
            diagnose = { _, _ -> }, liveSelection = { selection })
        fun callback(session: MalAuthorizationSession) {
            val state = URLDecoder.decode(URI(session.authorizationUrl).rawQuery.substringAfter("state="), "UTF-8")
            auth.submitCallback("http://192.168.1.104:17893/mal/callback?code=synthetic-code&state=" + URLEncoder.encode(state, "UTF-8"))
        }
    }
}
