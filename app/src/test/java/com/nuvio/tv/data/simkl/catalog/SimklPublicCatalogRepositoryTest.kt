package com.nuvio.tv.data.simkl.catalog

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.mal.MalMetadataService
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.data.repository.CatalogRepositoryImpl
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.AddonRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class SimklPublicCatalogRepositoryTest {
    @Test fun `native MAL catalog detail stays exact MAL and never constructs a remote Simkl meta URL`() = runTest {
        val api = mockk<AddonApi>()
        val mal = mockk<MalMetadataService>()
        val meta = mockk<Meta>()
        coEvery { mal.meta("mal:59978") } returns meta
        val simkl = mockk<SimklPublicCatalog>() { every { withAttribution(any()) } answers { firstArg() } }
        val repository = MetaRepositoryImpl(mockk<Context>(relaxed = true), api, mockk(relaxed = true),
            mockk(relaxed = true), malMetadata = mal, simklCatalog = simkl)
        val result = repository.getMeta(SimklPublicCatalog.BASE_URL, "anime", "mal:59978").last() as NetworkResult.Success
        assertSame(meta, result.data)
        coVerify(exactly = 1) { mal.meta("mal:59978") }
        coVerify(exactly = 0) { api.getMeta(any()) }
    }

    @Test fun `native TV exact IMDb delegates fixed Cinemeta metadata endpoint without guessing IDs`() = runTest {
        val api = mockk<AddonApi>()
        val payload = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MetaResponseDto::class.java)
            .fromJson("""{"meta":{"id":"tt1234567","type":"series","name":"Exact show"}}""")!!
        val requests = mutableListOf<String>()
        coEvery { api.getMeta(capture(requests)) } returns Response.success(payload)
        val addons = mockk<AddonRepository>() { every { getInstalledAddons() } returns flowOf(listOf(
            Addon(id = "cinemeta", name = "Cinemeta", version = "1", description = null, logo = null,
                baseUrl = SimklPublicCatalog.CINEMETA_URL, catalogs = emptyList(), types = listOf(ContentType.SERIES),
                resources = listOf(AddonResource("meta", listOf("series"), listOf("tt")))))) }
        val simkl = mockk<SimklPublicCatalog>() { every { withAttribution(any()) } answers { firstArg() } }
        val repository = MetaRepositoryImpl(mockk<Context>(relaxed = true), api, addons, mockk(relaxed = true), simklCatalog = simkl)
        val result = repository.getMeta(SimklPublicCatalog.BASE_URL, "series", "tt1234567").last() as NetworkResult.Success
        assertEquals("tt1234567", result.data.id)
        assertEquals(listOf("https://v3-cinemeta.strem.io/meta/series/tt1234567.json"), requests)
    }

    @Test fun `native detail rejects unknown identities instead of fuzzy metadata fallback`() = runTest {
        val api = mockk<AddonApi>()
        val repository = MetaRepositoryImpl(mockk<Context>(relaxed = true), api, mockk(relaxed = true), mockk(relaxed = true))
        for (id in listOf("simkl:12345", "mal:59978:1", "tt1234567:2:1", "https://example.test/private"))
            assertTrue(repository.getMeta(SimklPublicCatalog.BASE_URL, "series", id).last() is NetworkResult.Error)
        coVerify(exactly = 0) { api.getMeta(any()) }
    }

    @Test fun `native catalog requires exact builtin addon ID before invoking backend`() = runTest {
        val api = mockk<AddonApi>()
        val simkl = mockk<SimklPublicCatalog>()
        val row = mockk<CatalogRow>()
        coEvery { simkl.catalog("anime-week", 0) } returns row
        val repository = CatalogRepositoryImpl(mockk<Context>(relaxed = true), api, mockk(relaxed = true),
            mockk(relaxed = true), simklCatalog = simkl)
        suspend fun load(id: String) = repository.getCatalog(SimklPublicCatalog.BASE_URL, id, "Simkl Trending",
            "anime-week", "Simkl Trending Anime", "anime").last()
        assertTrue(load("untrusted.remote") is NetworkResult.Error)
        assertSame(row, (load(SimklPublicCatalog.ADDON_ID) as NetworkResult.Success).data)
        coVerify(exactly = 1) { simkl.catalog("anime-week", 0) }
        coVerify(exactly = 0) { api.getCatalog(any()) }
    }
}
