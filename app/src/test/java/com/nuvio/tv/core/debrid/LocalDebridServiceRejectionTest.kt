package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.PremiumizeCacheCheckDto
import com.nuvio.tv.data.remote.dto.TorboxCachedItemDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.domain.model.DebridSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class LocalDebridServiceRejectionTest {
    @Test fun `Torbox cache rate limit retains numeric retry delay without exposing response text`() = runTest {
        val tb = mockk<TorboxApi>()
        val response = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://service.example.test/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Rate limited").header("Retry-After", "120").build()
        coEvery { tb.checkCached(any(), any(), any()) } returns Response.error<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>>(
            "private-token".toResponseBody(), response)
        val service = LocalDebridService(tb, mockk())
        val account = DebridSettings(enabled = true, torboxApiKey = "synthetic-tb").activeResolverCredential!!
        try { service.isCached(account, "a".repeat(40)); fail("Expected typed rejection") }
        catch (error: DirectDebridHttpException) {
            assertEquals(429, error.status)
            assertEquals(120L, error.retryAfterSeconds)
            assertFalse(error.toString().contains("private-token"))
        }
    }

    @Test fun `Premiumize cache authentication rejection stays distinct from unknown and not cached`() = runTest {
        val pm = mockk<PremiumizeApi>()
        coEvery { pm.checkCache(any(), any()) } returns Response.error<PremiumizeCacheCheckDto>(401, "private-token".toResponseBody())
        val service = LocalDebridService(mockk(), pm)
        val account = DebridSettings(enabled = true, premiumizeApiKey = "synthetic-pm").activeResolverCredential!!
        try { service.checkCached(account, listOf("a".repeat(40))); fail("Expected typed rejection") }
        catch (error: DirectDebridHttpException) { assertEquals(401, error.status) }
    }

    @Test fun `cache cancellation is never converted into an unknown observation`() = runTest {
        val tb = mockk<TorboxApi>()
        coEvery { tb.checkCached(any(), any(), any()) } throws CancellationException("Cancelled")
        val account = DebridSettings(enabled = true, torboxApiKey = "synthetic-tb").activeResolverCredential!!
        try { LocalDebridService(tb, mockk()).isCached(account, "a".repeat(40)); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }
}
