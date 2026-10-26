package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.PremiumizeCacheCheckDto
import com.nuvio.tv.data.remote.dto.TorboxCachedItemDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/** Production response interpretation must distinguish a conclusive miss from an unknown response. */
class LocalDebridServiceTest {
    @Test fun `TorBox missing response body success flag or data remain unknown`() = runTest {
        val api = mockk<TorboxApi>()
        val service = LocalDebridService(api, mockk())
        val responses = listOf(
            Response.success<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>>(null),
            Response.success(TorboxEnvelopeDto(success = null, data = emptyMap<String, TorboxCachedItemDto>())),
            Response.success(TorboxEnvelopeDto(success = false, data = emptyMap<String, TorboxCachedItemDto>())),
            Response.success(TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>(success = true, data = null))
        )
        for (response in responses) {
            coEvery { api.checkCached(any(), any(), any()) } returns response
            assertNull(service.checkCached(account(DebridProviders.Torbox), listOf(HASH_A)))
        }
        coVerify(exactly = responses.size) { api.checkCached(any(), any(), any()) }
    }

    @Test fun `TorBox explicit successful empty data is a conclusive negative`() = runTest {
        val api = mockk<TorboxApi>()
        coEvery { api.checkCached(any(), any(), any()) } returns Response.success(
            TorboxEnvelopeDto(success = true, data = emptyMap<String, TorboxCachedItemDto>()))
        val result = LocalDebridService(api, mockk()).checkCached(account(DebridProviders.Torbox), listOf(HASH_A))
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test fun `TorBox valid response canonicalizes returned hash and keeps metadata`() = runTest {
        val api = mockk<TorboxApi>()
        coEvery { api.checkCached(any(), any(), any()) } returns Response.success(TorboxEnvelopeDto(success = true,
            data = mapOf(HASH_A.uppercase() to TorboxCachedItemDto("episode.mkv", 123L))))
        val result = LocalDebridService(api, mockk()).checkCached(account(DebridProviders.Torbox), listOf(HASH_A.uppercase()))
        assertEquals(mapOf(HASH_A to LocalDebridCachedItem("episode.mkv", 123L)), result)
        coVerify(exactly = 1) { api.checkCached("Bearer synthetic-token", "object", match { it.hashes == listOf(HASH_A) }) }
    }

    @Test fun `Premiumize truncated missing and oversized response arrays remain unknown`() = runTest {
        val api = mockk<PremiumizeApi>()
        val service = LocalDebridService(mockk(), api)
        val responses = listOf(
            PremiumizeCacheCheckDto(status = "success", response = listOf(true)),
            PremiumizeCacheCheckDto(status = "success", response = null),
            PremiumizeCacheCheckDto(status = "success", response = listOf(true, false, true)),
            PremiumizeCacheCheckDto(status = "error", response = listOf(true, false))
        )
        for (response in responses) {
            coEvery { api.checkCache(any(), any()) } returns Response.success(response)
            assertNull(service.checkCached(account(DebridProviders.Premiumize), listOf(HASH_A, HASH_B)))
        }
        coVerify(exactly = responses.size) { api.checkCache(any(), any()) }
    }

    @Test fun `Premiumize exact response maps each boolean and tolerates missing optional metadata`() = runTest {
        val api = mockk<PremiumizeApi>()
        coEvery { api.checkCache(any(), any()) } returns Response.success(
            PremiumizeCacheCheckDto(status = "success", response = listOf(true, false), filename = null, filesize = null))
        val result = LocalDebridService(mockk(), api).checkCached(account(DebridProviders.Premiumize), listOf(HASH_A, HASH_B))
        assertEquals(mapOf(HASH_A to LocalDebridCachedItem(null, null)), result)
        coVerify(exactly = 1) { api.checkCache("Bearer synthetic-token", listOf("magnet:?xt=urn:btih:$HASH_A", "magnet:?xt=urn:btih:$HASH_B")) }
    }

    @Test fun `Premiumize exact all-false response is a conclusive negative`() = runTest {
        val api = mockk<PremiumizeApi>()
        coEvery { api.checkCache(any(), any()) } returns Response.success(
            PremiumizeCacheCheckDto(status = "success", response = listOf(false, false)))
        val result = LocalDebridService(mockk(), api).checkCached(account(DebridProviders.Premiumize), listOf(HASH_A, HASH_B))
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test fun `transport failures remain unknown while cancellation propagates for both cache services`() = runTest {
        val torbox = mockk<TorboxApi>()
        val premiumize = mockk<PremiumizeApi>()
        val service = LocalDebridService(torbox, premiumize)
        coEvery { torbox.checkCached(any(), any(), any()) } throws IOException("Synthetic network failure")
        coEvery { premiumize.checkCache(any(), any()) } throws IOException("Synthetic network failure")
        for (provider in listOf(DebridProviders.Torbox, DebridProviders.Premiumize)) {
            assertNull(service.checkCached(account(provider), listOf(HASH_A)))
        }
        coEvery { torbox.checkCached(any(), any(), any()) } throws CancellationException("Synthetic cancellation")
        coEvery { premiumize.checkCache(any(), any()) } throws CancellationException("Synthetic cancellation")
        for (provider in listOf(DebridProviders.Torbox, DebridProviders.Premiumize)) {
            try { service.checkCached(account(provider), listOf(HASH_A)); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
        }
    }

    private fun account(provider: DebridProvider) = DebridServiceCredential(provider, "synthetic-token")
    private companion object {
        const val HASH_A = "0123456789abcdef0123456789abcdef01234567"
        const val HASH_B = "fedcba9876543210fedcba9876543210fedcba98"
    }
}
