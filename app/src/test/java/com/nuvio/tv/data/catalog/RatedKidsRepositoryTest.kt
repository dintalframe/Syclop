package com.nuvio.tv.data.catalog

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.data.repository.CatalogRepositoryImpl
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class RatedKidsRepositoryTest {
    @Test fun `native Kids IMDb detail delegates only the fixed metadata endpoint with exact media type`() = runTest {
        val api = mockk<AddonApi>()
        val payload = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MetaResponseDto::class.java)
            .fromJson("""{"meta":{"id":"tt1234567","type":"movie","name":"Exact family movie"}}""")!!
        val urls = mutableListOf<String>()
        coEvery { api.getMeta(capture(urls)) } returns Response.success(payload)
        val addons = mockk<AddonRepository>() { every { getInstalledAddons() } returns flowOf(listOf(
            Addon(id = "cinemeta", name = "Cinemeta", version = "1", description = null, logo = null,
                baseUrl = RatedKidsCatalog.CINEMETA_URL, catalogs = emptyList(), types = listOf(ContentType.MOVIE),
                resources = listOf(AddonResource("meta", listOf("movie"), listOf("tt")))))) }
        val repository = MetaRepositoryImpl(mockk<Context>(relaxed = true), api, addons, mockk(relaxed = true))
        assertEquals("tt1234567", (repository.getMeta(RatedKidsCatalog.BASE_URL, "movie", "tt1234567").last() as NetworkResult.Success).data.id)
        assertEquals(listOf("https://v3-cinemeta.strem.io/meta/movie/tt1234567.json"), urls)
        for (id in listOf("trakt:123", "mal:59978", "tt1234567:1:1", "https://private.test"))
            assertTrue(repository.getMeta(RatedKidsCatalog.BASE_URL, "movie", id).last() is NetworkResult.Error)
        assertTrue(repository.getMeta(RatedKidsCatalog.BASE_URL, "channel", "tt1234567").last() is NetworkResult.Error)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }
    @Test fun `native Kids catalog requires exact built in addon identity and type before invoking backend`() = runTest {
        val api = mockk<AddonApi>(); val kids = mockk<RatedKidsCatalog>(); val row = mockk<CatalogRow>()
        coEvery { kids.catalog(ContentType.MOVIE, "family-movies", 0, emptyMap()) } returns row
        val repository = CatalogRepositoryImpl(mockk<Context>(relaxed = true), api, mockk(relaxed = true),
            mockk(relaxed = true), ratedKidsCatalog = kids)
        suspend fun load(id: String, type: String) = repository.getCatalog(RatedKidsCatalog.BASE_URL, id, "Trakt",
            "family-movies", "Family movies", type).last()
        assertTrue(load("untrusted.remote", "movie") is NetworkResult.Error)
        assertTrue(load(RatedKidsCatalog.ADDON_ID, "channel") is NetworkResult.Error)
        assertSame(row, (load(RatedKidsCatalog.ADDON_ID, "movie") as NetworkResult.Success).data)
        coVerify(exactly = 1) { kids.catalog(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getCatalog(any()) }
    }
}
