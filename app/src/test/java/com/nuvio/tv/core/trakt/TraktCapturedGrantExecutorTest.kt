package com.nuvio.tv.core.trakt

import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.dto.trakt.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TraktCapturedGrantExecutorTest {
    @Test fun `profile switch during capture cannot adopt another viewer grant`() = runTest {
        val h = TraktActionHarness()
        val selection = h.store.selection()
        h.store.beforeRead = { h.store.selected = TraktActionSelection(2, 1) }
        assertFailure(TraktActionFailure.STALE) { h.executor.capture(selection) }
    }
    @Test fun `A to B to A selection revision invalidates an old session`() = runTest {
        val h = TraktActionHarness(); val session = h.session()
        h.store.selected = TraktActionSelection(1, 2)
        var calls = 0
        assertFailure(TraktActionFailure.STALE) { h.executor.execute(session) { _, _ -> calls++; Response.success(Unit) } }
        assertEquals(0, calls)
    }
    @Test fun `account reconnect revision invalidates old actions even if token values repeat`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); h.store.revision++
        assertFalse(h.executor.isCurrent(session))
    }
    @Test fun `profile switch after401 never refreshes or retries as the new viewer`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); var calls = 0
        assertFailure(TraktActionFailure.STALE) { h.executor.execute(session, true) { _, _ ->
            calls++; h.store.selected = TraktActionSelection(2, 1); httpError<Unit>(401)
        } }
        assertEquals(1, calls); assertEquals(0, h.refreshes)
    }
    @Test fun `ambiguous network failure is not replayed`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); var calls = 0
        assertFailure(TraktActionFailure.NETWORK_UNCERTAIN) { h.executor.execute<Unit>(session, true) { _, _ -> calls++; throw IOException("private URL") } }
        assertEquals(1, calls)
    }
    @Test fun `server503 response is not replayed`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); var calls = 0
        assertEquals(503, h.executor.execute<Unit>(session, true) { _, _ -> calls++; httpError(503) }.code())
        assertEquals(1, calls)
    }
    @Test fun `definite401 refreshes once and never loops on second401`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); val sent = mutableListOf<String>()
        val result = h.executor.execute<Unit>(session, true) { auth, _ -> sent += auth; httpError(401) }
        assertEquals(401, result.code()); assertEquals(1, h.refreshes)
        assertEquals(listOf("Bearer access-a", "Bearer refreshed-access"), sent)
    }
    @Test fun `another request already refreshed the failed token so it is reused without second refresh`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); var first = true
        val result = h.executor.execute<Unit>(session) { _, _ ->
            if(first) {
                first = false
                var nestedFirst = true
                h.executor.execute<Unit>(session) { _, _ ->
                    if(nestedFirst) { nestedFirst = false; httpError(401) } else Response.success(Unit)
                }
                httpError(401)
            } else Response.success(Unit)
        }
        assertTrue(result.isSuccessful); assertEquals(1,h.refreshes)
    }
    @Test fun `late refresh after profile switch cannot save tokens`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); h.beforeRefreshReturn = { h.store.selected = TraktActionSelection(2, 1) }
        assertFailure(TraktActionFailure.STALE) { h.executor.execute<Unit>(session) { _, _ -> httpError(401) } }
        assertEquals("access-a", h.store.states.getValue(1).accessToken); assertEquals(0, h.store.saves)
    }
    @Test fun `failed conditional refresh commit cannot publish new authorization`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); h.store.allowSave = false
        assertFailure(TraktActionFailure.STALE) { h.executor.execute<Unit>(session) { _, _ -> httpError(401) } }
        assertEquals("access-a", h.store.states.getValue(1).accessToken)
    }
    @Test fun `cancellation after noncooperative network response cannot commit refresh`() = runTest {
        val h = TraktActionHarness(); val session = h.session()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.beforeRefreshReturn = { coroutineContext.job.cancel() }
            h.executor.execute<Unit>(session) { _, _ -> httpError(401) }
        }
        job.join(); assertTrue(job.isCancelled); assertEquals(0, h.store.saves)
    }
    @Test fun `registration change invalidates captured actions`() = runTest {
        val h = TraktActionHarness(); val session = h.session(); h.store.app = TraktAppRegistration("other-public-client")
        assertFalse(h.executor.isCurrent(session))
    }
    @Test fun `diagnostic strings do not contain private grant values`() = runTest {
        val h = TraktActionHarness(); val text = h.session().toString() + TraktActionException(TraktActionFailure.NETWORK_UNCERTAIN)
        assertFalse(text.contains("access-a")); assertFalse(text.contains("refresh-a"))
    }
}

internal class TraktActionHarness {
    val store = TraktActionMemoryCredentials()
    var refreshes = 0
    var beforeRefreshReturn: (() -> Unit)? = null
    val executor = TraktCapturedGrantExecutor(store, {
        refreshes++; beforeRefreshReturn?.invoke()
        Response.success(TraktTokenResponseDto("refreshed-access", "Bearer", 604800, "rotated-refresh", createdAt = 1_000L))
    }, TraktTokenRefreshCoordinator(), { 1_001_000L }, { })
    suspend fun session() = executor.capture(store.selection())
}
internal class TraktActionMemoryCredentials : TraktActionCredentials {
    var selected = TraktActionSelection(1, 0)
    var revision = 0L
    var app = TraktAppRegistration("test-public-client")
    var beforeRead: (() -> Unit)? = null
    var allowSave = true
    var saves = 0
    val states = mutableMapOf(1 to TraktAuthState("access-a", "refresh-a", "Bearer", 1000, 604800,
        registrationClientId = "test-public-client", profileId = 1))
    override fun selection() = selected
    override fun registration() = app
    override fun grantRevision(profileId: Int) = revision
    override suspend fun read(profileId: Int): TraktAuthState { beforeRead?.invoke(); return states[profileId] ?: TraktAuthState(profileId = profileId) }
    override suspend fun saveRefresh(expected: TraktAuthState, revision: Long, token: TraktTokenResponseDto, selectionCurrent: () -> Boolean): Boolean {
        if (!allowSave || !selectionCurrent() || states[expected.profileId] != expected || this.revision != revision) return false
        saves++; states[expected.profileId] = expected.copy(accessToken = token.accessToken, refreshToken = token.refreshToken,
            tokenType = token.tokenType, createdAt = token.createdAt, expiresIn = token.expiresIn)
        return true
    }
}
internal fun <T> httpError(code: Int): Response<T> = Response.error(code, "{}".toResponseBody())
internal suspend fun assertFailure(expected: TraktActionFailure, block: suspend () -> Unit) {
    try { block(); fail("Expected guarded failure") } catch(e: TraktActionException) { assertEquals(expected, e.failure) }
}
