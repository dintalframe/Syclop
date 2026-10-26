package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressAcknowledgement
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.ui.screens.home.choosePreferredNextUpSeed
import com.nuvio.tv.ui.screens.home.reconcileContinueWatchingInProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressRepositoryContinueWatchingTest {
    @Test
    fun `local episode seven immediately replaces stale Trakt episode three without waiting for optimistic sync`() = runBlocking {
        val h = harness()
        h.awaitProvider()
        val current = progress(7, 700L, 40L, scope = ACCOUNT_A)

        h.repository.saveProgress(current, profileId = 1, syncRemote = false)
        val rows = h.repository.allProgress.first()

        assertEquals(7, reconcileContinueWatchingInProgress(rows, emptyList()).single().episode)
        assertEquals(current, rows.first())
        assertEquals(current, h.repository.getProgress("tt0898266", 1).first())
        assertEquals(current, h.repository.getEpisodeProgress("tt0898266", 1, 7, 1).first())
        assertEquals(current, h.repository.getAllEpisodeProgress("tt0898266", 1).first()[1 to 7])
    }

    @Test
    fun `completed latest local episode survives repository recreation and becomes next up seed`() = runBlocking {
        val h = harness()
        h.awaitProvider()
        h.repository.markAsCompleted(progress(7, 700L, 95L, scope = ACCOUNT_A), 1, false)
        val recreated = h.newRepository()
        h.awaitProvider(recreated)

        val rows = recreated.allProgress.first()
        val seeds = recreated.observeNextUpSeeds().first()
        assertTrue(reconcileContinueWatchingInProgress(rows, seeds).isEmpty())
        assertEquals(7, choosePreferredNextUpSeed(seeds)?.episode)
        assertEquals(ACCOUNT_A, seeds.first { it.episode == 7 }.localPlaybackScope)
    }

    @Test
    fun `newer exact remote progress and later external episode supersede saved local playback`() = runBlocking {
        val h = harness(initialLocal = listOf(progress(7, 700L, 40L, scope = ACCOUNT_A)))
        h.awaitProvider()
        val newerSameEpisode = progress(7, 800L, 60L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
        h.remote.value = listOf(newerSameEpisode)
        assertEquals(newerSameEpisode, h.repository.allProgress.first().single())

        val laterExternalEpisode = progress(8, 900L, 30L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
        h.remote.value = listOf(newerSameEpisode, laterExternalEpisode)
        assertEquals(8, reconcileContinueWatchingInProgress(h.repository.allProgress.first(), emptyList()).single().episode)
    }

    @Test
    fun `switching tracking account hides prior account local playback and completed seeds`() = runBlocking {
        val h = harness(initialLocal = listOf(progress(7, 700L, 100L, scope = ACCOUNT_A)))
        h.awaitProvider()
        assertTrue(h.repository.allProgress.first().any { it.episode == 7 })

        h.accountScope.value = ACCOUNT_B
        assertFalse(h.repository.allProgress.first().any { it.episode == 7 })
        assertFalse(h.repository.observeNextUpSeeds().first().any { it.episode == 7 })
    }

    @Test
    fun `unstamped imported progress cannot become an account local override`() = runBlocking {
        val h = harness(initialLocal = listOf(progress(7, 700L, 40L)))
        h.awaitProvider()

        assertEquals(listOf(3), h.repository.allProgress.first().map { it.episode })
    }

    @Test
    fun `profile switch never reads saved progress from former profile`() = runBlocking {
        val h = harness(initialLocal = listOf(progress(7, 700L, 40L, scope = ACCOUNT_A)))
        h.awaitProvider()
        h.profile.value = 2

        assertEquals(listOf(3), h.repository.allProgress.first().map { it.episode })
        assertFalse(h.repository.observeNextUpSeeds().first().any { it.episode == 7 })
    }

    @Test
    fun `delayed outgoing completion keeps its captured time and cannot replace current episode seven`() = runBlocking {
        val h = harness()
        h.awaitProvider()
        val current = progress(7, 700L, 40L, scope = ACCOUNT_A)
        h.repository.saveProgress(current, 1, false)
        h.repository.markAsCompleted(progress(3, 300L, 100L, scope = ACCOUNT_A), 1, false)

        val rows = h.repository.allProgress.first()
        assertEquals(current, rows.first())
        assertEquals(7, reconcileContinueWatchingInProgress(rows, h.repository.observeNextUpSeeds().first()).single().episode)
    }

    @Test
    fun `later lower episode rewatch remains latest local progress`() = runBlocking {
        val h = harness(initialLocal = listOf(progress(7, 700L, 100L, scope = ACCOUNT_A)))
        h.awaitProvider()
        val rewatch = progress(3, 800L, 40L, scope = ACCOUNT_A)
        h.repository.saveProgress(rewatch, 1, false)

        assertEquals(rewatch, h.repository.allProgress.first().first())
        assertEquals(3, reconcileContinueWatchingInProgress(h.repository.allProgress.first(), emptyList()).single().episode)
    }

    @Test
    fun `confirmed remote acknowledgement retires local override so later external removal stays removed`() = runBlocking {
        val local = progress(7, 700L, 40L, scope = ACCOUNT_A)
        val h = harness(initialLocal = listOf(local))
        h.awaitProvider()
        val acknowledged = progress(7, 800L, 40L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
        h.remote.value = listOf(acknowledged)
        h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, listOf(acknowledged)) { true }
        h.awaitScope(null)

        h.remote.value = emptyList()
        h.seeds.value = emptyList()
        h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, emptyList()) { true }
        assertTrue(h.repository.allProgress.first().isEmpty())
        assertTrue(h.repository.observeNextUpSeeds().first().isEmpty())
    }

    @Test
    fun `confirmed completed history retires completion override so external unwatch stays unwatched`() = runBlocking {
        val local = progress(7, 700L, 100L, scope = ACCOUNT_A)
        val h = harness(initialLocal = listOf(local))
        h.awaitProvider()
        val confirmedHistory = progress(7, 800L, 100L, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        h.remote.value = listOf(confirmedHistory)
        h.seeds.value = listOf(confirmedHistory)
        h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, listOf(confirmedHistory)) { true }
        h.awaitScope(null)

        h.remote.value = emptyList()
        h.seeds.value = emptyList()
        h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, emptyList()) { true }
        assertTrue(h.repository.allProgress.first().isEmpty())
        assertTrue(h.repository.observeNextUpSeeds().first().isEmpty())
    }

    @Test
    fun `optimistic or failed partial projection alone never retires durable local scope`() = runBlocking {
        val local = progress(7, 700L, 40L, scope = ACCOUNT_A)
        val h = harness(initialLocal = listOf(local))
        h.awaitProvider()
        // The visible provider flow can contain optimistic state; only confirmedProgress is an ack.
        h.remote.value = listOf(progress(7, 800L, 40L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK))
        h.repository.allProgress.first()
        assertEquals(ACCOUNT_A, h.saved().single().localPlaybackScope)
        h.remote.value = emptyList() // failed/partial reads publish no acknowledgement
        assertEquals(local, h.repository.allProgress.first().single())
        assertEquals(ACCOUNT_A, h.saved().single().localPlaybackScope)
    }

    @Test
    fun `newer local playback racing acknowledgement compare clear keeps its durable scope`() = runBlocking {
        val local = progress(7, 700L, 40L, scope = ACCOUNT_A)
        val latest = progress(7, 900L, 60L, scope = ACCOUNT_A)
        val h = harness(initialLocal = listOf(local))
        h.awaitProvider()
        h.beforeClear = { h.repository.saveProgress(latest, 1, false) }
        val acknowledged = progress(7, 800L, 40L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
        h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, listOf(acknowledged)) { true }
        withTimeout(3_000L) { while (h.saved().single().lastWatched != 900L) delay(1L) }

        assertEquals(latest, h.saved().single())
        assertEquals(latest, h.repository.allProgress.first().first())
    }

    @Test
    fun `foreign account and stale owned acknowledgement cannot clear the current local record`() = runBlocking {
        val local = progress(7, 700L, 40L, scope = ACCOUNT_A)
        val h = harness(initialLocal = listOf(local))
        h.awaitProvider()
        val acknowledged = progress(7, 800L, 40L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
        val foreignChecks = java.util.concurrent.atomic.AtomicInteger()
        h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_B, listOf(acknowledged)) { foreignChecks.incrementAndGet(); true }
        withTimeout(3_000L) { while (foreignChecks.get() == 0) delay(1L) }
        val staleChecks = java.util.concurrent.atomic.AtomicInteger()
        h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, listOf(acknowledged)) { staleChecks.incrementAndGet(); false }
        withTimeout(3_000L) { while (staleChecks.get() == 0) delay(1L) }

        assertEquals(local, h.saved().single())
    }

    @Test
    fun `failed acknowledgement read or compare clear retains local record and next acknowledgement still succeeds`() = runBlocking {
        for (failRead in listOf(true, false)) {
            val local = progress(7, 700L, 40L, scope = ACCOUNT_A)
            val h = harness(initialLocal = listOf(local))
            h.awaitProvider()
            h.failNextRead = failRead
            h.failNextClear = !failRead
            val acknowledged = progress(7, 800L, 40L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
            h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, listOf(acknowledged)) { true }
            withTimeout(3_000L) {
                while ((if (failRead) h.ackReads.get() else h.ackClears.get()) == 0) delay(1L)
            }
            assertEquals(local, h.saved().single())

            h.confirmed.value = TrackingProgressAcknowledgement(1, ACCOUNT_A, listOf(acknowledged)) { true }
            h.awaitScope(null)
            assertEquals(local.copy(localPlaybackScope = null), h.saved().single())
        }
    }

    private fun harness(initialLocal: List<WatchProgress> = emptyList()) = Harness(initialLocal)

    private class Harness(initialLocal: List<WatchProgress>) {
        val profile = MutableStateFlow(1)
        val accountScope = MutableStateFlow<String?>(ACCOUNT_A)
        val confirmed = MutableStateFlow<TrackingProgressAcknowledgement?>(null)
        var beforeClear: (suspend () -> Unit)? = null
        @Volatile var failNextRead = false
        @Volatile var failNextClear = false
        val ackReads = java.util.concurrent.atomic.AtomicInteger()
        val ackClears = java.util.concurrent.atomic.AtomicInteger()
        val remote = MutableStateFlow(listOf(progress(3, 100L, 30L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)))
        val seeds = MutableStateFlow(listOf(progress(2, 90L, 100L, source = WatchProgress.SOURCE_TRAKT_HISTORY)))
        private val local = mapOf(1 to MutableStateFlow(initialLocal), 2 to MutableStateFlow(emptyList<WatchProgress>()))
        private val manager = mockk<ProfileManager> {
            every { activeProfileId } returns profile
            every { activeProfileSelectionRevision } answers { profile.value.toLong() }
        }
        private val preferences = mockk<WatchProgressPreferences>(relaxed = true) {
            every { observeAllProgress(any()) } answers { local.getValue(firstArg()) }
            every { getEpisodeProgress(any(), any(), any(), any()) } answers {
                val season = secondArg<Int>()
                val episode = thirdArg<Int>()
                local.getValue(arg<Int>(3)).map { rows -> rows.firstOrNull { it.season == season && it.episode == episode } }
            }
            every { getAllEpisodeProgress(any(), any()) } answers {
                local.getValue(secondArg<Int>()).map { rows -> rows.associateBy { it.season!! to it.episode!! } }
            }
            coEvery { saveProgress(any(), any()) } coAnswers {
                val storage = local.getValue(secondArg<Int>())
                val saved = firstArg<WatchProgress>()
                storage.value = (storage.value.filterNot { it.contentId == saved.contentId && it.season == saved.season && it.episode == saved.episode } + saved)
                    .groupBy { it.contentId }.values.map { it.maxBy(WatchProgress::lastWatched) }
            }
            coEvery { getAllRawEntries(any()) } coAnswers {
                ackReads.incrementAndGet()
                if (failNextRead) { failNextRead = false; throw java.io.IOException("synthetic storage read failure") }
                local.getValue(firstArg<Int>()).value.associateBy { "${it.contentId}_s${it.season}e${it.episode}" }
            }
            coEvery { compareAndClearLocalPlaybackScope(any(), any(), any()) } coAnswers {
                ackClears.incrementAndGet()
                if (failNextClear) { failNextClear = false; throw java.io.IOException("synthetic storage write failure") }
                val expected = firstArg<WatchProgress>()
                val storage = local.getValue(secondArg<Int>())
                val current = thirdArg<() -> Boolean>()
                beforeClear?.also { beforeClear = null }?.invoke()
                if (current() && storage.value.any { it == expected }) {
                    storage.value = storage.value.map { if (it == expected) it.copy(localPlaybackScope = null) else it }
                    true
                } else false
            }
        }
        private val provider = mockk<TrackingProgressProvider>(relaxed = true) {
            every { providerId } returns TrackingProviderId.TRAKT
            every { isAuthenticated } returns flowOf(true)
            every { allProgress } returns remote
            every { episodeProgress(any()) } returns flowOf(emptyMap())
            every { nextUpSeeds } returns seeds
            every { localPlaybackScope } returns accountScope
            every { confirmedProgress } returns confirmed
            coEvery { captureLocalPlaybackScope(any()) } answers { accountScope.value }
            every { remoteProgressLoaded } returns flowOf(true)
            every { retainsLocalProgress(any()) } returns false
        }
        val repository = newRepository()
        fun saved() = local.getValue(profile.value).value
        suspend fun awaitScope(scope: String?) = withTimeout(3_000L) {
            while (saved().single().localPlaybackScope != scope) delay(1L)
        }

        suspend fun awaitProvider(repository: WatchProgressRepositoryImpl = this.repository) = withTimeout(3_000L) {
            while (repository.captureLocalPlaybackScope(profile.value) != accountScope.value) delay(1L)
        }

        fun newRepository() = WatchProgressRepositoryImpl(
            watchProgressPreferences = preferences,
            traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
                every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.TRAKT)
            },
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true),
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true) { every { isAuthenticated } returns false },
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true), profileManager = manager,
            trackingProgressProviders = TrackingProgressProviderRegistry(setOf(provider)),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mockk<WatchStateMutationStore>(relaxed = true), serverWatched = mockk(relaxed = true)
        )
    }

    companion object {
        private const val ACCOUNT_A = "trakt-local-v1:account-a"
        private const val ACCOUNT_B = "trakt-local-v1:account-b"
        private fun progress(episode: Int, time: Long, position: Long, source: String = WatchProgress.SOURCE_LOCAL, scope: String? = null) = WatchProgress(
            contentId = "tt0898266", contentType = "series", name = "The Big Bang Theory",
            poster = "poster", backdrop = "backdrop", logo = null, videoId = "tt0898266:1:$episode",
            season = 1, episode = episode, episodeTitle = "Episode $episode", position = position,
            duration = 100L, lastWatched = time, source = source, localPlaybackScope = scope
        )
    }
}
