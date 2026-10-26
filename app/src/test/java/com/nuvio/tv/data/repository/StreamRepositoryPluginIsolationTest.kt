package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.debrid.LocalDebridService
import com.nuvio.tv.core.debrid.LocalDebridCachedItem
import com.nuvio.tv.core.express.ExpressAddonAdapter
import com.nuvio.tv.core.express.ExpressSearchReport
import com.nuvio.tv.core.express.ExpressSearchUpdate
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import retrofit2.Response

class StreamRepositoryPluginIsolationTest {
    @Test
    fun `stale availability cancels the observed search while a provider remains pending`() = runBlocking {
        val express = mockk<ExpressAddonAdapter>()
        every { express.isExpress(any()) } returns true
        val providerRelease = CompletableDeferred<Unit>()
        val providerFinished = CompletableDeferred<Unit>()
        val initial = syntheticStream("First", 'a')
        every { express.searchProgressively(any(), any()) } returns flow {
            try {
                emit(ExpressSearchUpdate(listOf(initial), ExpressSearchReport(2, 1, 0, 0, 0, 1, false, complete = false)))
                providerRelease.await()
                emit(ExpressSearchUpdate(listOf(initial), ExpressSearchReport(2, 2, 0, 0, 0, 1, false, complete = true)))
            } finally { providerFinished.complete(Unit) }
        }
        val h = newHarness(emptyList(), express)
        val annotationEntered = CompletableDeferred<Unit>()
        val annotationRelease = CompletableDeferred<Unit>()
        coEvery { h.availability.annotateCachedAvailability(any(), any()) } coAnswers {
            annotationEntered.complete(Unit)
            annotationRelease.await()
            throw CancellationException("Synthetic stale captured service")
        }
        val observed = async { h.repository.getStreamsFromAllAddons("movie", "tt1341338").toList() }
        try {
            withTimeout(2_000) { annotationEntered.await() }
            h.credentialRevision.incrementAndGet()
            annotationRelease.complete(Unit)
            try { withTimeout(2_000) { observed.await() }; fail("Expected whole observed search cancellation") }
            catch (error: CancellationException) {
                assertTrue(error.message.orEmpty().contains("connected service changed"))
            }
            assertTrue(providerRelease.isActive)
        } finally {
            annotationRelease.complete(Unit)
            providerRelease.complete(Unit)
            withTimeout(2_000) { providerFinished.await() }
        }
    }

    @Test
    fun `superseded enrichment cancellation still allows the newer provider snapshot to finish`() = runBlocking {
        val express = mockk<ExpressAddonAdapter>()
        every { express.isExpress(any()) } returns true
        val nextSnapshot = CompletableDeferred<Unit>()
        val first = syntheticStream("First", 'a')
        val second = syntheticStream("Second", 'b')
        every { express.searchProgressively(any(), any()) } returns flow {
            emit(ExpressSearchUpdate(listOf(first), ExpressSearchReport(2, 1, 0, 0, 0, 1, false, complete = false)))
            nextSnapshot.await()
            emit(ExpressSearchUpdate(listOf(first, second), ExpressSearchReport(2, 2, 0, 0, 0, 2, false, complete = true)))
        }
        val h = newHarness(emptyList(), express)
        val entered = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        coEvery { h.availability.annotateCachedAvailability(any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { neverComplete.await() } finally { firstCancelled.complete(Unit) }
            }
            firstArg<List<AddonStreams>>()
        }
        val observed = async { h.repository.getStreamsFromAllAddons("movie", "tt1341338").toList() }
        withTimeout(2_000) { entered.await() }
        nextSnapshot.complete(Unit)
        val results = withTimeout(2_000) { observed.await() }
        assertTrue(firstCancelled.isCompleted)
        val final = results.last() as NetworkResult.Success
        assertEquals(2, final.data.single().streams.size)
        assertEquals(true, final.data.single().searchDiagnostics?.complete)
    }

    @Test
    fun `negative availability cannot erase raw candidates from a reused source search`() = runBlocking {
        val h = nativeCacheHarness()
        coEvery { h.backend.checkCached(any(), any()) } returns emptyMap()
        val first = h.repository.getStreamsFromAllAddons("movie", "tt1341338").toList().last() as NetworkResult.Success
        assertTrue(first.data.single().streams.isEmpty())
        h.now.addAndGet(10_000)
        coEvery { h.backend.checkCached(any(), any()) } returns mapOf(HASH to LocalDebridCachedItem("movie.mkv", 123L))
        val second = h.repository.getStreamsFromAllAddons("movie", "tt1341338").toList().last() as NetworkResult.Success
        assertEquals(1, second.data.single().streams.size)
        assertEquals(StreamDebridCacheState.CACHED, second.data.single().streams.single().debridCacheStatus?.state)
        coVerify(exactly = 1) { h.api.getStreams(any()) }
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
    }

    @Test
    fun `cached peek refreshes expired native availability from retained raw candidates`() = runBlocking {
        val h = nativeCacheHarness()
        coEvery { h.backend.checkCached(any(), any()) } returns emptyMap()
        h.repository.getStreamsFromAllAddons("movie", "tt1341338").toList()
        h.now.addAndGet(10_000)
        coEvery { h.backend.checkCached(any(), any()) } returns mapOf(HASH to LocalDebridCachedItem("movie.mkv", 123L))
        val cached = h.repository.getCachedStreamsFromAllAddons("movie", "tt1341338")
        assertEquals(1, cached!!.single().streams.size)
        assertEquals(StreamDebridCacheState.CACHED, cached.single().streams.single().debridCacheStatus?.state)
        coVerify(exactly = 1) { h.api.getStreams(any()) }
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
        h.credentialRevision.incrementAndGet()
        assertNull(h.repository.getCachedStreamsFromAllAddons("movie", "tt1341338"))
        coVerify(exactly = 2) { h.backend.checkCached(any(), any()) }
    }

    @Test
    fun `cached peek never returns an observation after its captured account epoch changes`() = runBlocking {
        val h = nativeCacheHarness()
        coEvery { h.backend.checkCached(any(), any()) } returns emptyMap()
        h.repository.getStreamsFromAllAddons("movie", "tt1341338").toList()
        h.now.addAndGet(10_000)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { h.backend.checkCached(any(), any()) } coAnswers {
            entered.complete(Unit); release.await(); mapOf(HASH to LocalDebridCachedItem("old-account.mkv", 123L))
        }
        val cached = async { h.repository.getCachedStreamsFromAllAddons("movie", "tt1341338") }
        withTimeout(2_000) { entered.await() }
        h.credentialRevision.incrementAndGet()
        release.complete(Unit)
        try { cached.await(); fail("Expected stale cached observation cancellation") }
        catch (_: CancellationException) { }
    }

    @Test
    fun `admitted addon rows are published before slow availability and next addon stays progressive`() = runBlocking {
        val harness = newHarness(emptyList())
        val second = compatibleAddon().copy(id = "second", name = "Second Addon", displayName = "Second Addon", baseUrl = "https://second.example")
        every { harness.addonRepository.getInstalledAddons() } returns flowOf(listOf(compatibleAddon(), second))
        val cacheCheck = CompletableDeferred<Unit>()
        coEvery { harness.availability.annotateCachedAvailability(any(), any()) } coAnswers {
            cacheCheck.await()
            firstArg<List<AddonStreams>>()
        }
        try {
            val result = withTimeout(2_000) {
                harness.repository.getStreamsFromAllAddons("movie", "tt1341338")
                    .first { it is NetworkResult.Success && it.data.size == 2 }
            } as NetworkResult.Success
            assertEquals(setOf("Fast Addon", "Second Addon"), result.data.map { it.addonName }.toSet())
            assertTrue(cacheCheck.isActive)
        } finally { cacheCheck.complete(Unit) }
    }

    @Test
    fun `Express admission is delivered before its provider search finishes and carries request diagnostics`() = runBlocking {
        val express = mockk<ExpressAddonAdapter>()
        every { express.isExpress(any()) } returns true
        val finishSearch = CompletableDeferred<Unit>()
        val stream = Stream("First source", "Show", description = null, url = null, ytId = null, infoHash = "a".repeat(40),
            fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Provider", addonLogo = null)
        every { express.searchProgressively(any(), any()) } returns flow {
            emit(ExpressSearchUpdate(listOf(stream), ExpressSearchReport(2, 1, 0, 0, 0, 1, false, complete = false)))
            finishSearch.await()
            emit(ExpressSearchUpdate(listOf(stream), ExpressSearchReport(2, 2, 0, 0, 0, 1, false, complete = true)))
        }
        val harness = newHarness(emptyList(), express)
        try {
            val result = withTimeout(2_000) {
                harness.repository.getStreamsFromAllAddons("movie", "tt1341338")
                    .first { it is NetworkResult.Success && it.data.any { group -> group.streams.isNotEmpty() } }
            } as NetworkResult.Success
            assertEquals(1, result.data.single().streams.size)
            assertEquals(false, result.data.single().searchDiagnostics?.complete)
            assertTrue(finishSearch.isActive)
        } finally { finishSearch.complete(Unit) }
    }

    @Test
    fun `addon results arrive while plugin TMDB lookup is pending`() = runBlocking {
        val harness = newHarness(listOf(compatibleScraper()))
        val tmdbResult = CompletableDeferred<String?>()
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } coAnswers {
            tmdbResult.await()
        }

        val result = withTimeout(1_000) {
            harness.repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1341338",
                season = null,
                episode = null
            ).first { it is NetworkResult.Success }
        }

        val groups = (result as NetworkResult.Success).data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(1, groups.single().streams.size)
        assertTrue(tmdbResult.isActive)
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
        tmdbResult.complete(null)
        Unit
    }

    @Test
    fun `TMDB lookup is skipped when no compatible plugin is enabled`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        assertTrue(results.last() is NetworkResult.Success)
        coVerify(exactly = 0) { harness.tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
    }

    /**
     * The stream path must read addon display info from the installed [Addon] it was given, not
     * refetch the manifest. fetchAddon is deliberately left stubbed on the harness so this asserts
     * the call is available and still not made, rather than merely un-stubbed.
     */
    @Test
    fun `stream lookup issues no manifest request`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        assertEquals(listOf("Fast Addon"), success.data.map { it.addonName })
        // Pins the URL too: the regression is not merely "one call happened", it is that the
        // stream request is the only request, built from the addon's own baseUrl.
        coVerify(exactly = 1) { harness.api.getStreams("https://addon.example/stream/movie/tt1341338.json") }
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    @Test
    fun `streams carry the installed addon name and logo`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        val groups = success.data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(listOf(ADDON_LOGO), groups.map { it.addonLogo })
        assertTrue(groups.single().streams.all { it.addonName == "Fast Addon" })
        assertTrue(groups.single().streams.all { it.addonLogo == ADDON_LOGO })
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    @Test
    fun `repository grouping preserves each plugin provider name`() = runTest {
        assertPluginProviderNames(groupByRepository = true)
    }

    @Test
    fun `ungrouped plugins retain their existing group names`() = runTest {
        assertPluginProviderNames(groupByRepository = false)
    }

    private suspend fun assertPluginProviderNames(groupByRepository: Boolean) {
        val scrapers = listOf(
            compatibleScraper().copy(id = "first", name = "First provider"),
            compatibleScraper().copy(id = "second", name = "Second provider")
        )
        val harness = newHarness(scrapers)
        every { harness.pluginManager.groupStreamsByRepository } returns flowOf(groupByRepository)
        every { harness.pluginManager.repositories } returns flowOf(
            listOf(PluginRepository(id = "repo", name = "Repository", url = "https://repo.example"))
        )
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } returns "123"
        every { harness.pluginManager.executeScrapersStreaming("123", "movie", null, null) } returns
            flowOf(*scrapers.map { scraper ->
                scraper to listOf(LocalScraperResult(
                    title = "Shared title",
                    url = "https://stream.example/${scraper.id}.m3u8"
                ))
            }.toTypedArray())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie", videoId = "tt1341338", season = null, episode = null
        ).toList()
        val groups = (results.last() as NetworkResult.Success).data
        val pluginGroups = groups.filter { it.addonName != "Fast Addon" }
        assertEquals(
            if (groupByRepository) listOf("Repository") else scrapers.map { it.name },
            pluginGroups.map { it.addonName }
        )
        val streams = pluginGroups.flatMap { it.streams }
        assertEquals(scrapers.map { it.name }, streams.map { it.pluginSourceName })
        assertEquals(listOf("Shared title", "Shared title"), streams.map { it.title })
        assertTrue(streams.all { stream ->
            stream.addonName == if (groupByRepository) "Repository" else stream.pluginSourceName
        })
        assertTrue(groups.single { it.addonName == "Fast Addon" }.streams.all { it.pluginSourceName == null })
    }

    private fun newHarness(enabledScrapers: List<ScraperInfo>, express: ExpressAddonAdapter? = null,
        nativeAvailability: Boolean = false): Harness {
        val addon = compatibleAddon()
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any()) } returns Response.success(
            StreamResponseDto(
                streams = listOf(
                    StreamDto(
                        name = "Fast Stream",
                        url = "https://stream.example/video.m3u8"
                    )
                )
            )
        )

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonRepository.fetchAddon(addon.baseUrl) } returns NetworkResult.Success(addon)

        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(enabledScrapers)
        every { pluginManager.pluginsEnabled } returns flowOf(enabledScrapers.isNotEmpty())
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        val profile = MutableStateFlow(1)
        every { profileManager.activeProfileId } returns profile

        val tmdbService = mockk<TmdbService>(relaxed = true)
        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        val settings = MutableStateFlow(if (nativeAvailability) DebridSettings(enabled = true, torboxApiKey = "synthetic-tb") else DebridSettings())
        val credentialRevision = AtomicLong(0L)
        val now = AtomicLong(1_000L)
        every { debridSettingsDataStore.settings } returns settings
        every { debridSettingsDataStore.activeProfileId } returns profile
        every { debridSettingsDataStore.activeProfileSelectionRevision } returns 0L
        every { debridSettingsDataStore.activeCredentialRevision } answers { credentialRevision.get() }
        coEvery { debridSettingsDataStore.credentialForProfile(any(), any()) } coAnswers { settings.value.apiKeyFor(secondArg()) }
        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>()
        }
        val backend = mockk<LocalDebridService>()
        val availability = if (nativeAvailability) LocalDebridAvailabilityService(debridSettingsDataStore, backend, now::get)
        else mockk<LocalDebridAvailabilityService>().also { mock ->
            coEvery { mock.markChecking(any(), any()) } coAnswers { firstArg<List<AddonStreams>>() }
            coEvery { mock.annotateCachedAvailability(any(), any()) } coAnswers { firstArg<List<AddonStreams>>() }
        }

        return Harness(
            repository = StreamRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                api = api,
                addonRepository = addonRepository,
                pluginManager = pluginManager,
                profileManager = profileManager,
                debridSettingsDataStore = debridSettingsDataStore,
                tmdbService = tmdbService,
                debridStreamPresentation = presentation,
                localDebridAvailabilityService = availability,
                serverStreams = mockk {
                    every { isNativeRequest(any()) } returns false
                    every { revision } returns 0
                    every { sources(any(), any(), any(), any(), any()) } returns emptyList()
                },
                expressAdapter = express
            ),
            api = api,
            tmdbService = tmdbService,
            addonRepository = addonRepository,
            pluginManager = pluginManager,
            availability = availability,
            presentation = presentation, backend = backend, now = now, credentialRevision = credentialRevision
        )
    }

    private fun nativeCacheHarness(): Harness {
        val h = newHarness(emptyList(), nativeAvailability = true)
        coEvery { h.api.getStreams(any()) } returns Response.success(StreamResponseDto(streams = listOf(
            StreamDto(name = "Synthetic candidate", infoHash = HASH))))
        // The presentation boundary deliberately removes confirmed negatives, exactly where
        // the regression lost raw candidates. Actual account/TTL work uses the real service.
        every { h.presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>().map { group -> group.copy(streams = group.streams.filterNot {
                it.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED
            }) }
        }
        return h
    }

    private fun syntheticStream(name: String, hash: Char) = Stream(name, "Synthetic title", null, null, null,
        hash.toString().repeat(40), null, null, null, "Synthetic provider", null)

    private fun compatibleAddon(): Addon = Addon(
        id = "fast-addon",
        name = "Fast Addon",
        version = "1.0.0",
        description = null,
        logo = ADDON_LOGO,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(
                name = "stream",
                types = listOf("movie"),
                idPrefixes = listOf("tt")
            )
        )
    )

    private fun compatibleScraper(): ScraperInfo = ScraperInfo(
        id = "plugin",
        name = "Plugin",
        description = "",
        version = "1.0.0",
        filename = "plugin.js",
        supportedTypes = listOf("movie"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo",
        formats = null,
        type = RepositoryType.NUVIO_JS
    )

    private companion object {
        const val ADDON_LOGO = "https://addon.example/logo.png"
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
    }

    private data class Harness(
        val repository: StreamRepositoryImpl,
        val api: AddonApi,
        val tmdbService: TmdbService,
        val addonRepository: AddonRepository,
        val pluginManager: PluginManager,
        val availability: LocalDebridAvailabilityService,
        val presentation: DebridStreamPresentation,
        val backend: LocalDebridService,
        val now: AtomicLong,
        val credentialRevision: AtomicLong
    )
}
