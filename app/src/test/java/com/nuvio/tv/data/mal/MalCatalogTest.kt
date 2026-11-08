package com.nuvio.tv.data.mal

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MalCatalogTest {
    private val account = MutableStateFlow(MalAccountState(configuration = MalConfiguration("syclop-test-client", "https://example.test/mal")))
    private val auth = mockk<MalAuthRepository> { every { state } returns account }
    private val api = mockk<MalApiClient>()
    private val repository = mockk<MalRepository> {
        every { catalogRevision } returns MutableStateFlow(0L)
    }
    private val catalog = MalCatalog(auth, api, repository)

    @Test fun `only configured native catalogs are advertised and list rows require account`() = runTest {
        assertEquals(listOf("popular", "ranking", "search"), catalog.addon.first()!!.catalogs.map { it.id })
        account.value = account.value.copy(connected = true)
        val connected = catalog.addon.first()!!
        assertEquals(8, connected.catalogs.size)
        assertTrue(connected.catalogs.filter { it.id != "search" }.all { it.showInHome })
        assertTrue(connected.catalogs.last().isSearchOnlyCatalog())
        account.value = account.value.copy(configuration = MalConfiguration())
        assertNull(catalog.addon.first())
    }

    @Test fun `native ranking returns exact MAL identities and pagination`() = runTest {
        coEvery { api.ranking(100, "syclop-test-client") } returns MalPage(listOf(MalAnime(21, "Entry")), true)
        val row = catalog.catalog("ranking", 100, emptyMap())
        assertEquals("mal:21", row.items.single().id)
        assertEquals(MalCatalog.BASE_URL, row.addonBaseUrl)
        assertEquals(101, row.nextSkip)
        assertTrue(row.hasMore)
        coVerify(exactly = 1) { api.ranking(100, "syclop-test-client") }
    }

    @Test fun `popular discovery is public and preserves MAL IDs media type and cursor`() = runTest {
        assertFalse(account.value.connected)
        coEvery { api.popular(100, "syclop-test-client") } returns MalPage(listOf(
            MalAnime(5114, "Series", mediaType = "tv"), MalAnime(40028, "Movie", mediaType = "movie")), true)
        val row = catalog.catalog("popular", 100, emptyMap())
        assertEquals("popular", row.catalogId)
        assertEquals("MAL · Popular anime", row.catalogName)
        assertEquals(listOf("mal:5114", "mal:40028"), row.items.map { it.id })
        assertEquals(listOf("series", "movie"), row.items.map { it.apiType })
        assertTrue(row.items.all { it.sourceAddonBaseUrl == MalCatalog.BASE_URL })
        assertEquals(1, row.currentPage)
        assertEquals(102, row.nextSkip)
        assertEquals(100, row.skipStep)
        assertTrue(row.hasMore && row.supportsSkip)
        coVerify(exactly = 1) { api.popular(100, "syclop-test-client") }
        coVerify(exactly = 0) { api.ranking(any(), any()) }
    }

    @Test fun `late popular response cannot publish into another profile or registration`() = runTest {
        val original = account.value
        for (changed in listOf(original.copy(scope = MalScope(2, 1)),
            original.copy(configuration = MalConfiguration("different-test-client", "https://example.test/mal")))) {
            account.value = original
            coEvery { api.popular(0, "syclop-test-client") } coAnswers {
                account.value = changed
                MalPage(listOf(MalAnime(5114, "Late result")), false)
            }
            try {
                catalog.catalog("popular", 0, emptyMap())
                fail("Late public catalog response crossed the captured account scope")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `short queries never issue a remote search`() = runTest {
        assertTrue(catalog.catalog("search", 0, mapOf("search" to "a")).items.isEmpty())
        coVerify(exactly = 0) { api.publicSearch(any(), any(), any()) }
    }

    @Test fun `real native search delegates bounded query and preserves movie identity`() = runTest {
        coEvery { api.publicSearch("Anime movie", 0, "syclop-test-client") } returns
            MalPage(listOf(MalAnime(15, "Movie", mediaType = "movie")), false)
        val row = catalog.catalog("search", 0, mapOf("search" to " Anime movie "))
        assertEquals("mal:15", row.items.single().id)
        assertEquals("movie", row.items.single().apiType)
        assertFalse(row.hasMore)
    }
}
