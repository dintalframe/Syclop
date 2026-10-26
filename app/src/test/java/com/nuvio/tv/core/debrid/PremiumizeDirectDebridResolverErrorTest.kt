package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class PremiumizeDirectDebridResolverErrorTest {
    @Test fun `arbitrary cache and not found error text cannot become a cache miss or stale source`() = runTest {
        val api = mockk<PremiumizeApi>()
        val store = mockk<DebridSettingsDataStore>()
        val selector = mockk<PremiumizeDirectDownloadFileSelector>()
        for (message in listOf("cache server unavailable", "Not found: account authorization", "private-token")) {
            coEvery { api.directDownload(any(), any()) } returns Response.success(PremiumizeDirectDownloadDto(
                status = "error", message = message, code = "unrecognized"))
            val result = PremiumizeDirectDebridResolver(store, api, selector).resolve(source(), 1, 4,
                CapturedDebridOperation("premiumize", "synthetic-pm") { true }) as DirectDebridResolveResult.Failure
            assertEquals(DirectDebridFailureReason.UNAVAILABLE, result.detail.reason)
            assertFalse(result.toString().contains(message))
        }
        coVerify(exactly = 3) { api.directDownload("Bearer synthetic-pm", any()) }
    }

    private fun source() = Stream(null, "Show S01E04", null, null, null, "a".repeat(40), null,
        null, null, "Synthetic", null, clientResolve = StreamClientResolve(type = "torrent", infoHash = "a".repeat(40),
            fileIdx = null, magnetUri = null, sources = null, torrentName = null, filename = null,
            mediaType = "series", mediaId = null, mediaOnlyId = null, title = "Show", season = 1, episode = 4,
            service = "premiumize", serviceIndex = null, serviceExtension = null, isCached = null))
}
