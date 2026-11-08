package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.initialization.InitializationFailure
import com.nuvio.tv.core.initialization.InitializationProvider
import com.nuvio.tv.core.initialization.InitializationReadException
import com.nuvio.tv.core.initialization.InitializationReadScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesMediaDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktProgressInitializationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `refresh emitted by the first blocked batch is retained and cancels it`() = runTest {
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var batches = 0
        var subscribedAtFirstBatch = false
        var refreshAccepted = false
        var firstCancelled = false
        val collector = backgroundScope.launch {
            traktRefreshEvents(emptyFlow(), signals).collectLatest {
                if (++batches == 1) {
                    subscribedAtFirstBatch = signals.subscriptionCount.value > 0
                    refreshAccepted = signals.tryEmit(Unit)
                    try { awaitCancellation() } finally { firstCancelled = true }
                }
            }
        }
        try {
            runCurrent()
            assertEquals(2, batches)
            assertTrue(subscribedAtFirstBatch)
            assertTrue(refreshAccepted)
            assertTrue(firstCancelled)
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun `owned import includes completed movies and episode history separately from unfinished progress`() = runBlocking {
        val fixture = Fixture()
        fixture.recentHistory = listOf(
            TraktHistoryItemDto(watchedAt = "2026-01-02T00:00:00Z",
                movie = TraktMovieDto(title = "Completed movie", ids = TraktIdsDto(imdb = "tt100"))),
            TraktHistoryItemDto(watchedAt = "2026-01-03T00:00:00Z",
                show = TraktShowDto(title = "Completed show", ids = TraktIdsDto(imdb = "tt200")),
                episode = TraktEpisodeDto(title = "Episode", season = 1, number = 2)))
        val service = fixture.service()
        val result = withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) }
        assertEquals(3, result.count) // One unfinished progress item and two real completed observations.
        val history = service.observeWatchedHistory(flowOf(Unit)).first()
        assertEquals(listOf("tt200:1:2", "tt100"), history.map { it.videoId })
        assertEquals(listOf(MOVIE_ID), service.observeAllProgress().first().map { it.contentId })
    }

    @Test
    fun `failed history read preserves the previous owned history rather than publishing empty`() = runBlocking {
        val fixture = Fixture()
        fixture.recentHistory = listOf(TraktHistoryItemDto(watchedAt = "2026-01-02T00:00:00Z",
            movie = TraktMovieDto(title = "Completed movie", ids = TraktIdsDto(imdb = "tt100"))))
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) }
        service.refreshRemoteSnapshot() // An unchanged poll is not a reusable full-import receipt.
        fixture.failRecentHistory.set(true)
        val failure = runCatching { withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) } }.exceptionOrNull()
        assertTrue(failure is InitializationReadException)
        assertEquals(InitializationFailure.NETWORK, (failure as InitializationReadException).reason)
        assertEquals("tt100", service.observeWatchedHistory(flowOf(Unit)).first().single().contentId)
    }

    @Test
    fun `account ownership loss hides completed history before replacement import succeeds`() = runBlocking {
        val fixture = Fixture()
        fixture.recentHistory = listOf(TraktHistoryItemDto(watchedAt = "2026-01-02T00:00:00Z",
            movie = TraktMovieDto(title = "Completed movie", ids = TraktIdsDto(imdb = "tt100"))))
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) }
        assertEquals(1, service.observeWatchedHistory(flowOf(Unit)).first().size)
        fixture.current.set(false)
        assertTrue(service.observeWatchedHistory(flowOf(Unit)).first().isEmpty())
    }

    @Test
    fun `bounded history publishes while mandatory progress is blocked without completing import`() = runBlocking {
        val fixture = Fixture(blockProgress = true)
        fixture.recentHistory = completedMovie()
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { fixture.progressEntered.await() }
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            service.awaitInitializationImport(fixture.owner)
        }
        try {
            val history = withTimeout(TIMEOUT_MS) {
                service.observeWatchedHistory(flowOf(Unit)).first { it.isNotEmpty() }
            }
            assertEquals("tt100", history.single().contentId)
            assertEquals(false, service.observeRemoteProgressLoaded().first())
            assertEquals(false, importing.isCompleted)
            assertEquals(1, fixture.recentHistoryCalls.get())
            fixture.releaseProgress.complete(Unit)
            val result = withTimeout(TIMEOUT_MS) { importing.await() }
            assertTrue(result.complete)
            assertEquals(2, result.count)
            assertEquals(1, fixture.recentHistoryCalls.get())
        } finally {
            fixture.releaseProgress.complete(Unit)
            importing.cancelAndJoin()
        }
    }

    @Test
    fun `successful bounded history survives later unrelated mandatory progress failure`() = runBlocking {
        val fixture = Fixture(blockProgress = true)
        fixture.recentHistory = completedMovie()
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { fixture.progressEntered.await() }
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { service.awaitInitializationImport(fixture.owner) }
        }
        try {
            withTimeout(TIMEOUT_MS) { service.observeWatchedHistory(flowOf(Unit)).first { it.isNotEmpty() } }
            fixture.failProgress.set(true)
            fixture.releaseProgress.complete(Unit)
            assertTrue(withTimeout(TIMEOUT_MS) { importing.await() }.isFailure)
            assertEquals(false, service.observeRemoteProgressLoaded().first())
            assertEquals("tt100", service.observeWatchedHistory(flowOf(Unit)).first().single().contentId)
            assertEquals(1, fixture.recentHistoryCalls.get())
        } finally {
            fixture.releaseProgress.complete(Unit)
            importing.cancelAndJoin()
        }
    }

    @Test
    fun `late bounded history cannot publish after ownership changes while its response is blocked`() = runBlocking {
        val fixture = Fixture(blockRecentHistory = true)
        fixture.recentHistory = completedMovie()
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { fixture.recentHistoryEntered.await() }
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { service.awaitInitializationImport(fixture.owner) }
        }
        try {
            assertTrue(service.observeWatchedHistory(flowOf(Unit)).first().isEmpty())
            fixture.current.set(false)
            fixture.releaseRecentHistory.complete(Unit)
            val failure = withTimeout(TIMEOUT_MS) { importing.await() }.exceptionOrNull()
            assertTrue(failure is InitializationReadException)
            assertEquals(InitializationFailure.ACCOUNT_CHANGED, (failure as InitializationReadException).reason)
            assertTrue(service.observeWatchedHistory(flowOf(Unit)).first().isEmpty())
            assertEquals(false, service.observeRemoteProgressLoaded().first())
        } finally {
            fixture.releaseRecentHistory.complete(Unit)
            importing.cancelAndJoin()
        }
    }

    @Test
    fun `refresh cancellation before history response cannot publish the cancelled batch`() = runBlocking {
        val fixture = Fixture(blockRecentHistory = true)
        fixture.recentHistory = completedMovie()
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { fixture.recentHistoryEntered.await() }
        try {
            fixture.recentHistory = emptyList()
            service.requestAutomaticRefresh()
            withTimeout(TIMEOUT_MS) {
                fixture.recentHistoryCancelled.await()
                while (fixture.recentHistoryCalls.get() < 2) delay(5L)
            }
            assertTrue(service.observeWatchedHistory(flowOf(Unit)).first().isEmpty())
            assertEquals(false, service.observeRemoteProgressLoaded().first())
            fixture.releaseRecentHistory.complete(Unit)
            withTimeout(TIMEOUT_MS) { service.observeRemoteProgressLoaded().first { it } }
            assertTrue(service.observeWatchedHistory(flowOf(Unit)).first().isEmpty())
            assertEquals(2, fixture.recentHistoryCalls.get())
        } finally { fixture.releaseRecentHistory.complete(Unit) }
    }
    @Test
    fun `initialization joins an actual background import instead of starting a second batch`() = runBlocking {
        val fixture = Fixture(blockFirstActivity = true)
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { fixture.activityEntered.await() }
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            service.awaitInitializationImport(fixture.owner)
        }
        fixture.releaseActivity.complete(Unit)
        val result = withTimeout(TIMEOUT_MS) { importing.await() }

        assertTrue(result.complete)
        assertTrue(result.truncated) // Existing bounded Continue Watching window is retained.
        assertEquals(1, result.count ?: -1)
        assertEquals(1, fixture.activityCalls.get())
        assertEquals(2, fixture.playbackCalls.get())
        assertEquals(1, fixture.watchedMovieCalls.get())
        assertEquals(1, fixture.watchedShowCalls.get())
        assertEquals(result, service.awaitInitializationImport(fixture.owner))
        assertEquals(1, fixture.activityCalls.get()) // A just-finished actual import is reused too.
    }

    @Test
    fun `a failed required watched read is not accepted as an import and preserves cached progress`() = runBlocking {
        val fixture = Fixture()
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) {
            service.observeAllProgress().first { it.any { row -> row.contentId == MOVIE_ID } }
        }
        withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) }
        service.refreshRemoteSnapshot() // Explicit polling no-op is not an import receipt.
        fixture.failWatchedMovies.set(true)

        val failure = try {
            withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) }
            error("A failed required read must not complete initialization")
        } catch (failure: InitializationReadException) { failure }
        assertEquals(InitializationFailure.NETWORK, failure.reason)
        val current = service.observeAllProgress().first()
        assertTrue(current.any { it.contentId == MOVIE_ID })
    }

    @Test
    fun `an account change during an in-flight page rejects its progress commit`() = runBlocking {
        val fixture = Fixture(blockFirstActivity = true)
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { fixture.activityEntered.await() }
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { service.awaitInitializationImport(fixture.owner) }
        }
        fixture.current.set(false)
        fixture.releaseActivity.complete(Unit)
        val failure = withTimeout(TIMEOUT_MS) { importing.await() }.exceptionOrNull()

        assertTrue(failure is InitializationReadException)
        assertEquals(InitializationFailure.ACCOUNT_CHANGED, (failure as InitializationReadException).reason)
        assertEquals(0, fixture.playbackCalls.get())
        assertEquals(0, fixture.watchedMovieCalls.get())
        assertEquals(0, fixture.watchedShowCalls.get())
    }

    @Test
    fun `a failed import for a new account hides the former account projection`() = runBlocking {
        val fixture = Fixture()
        val service = fixture.service()
        withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) }
        fixture.current.set(false)
        fixture.failWatchedMovies.set(true)
        val newOwner = InitializationReadScope(InitializationProvider.TRAKT, "replacement-grant") { true }

        val failure = runCatching {
            withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(newOwner) }
        }.exceptionOrNull()
        assertTrue(failure is InitializationReadException)
        assertEquals(InitializationFailure.NETWORK, (failure as InitializationReadException).reason)
        assertEquals(false, service.observeRemoteProgressLoaded().first())
        withTimeout(TIMEOUT_MS) { assertTrue(service.observeAllProgress().first().isEmpty()) }
    }

    @Test
    fun `successful HTTP response without required playback payload cannot complete an import`() = runBlocking {
        val fixture = Fixture()
        fixture.missingMoviePayload.set(true)
        val service = fixture.service()
        val failure = runCatching {
            withTimeout(TIMEOUT_MS) { service.awaitInitializationImport(fixture.owner) }
        }.exceptionOrNull()
        assertTrue(failure is InitializationReadException)
        assertEquals(InitializationFailure.INCOMPLETE, (failure as InitializationReadException).reason)
        assertEquals(false, service.observeRemoteProgressLoaded().first())
    }

    private class Fixture(private val blockFirstActivity: Boolean = false,
        private val blockProgress: Boolean = false, private val blockRecentHistory: Boolean = false) {
        val api = mockk<TraktApi>()
        val current = AtomicBoolean(true)
        val owner = InitializationReadScope(InitializationProvider.TRAKT, "test-profile-grant") { current.get() }
        val activityEntered = CompletableDeferred<Unit>()
        val releaseActivity = CompletableDeferred<Unit>()
        val activityCalls = AtomicInteger()
        val playbackCalls = AtomicInteger()
        val watchedMovieCalls = AtomicInteger()
        val watchedShowCalls = AtomicInteger()
        val failWatchedMovies = AtomicBoolean()
        val missingMoviePayload = AtomicBoolean()
        var recentHistory = emptyList<TraktHistoryItemDto>()
        val failRecentHistory = AtomicBoolean()
        val recentHistoryCalls = AtomicInteger()
        val recentHistoryEntered = CompletableDeferred<Unit>()
        val recentHistoryCancelled = CompletableDeferred<Unit>()
        val releaseRecentHistory = CompletableDeferred<Unit>()
        val progressEntered = CompletableDeferred<Unit>()
        val releaseProgress = CompletableDeferred<Unit>()
        val failProgress = AtomicBoolean()

        fun service(): TraktProgressService {
            coEvery { api.getRecentHistory(any(), any(), any(), any()) } coAnswers {
                recentHistoryCalls.incrementAndGet()
                if (blockRecentHistory) {
                    recentHistoryEntered.complete(Unit)
                    try { releaseRecentHistory.await() } catch (cancelled: CancellationException) {
                        recentHistoryCancelled.complete(Unit)
                        throw cancelled
                    }
                }
                if (failRecentHistory.get()) Response.error(503, "".toResponseBody())
                else Response.success(recentHistory)
            }
            coEvery { api.getLastActivities(any()) } coAnswers {
                if (activityCalls.incrementAndGet() == 1 && blockFirstActivity) {
                    activityEntered.complete(Unit)
                    releaseActivity.await()
                }
                Response.success(TraktLastActivitiesResponseDto(
                    movies = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z"),
                    episodes = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z")))
            }
            coEvery { api.getPlayback(any(), any(), any(), any()) } answers {
                playbackCalls.incrementAndGet()
                if (secondArg<String>() == "movies" && missingMoviePayload.get())
                    Response.success<List<TraktPlaybackItemDto>>(null)
                else if (secondArg<String>() == "movies") Response.success(listOf(TraktPlaybackItemDto(
                    id = 1L, type = "movie", progress = 40f, pausedAt = "2026-01-01T00:00:00.000Z",
                    movie = TraktMovieDto(title = "Movie", ids = TraktIdsDto(trakt = 1, imdb = MOVIE_ID)))))
                else Response.success(emptyList())
            }
            coEvery { api.getEpisodeHistory(any(), any(), any(), any(), any()) } coAnswers {
                if (blockProgress) {
                    progressEntered.complete(Unit)
                    releaseProgress.await()
                }
                if (failProgress.get()) Response.error(503, "".toResponseBody()) else Response.success(emptyList())
            }
            coEvery { api.getHiddenItems(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
            coEvery { api.getWatched(any(), any(), any(), any(), any()) } answers {
                watchedMovieCalls.incrementAndGet()
                if (failWatchedMovies.get()) Response.error(503, "".toResponseBody())
                else Response.success(emptyList())
            }
            coEvery { api.getWatchedShows(any(), any(), any(), any()) } answers {
                watchedShowCalls.incrementAndGet()
                Response.success(emptyList())
            }
            val auth = mockk<TraktAuthService>()
            every { auth.isCircuitClosed() } returns true
            coEvery { auth.captureInitializationReadScope() } returns owner
            coEvery { auth.executeAuthorizedRequest<Any?>(any()) } coAnswers {
                firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer test")
            }
            val metadata = mockk<MetaRepository>()
            every { metadata.getMetaFromAllAddons(any(), any(), any()) } returns emptyFlow()
            val settings = mockk<TraktSettingsDataStore>()
            every { settings.continueWatchingDaysCap } returns flowOf(TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP)
            val layout = mockk<LayoutPreferenceDataStore>()
            every { layout.nextUpFromFurthestEpisode } returns MutableStateFlow(false)
            val profiles = mockk<ProfileManager>()
            every { profiles.activeProfileId } returns MutableStateFlow(1)
            every { profiles.activeProfileSelectionRevision } returns 0L
            val watched = mockk<WatchedSeriesStateHolder>()
            every { watched.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
            return TraktProgressService(mockk<Context>(relaxed = true), api, auth, metadata,
                mockk<TmdbService>(relaxed = true), settings, layout, mockk(relaxed = true), profiles, watched)
        }
    }

    private companion object {
        const val MOVIE_ID = "tt0000001"
        const val TIMEOUT_MS = 15_000L
    }

    private fun completedMovie() = listOf(TraktHistoryItemDto(watchedAt = "2026-01-02T00:00:00Z",
        movie = TraktMovieDto(title = "Completed movie", ids = TraktIdsDto(imdb = "tt100"))))
}
