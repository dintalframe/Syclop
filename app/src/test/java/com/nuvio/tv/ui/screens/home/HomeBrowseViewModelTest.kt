package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import io.mockk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeBrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val fixtures = mutableListOf<Fixture>()
    @After fun close() { fixtures.forEach { it.vm.viewModelScope.cancel() } }

    @Test fun `Home makes no category requests and selected catalog batches stay bounded`() = runTest {
        val f = fixture(catalogs = (1..7).map { descriptor("movie$it", ContentType.MOVIE) })
        runCurrent(); assertEquals(0, f.calls.size)
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        assertEquals(4, f.calls.size); assertTrue(f.vm.state.value.moreCatalogs)
        f.vm.loadMoreCatalogs(); advanceUntilIdle()
        assertEquals(7, f.calls.size); assertFalse(f.vm.state.value.moreCatalogs)
    }
    @Test fun `later catalogs remain reachable after the first four have no matching items`() = runTest {
        val f = fixture(catalogs = (1..5).map { descriptor("movie$it", ContentType.MOVIE) })
        f.response = { _, id, _ -> row(id, if (id == "movie5") listOf(item("tt5", ContentType.MOVIE)) else emptyList()) }
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        assertTrue(f.vm.state.value.rows.all { it.data?.items?.isEmpty() == true })
        assertTrue(f.vm.state.value.moreCatalogs)
        f.vm.loadMoreCatalogs(); advanceUntilIdle()
        assertEquals(listOf("tt5"), f.vm.state.value.rows.flatMap { it.data?.items.orEmpty() }.map { it.id })
    }
    @Test fun `profile B never requests the replayed private catalog snapshot from profile A`() = runTest {
        val f = fixture()
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        assertEquals(listOf(URL_A), f.calls.map { it.first })
        f.revision.incrementAndGet(); f.profile.value = 2
        runCurrent()
        assertEquals(1, f.calls.size)
        assertTrue(f.vm.state.value.rows.isEmpty())
        f.installed.value = listOf(f.addon.copy(baseUrl = URL_B))
        advanceUntilIdle()
        assertEquals(listOf(URL_A, URL_B), f.calls.map { it.first })
        assertTrue(f.vm.isCurrent())
    }
    @Test fun `changing tabs cancels the actual repository collection and stale results cannot publish`() = runTest {
        val f = fixture(catalogs = listOf(descriptor("movie", ContentType.MOVIE), descriptor("show", ContentType.SERIES)))
        val waiting = CompletableDeferred<Unit>(); var cancelled = false
        every { f.catalogs.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val id = arg<String>(3)
            flow {
                if (id == "movie") try { waiting.await() } finally { cancelled = true }
                emit(NetworkResult.Success(row(id, listOf(item(id, if (id == "movie") ContentType.MOVIE else ContentType.SERIES)))))
            }
        }
        f.vm.select(HomeBrowseSection.MOVIES); runCurrent()
        f.vm.select(HomeBrowseSection.TV); advanceUntilIdle()
        assertTrue(cancelled)
        waiting.complete(Unit); runCurrent()
        assertEquals(HomeBrowseSection.TV, f.vm.state.value.section)
        assertEquals(listOf("show"), f.vm.state.value.rows.flatMap { it.data?.items.orEmpty() }.map { it.id })
    }
    @Test fun `same-profile away and back revision rejects old result and deliberate reselect restarts`() = runTest {
        val f = fixture(); val waiting = CompletableDeferred<Unit>()
        f.beforeResponse = { waiting.await() }
        f.vm.select(HomeBrowseSection.MOVIES); runCurrent()
        f.revision.addAndGet(2L) // Synchronous selection invalidation, even if StateFlow conflates A→B→A.
        waiting.complete(Unit); runCurrent()
        assertFalse(f.vm.isCurrent())
        assertTrue(f.vm.state.value.rows.all { it.data == null })
        f.beforeResponse = {}
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        assertTrue(f.vm.isCurrent())
        assertTrue(f.vm.state.value.rows.any { it.data?.items?.isNotEmpty() == true })
    }
    @Test fun `cancelled initial load cannot restore a permanently loading cached row`() = runTest {
        val f = fixture(); val waiting = CompletableDeferred<Unit>()
        f.beforeResponse = { waiting.await() }
        f.vm.select(HomeBrowseSection.MOVIES); runCurrent()
        f.vm.select(HomeBrowseSection.HOME); runCurrent()
        f.beforeResponse = {}
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        assertTrue(f.vm.state.value.rows.none { it.loading })
        assertEquals(1, f.vm.state.value.rows.single().data!!.items.size)
    }
    @Test fun `source default skip cannot override successive inline page requests`() = runTest {
        val d = descriptor("movie", ContentType.MOVIE).copy(extra = listOf(CatalogExtra("skip", defaultValue = "0")))
        val f = fixture(catalogs = listOf(d))
        val extras = mutableListOf<Map<String, String>>()
        every { f.catalogs.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val skip = arg<Int>(6); extras += arg<Map<String, String>>(8)
            f.calls += Triple(arg(0), arg(3), skip)
            flowOf(NetworkResult.Success(row("movie", listOf(item("tt$skip", ContentType.MOVIE))).copy(hasMore = skip == 0, supportsSkip = true, nextSkip = skip + 100)))
        }
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        f.vm.loadMore(f.vm.state.value.rows.single().key); advanceUntilIdle()
        assertEquals(listOf(0, 100), f.calls.map { it.third })
        assertTrue(extras.all { "skip" !in it })
        assertEquals(listOf("tt0", "tt100"), f.vm.state.value.rows.single().data!!.items.map { it.id })
    }
    @Test fun `unreadable profile preferences fail closed without requesting replayed provider`() = runTest {
        val f = fixture(); coEvery { f.preferences.enabledUrlsForProfile(any()) } throws java.io.IOException("private fixture")
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        assertTrue(f.calls.isEmpty()); assertTrue(f.vm.state.value.failed)
        assertTrue(f.vm.state.value.rows.isEmpty())
    }

    @Test fun `Anime fetches the configured native MAL catalog even when absent from Home choices`() = runTest {
        val f = fixture()
        f.installed.value = listOf(f.addon.copy(id = com.nuvio.tv.data.mal.MalCatalog.ADDON_ID,
            baseUrl = com.nuvio.tv.data.mal.MalCatalog.BASE_URL,
            catalogs = listOf(descriptor("ranking", ContentType.SERIES).copy(showInHome = false, hasExplicitShowInHome = true))))
        f.response = { _, id, _ -> row(id, listOf(item("mal:52991", ContentType.SERIES)))
            .copy(addonId = com.nuvio.tv.data.mal.MalCatalog.ADDON_ID, addonBaseUrl = com.nuvio.tv.data.mal.MalCatalog.BASE_URL) }
        f.vm.select(HomeBrowseSection.ANIME); advanceUntilIdle()
        assertEquals(listOf(com.nuvio.tv.data.mal.MalCatalog.BASE_URL), f.calls.map { it.first })
        assertEquals(listOf("mal:52991"), f.vm.state.value.rows.single().data!!.items.map { it.id })
    }
    @Test fun `category focus and return requests are isolated by section and profile revision`() = runTest {
        val f = fixture()
        val movie = HomeScreenFocusState(focusedRowKey = "movie-row", focusedItemIndex = 3, hasSavedFocus = true)
        val anime = HomeScreenFocusState(focusedRowKey = "anime-row", focusedItemIndex = 5, hasSavedFocus = true)
        f.vm.saveFocusState(HomeBrowseSection.MOVIES, 1, movie)
        f.vm.saveFocusState(HomeBrowseSection.ANIME, 1, anime)
        f.vm.returnToCategory(HomeBrowseSection.MOVIES, 1)
        assertEquals(movie, f.vm.focusState(HomeBrowseSection.MOVIES, 1))
        assertEquals(anime, f.vm.focusState(HomeBrowseSection.ANIME, 1))
        assertFalse(f.vm.consumeCategoryReturn(HomeBrowseSection.ANIME, 1))
        assertTrue(f.vm.consumeCategoryReturn(HomeBrowseSection.MOVIES, 1))
        assertFalse(f.vm.consumeCategoryReturn(HomeBrowseSection.MOVIES, 1))
        f.revision.incrementAndGet()
        assertEquals(HomeScreenFocusState(), f.vm.focusState(HomeBrowseSection.MOVIES, 2))
        f.vm.saveFocusState(HomeBrowseSection.MOVIES, 1, movie)
        f.vm.returnToCategory(HomeBrowseSection.MOVIES, 1)
        assertFalse(f.vm.consumeCategoryReturn(HomeBrowseSection.MOVIES, 2))
    }
    @Test fun `retry from a category tab reloads failed rows without reloading successful rows`() = runTest {
        val f = fixture(catalogs = listOf(descriptor("good", ContentType.MOVIE), descriptor("bad", ContentType.MOVIE)))
        var fail = true
        every { f.catalogs.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val id = arg<String>(3)
            f.calls += Triple(arg(0), id, arg(6))
            if (id == "bad" && fail) flowOf(NetworkResult.Error("unavailable"))
            else flowOf(NetworkResult.Success(row(id, listOf(item("tt$id", ContentType.MOVIE)))))
        }
        f.vm.select(HomeBrowseSection.MOVIES); advanceUntilIdle()
        assertEquals(1, f.vm.state.value.rows.count { it.failed })
        fail = false; f.vm.retryFailedCatalogs(); advanceUntilIdle()
        assertEquals(listOf("good", "bad", "bad"), f.calls.map { it.second })
        assertTrue(f.vm.state.value.rows.none { it.failed })
    }
    @Test fun `Kids certified native catalogs stay in the first bounded batch ahead of generic catalogs`() = runTest {
        val f = fixture(catalogs = (1..30).map { descriptor("generic$it", ContentType.MOVIE) })
        val native = f.addon.copy(id = com.nuvio.tv.data.catalog.RatedKidsCatalog.ADDON_ID,
            baseUrl = com.nuvio.tv.data.catalog.RatedKidsCatalog.BASE_URL,
            catalogs = listOf(descriptor("family-movies", ContentType.MOVIE)))
        f.installed.value = listOf(f.addon, native)
        f.vm.select(HomeBrowseSection.KIDS); advanceUntilIdle()
        assertEquals(4, f.calls.size)
        assertEquals(com.nuvio.tv.data.catalog.RatedKidsCatalog.BASE_URL, f.calls.first().first)
        repeat(10) { f.vm.loadMoreCatalogs(); advanceUntilIdle() }
        assertEquals(24, f.calls.size)
        assertEquals(1, f.calls.count { it.first == com.nuvio.tv.data.catalog.RatedKidsCatalog.BASE_URL })
    }
    @Test fun `request order revision changes for deliberate catalog reorder not late completion`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val f = fixture(catalogs = listOf(descriptor("slow", ContentType.MOVIE), descriptor("ready", ContentType.MOVIE)))
        every { f.catalogs.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val id = arg<String>(3)
            flow {
                if (id == "slow") slow.await()
                emit(NetworkResult.Success(row(id, listOf(item(id, ContentType.MOVIE)))))
            }
        }
        f.vm.select(HomeBrowseSection.MOVIES); runCurrent()
        val revision = f.vm.state.value.requestRevision
        assertTrue(revision > 0)
        assertEquals(listOf("ready"), f.vm.state.value.rows.mapNotNull { it.data?.catalogId })
        slow.complete(Unit); advanceUntilIdle()
        assertEquals(revision, f.vm.state.value.requestRevision)
        assertEquals(listOf("slow", "ready"), f.vm.state.value.rows.mapNotNull { it.data?.catalogId })

        f.installed.value = listOf(f.addon.copy(catalogs = f.addon.catalogs.reversed()))
        advanceUntilIdle()
        assertTrue(f.vm.state.value.requestRevision > revision)
        assertEquals(listOf("ready", "slow"), f.vm.state.value.rows.mapNotNull { it.data?.catalogId })
    }

    private fun fixture(catalogs: List<CatalogDescriptor> = listOf(descriptor("movie", ContentType.MOVIE))) = Fixture(catalogs).also(fixtures::add)
    private class Fixture(descriptors: List<CatalogDescriptor>) {
        val profile = MutableStateFlow(1); val revision = AtomicLong(1)
        val addon = Addon("fixture", "Fixture", version = "1", description = null, logo = null, baseUrl = URL_A,
            catalogs = descriptors, types = listOf(ContentType.MOVIE, ContentType.SERIES), resources = emptyList())
        val installed = MutableStateFlow(listOf(addon))
        val calls = mutableListOf<Triple<String, String, Int>>()
        var beforeResponse: suspend () -> Unit = {}
        var response: (String, String, Int) -> CatalogRow = { _, id, _ -> row(id, listOf(item(id, ContentType.MOVIE))) }
        val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns profile
            every { activeProfileSelectionRevision } answers { revision.get() }
            every { activeProfile } returns null
        }
        val preferences = mockk<AddonPreferences> {
            coEvery { enabledUrlsForProfile(any()) } answers { listOf(if (firstArg<Int>() == 1) URL_A else URL_B) }
        }
        val addons = mockk<AddonRepository> { every { getInstalledAddons() } returns installed }
        val catalogs = mockk<CatalogRepository> {
            every { getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                val url = arg<String>(0); val id = arg<String>(3); val skip = arg<Int>(6)
                flow { calls += Triple(url, id, skip); beforeResponse(); emit(NetworkResult.Success(response(url, id, skip))) }
            }
        }
        val vm = HomeBrowseViewModel(addons, catalogs, profiles, preferences)
    }
    companion object {
        private const val URL_A = "https://catalog.test/ProfileA?config=Alpha"
        private const val URL_B = "https://catalog.test/ProfileB?config=Beta"
        private fun descriptor(id: String, type: ContentType) = CatalogDescriptor(type, id = id, name = id, extraSupported = listOf("skip"))
        private fun item(id: String, type: ContentType) = MetaPreview(id, type, name = "Fixture", poster = null, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList())
        private fun row(id: String, items: List<MetaPreview>) = CatalogRow("fixture", "Fixture", URL_A, id, id, ContentType.MOVIE,
            items = items, hasMore = false, supportsSkip = true, nextSkip = 100)
    }
}
