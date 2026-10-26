package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class CapturedDebridOperationTest {
    @Test fun `actual provider uses captured credential without reading a later active account`() = runTest {
        val settings = mockk<DebridSettingsDataStore>()
        val api = mockk<RealDebridApi>()
        val resolver = RealDebridDirectDebridResolver(settings, api, mockk())
        coEvery { api.addMagnet(any(), any()) } coAnswers {
            assertEquals("Bearer account-A", firstArg<String>())
            throw IOException("Synthetic transport failure")
        }
        val result = resolver.resolve(stream(), 1, 2, CapturedDebridOperation(DebridProviders.REAL_DEBRID_ID, "account-A") { true })
        assertEquals(DirectDebridResolveResult.Failure(DirectDebridFailure(DirectDebridFailureReason.NETWORK_FAILURE, "realdebrid")), result)
        coVerify(exactly = 1) { api.addMagnet("Bearer account-A", any()) }
        verify(exactly = 0) { settings.settings }
    }
    @Test fun `actual provider stops between add and select when initiating viewer changes`() = runTest {
        val settings = mockk<DebridSettingsDataStore>(); val api = mockk<RealDebridApi>()
        val resolver = RealDebridDirectDebridResolver(settings, api, mockk())
        var current = true
        coEvery { api.addMagnet(any(), any()) } coAnswers {
            assertEquals("Bearer account-A", firstArg<String>())
            current = false
            Response.success(RealDebridAddTorrentDto(id = "private-cloud-job"))
        }
        try {
            resolver.resolve(stream(), 1, 2, CapturedDebridOperation(DebridProviders.REAL_DEBRID_ID, "account-A") { current })
            fail("A stale scope must cancel before the next service request")
        } catch (_: CancellationException) { }
        coVerify(exactly = 1) { api.addMagnet("Bearer account-A", any()) }
        coVerify(exactly = 0) { api.getTorrentInfo(any(), any()) }
        coVerify(exactly = 0) { api.selectFiles(any(), any(), any()) }
        coVerify(exactly = 0) { api.unrestrictLink(any(), any()) }
        verify(exactly = 0) { settings.settings }
    }
    @Test fun `already changed operation contacts neither active nor captured service account`() = runTest {
        val settings = mockk<DebridSettingsDataStore>(); val api = mockk<RealDebridApi>()
        val resolver = RealDebridDirectDebridResolver(settings, api, mockk())
        try {
            resolver.resolve(stream(), 1, 2, CapturedDebridOperation(DebridProviders.REAL_DEBRID_ID, "account-A") { false })
            fail("A stale operation must be rejected")
        } catch (_: CancellationException) { }
        coVerify(exactly = 0) { api.addMagnet(any(), any()) }
        verify(exactly = 0) { settings.settings }
    }
    @Test fun `captured service credentials cannot be reused with a different provider`() {
        val operation = CapturedDebridOperation(DebridProviders.REAL_DEBRID_ID, "private-key") { true }
        assertThrows(IllegalStateException::class.java) { operation.credential(DebridProviders.ALL_DEBRID_ID) }
        assertFalse(operation.toString().contains("private-key"))
    }
    @Test fun `request callback is never invoked after scope invalidation`() = runTest {
        var requests = 0
        val operation = CapturedDebridOperation(DebridProviders.ALL_DEBRID_ID, "account-A") { false }
        try { operation.request { requests++; "result" }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(0, requests)
    }
    @Test fun `late response is not published and next request is not repeated on a new account`() = runTest {
        var current = true; var requests = 0; var published = false
        val operation = CapturedDebridOperation(DebridProviders.ALL_DEBRID_ID, "account-A") { current }
        try { operation.request { requests++; current = false; "old-account-response" }; published = true }
        catch (_: CancellationException) { }
        assertFalse(published); assertEquals(1, requests)
        try { operation.request { requests++; "new-account-response" } } catch (_: CancellationException) { }
        assertEquals(1, requests)
    }

    @Test fun `captured HTTP rejection preserves status and retry delay without response text`() = runTest {
        val operation = CapturedDebridOperation(DebridProviders.REAL_DEBRID_ID, "private-key") { true }
        val response = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://service.example.test/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Rate limited").header("Retry-After", "120").build()
        try {
            operation.request { Response.error<String>("private-key".toResponseBody(), response) }
            fail("Expected typed service rejection")
        } catch (error: DirectDebridHttpException) {
            assertEquals(429, error.status)
            assertEquals(120L, error.retryAfterSeconds)
            assertFalse(error.toString().contains("private-key"))
        }
    }
    private fun stream() = Stream(name = "test", title = null, description = null, url = null, ytId = null,
        infoHash = "a".repeat(40), fileIdx = 0, externalUrl = null, behaviorHints = null, addonName = "test", addonLogo = null,
        clientResolve = StreamClientResolve(type = "debrid", infoHash = "a".repeat(40), fileIdx = 0,
            magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}", sources = null, torrentName = null,
            filename = null, mediaType = null, mediaId = null, mediaOnlyId = null, title = null,
            season = 1, episode = 2, service = DebridProviders.REAL_DEBRID_ID,
            serviceIndex = null, serviceExtension = null, isCached = true))
}
