package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SimklPlaybackOriginTest {
    @Test fun `profile selection changed during post pacing prevents old scrobble transport`() = runTest {
        var current = true
        var now = 0L
        var requests = 0
        val pacing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val native = SimklAuthScope(1, 1)
        val auth = SimklAuthorization(native, "account-A")
        val grant = TrackingPlaybackGrant(TrackingProviderId.SIMKL, 1, "grant-A", { current }, SimklPlaybackAuthScope(native))
        val client = SimklApiClient(SimklHttpEngine { _, _, _, _ -> requests++; SimklRawHttpResponse(200, "{}") },
            SimklApiConfiguration("client", "syclop", "test"), { auth }, {}, { now },
            sleep = { pacing.complete(Unit); release.await(); now += it }, retryJitterMs = { 0L })
        client.execute(SimklApiRequest(SimklHttpMethod.POST, "/scrobble/start"))
        val task = async { runCatching { withContext(grant.requestContext + grant) {
            client.execute(SimklApiRequest(SimklHttpMethod.POST, "/scrobble/stop"))
        } } }
        runCurrent()
        assertTrue(pacing.isCompleted)
        current = false // The profile store observer may still expose the old native scope.
        release.complete(Unit)
        assertTrue(task.await().exceptionOrNull() is TrackingPlaybackAccountChangedException)
        assertEquals(1, requests)
    }

    @Test fun `same-grant401 rotation remains authorized by the captured playback origin`() = runTest {
        val native = SimklAuthScope(1, 1)
        var auth = SimklAuthorization(native, "initial")
        var now = 0L
        val headers = mutableListOf<String?>()
        val grant = TrackingPlaybackGrant(TrackingProviderId.SIMKL, 1, "grant-A", { true }, SimklPlaybackAuthScope(native))
        val client = SimklApiClient(SimklHttpEngine { _, _, header, _ ->
            headers += header["Authorization"]
            SimklRawHttpResponse(if (headers.size == 1) 401 else 200, "{}")
        }, SimklApiConfiguration("client", "syclop", "test"), { auth }, {}, { now },
            sleep = { now += it }, retryJitterMs = { 0L }, renewAuthorization = { captured, forced ->
                if (forced) auth = captured.copy(accessToken = "rotated")
                auth
            })
        val response = grant.execute { client.execute(SimklApiRequest(SimklHttpMethod.POST, "/scrobble/stop")) }
        assertEquals(200, response.status)
        assertEquals(listOf("Bearer initial", "Bearer rotated"), headers)
    }

    @Test fun `origin invalidated during401 cannot refresh or retry into replacement account`() = runTest {
        var current = true
        val native = SimklAuthScope(1, 1)
        val auth = SimklAuthorization(native, "account-A")
        var forcedRefreshes = 0
        var requests = 0
        val grant = TrackingPlaybackGrant(TrackingProviderId.SIMKL, 1, "grant-A", { current }, SimklPlaybackAuthScope(native))
        val client = SimklApiClient(SimklHttpEngine { _, _, _, _ ->
            requests++; current = false; SimklRawHttpResponse(401, "{}")
        }, SimklApiConfiguration("client", "syclop", "test"), { auth }, {},
            renewAuthorization = { captured, forced -> if (forced) forcedRefreshes++; captured })
        val result = runCatching { withContext(grant.requestContext + grant) {
            client.execute(SimklApiRequest(SimklHttpMethod.POST, "/scrobble/stop"))
        } }
        assertTrue(result.exceptionOrNull() is TrackingPlaybackAccountChangedException)
        assertEquals(1, requests)
        assertEquals(0, forcedRefreshes)
    }
}
