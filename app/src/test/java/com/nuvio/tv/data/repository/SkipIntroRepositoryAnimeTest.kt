package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.remote.api.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class SkipIntroRepositoryAnimeTest {
    @Test fun `MAL AnimeSkip does not depend on a missing IntroDB episode mapping`() = runTest {
        val h = Harness()
        assertEquals(listOf(SkipInterval(10.0, 90.0, "op", "animeskip")), h.repo.getSkipIntervalsForMal("1", 2))
        h.verifyAnimeQueried()
        coVerify(exactly = 0) { h.intro.getSegments(any(), any(), any(), any()) }
    }

    @Test fun `Kitsu AnimeSkip does not depend on a missing IntroDB episode mapping`() = runTest {
        val h = Harness()
        assertEquals(listOf(SkipInterval(10.0, 90.0, "op", "animeskip")), h.repo.getSkipIntervalsForKitsu("2", 2))
        h.verifyAnimeQueried()
        coVerify(exactly = 0) { h.intro.getSegments(any(), any(), any(), any()) }
    }

    @Test fun `AniSkip wins the same category while AnimeSkip can fill another category`() = runTest {
        val h = Harness()
        h.aniIntervals = listOf(AniSkipResult(AniSkipInterval(20.0, 80.0), "op"))
        h.timestamps = listOf(ts(10.0, "Intro"), ts(90.0, "Canon"), ts(1_300.0, "Credits"), ts(1_390.0, "Preview"))
        val result = h.repo.getSkipIntervalsForMal("1", 2)
        assertEquals("aniskip", result.single { it.type == "op" }.provider)
        assertEquals(SkipInterval(1_300.0, 1_390.0, "ed", "animeskip"), result.single { it.type == "ed" })
    }

    @Test fun `the IMDb path also prefers AniSkip over a competing AnimeSkip opening`() = runTest {
        val h = Harness()
        h.aniIntervals = listOf(AniSkipResult(AniSkipInterval(20.0, 80.0), "op"))
        assertEquals(listOf(SkipInterval(20.0, 80.0, "op", "aniskip")), h.repo.getSkipIntervals("tt123", 1, 2))
    }

    @Test fun `the final AnimeSkip timestamp never becomes a skip to the end`() = runTest {
        val h = Harness()
        h.timestamps = listOf(ts(10.0, "Intro"), ts(90.0, "Canon"), ts(1_300.0, "Credits"))
        val result = h.repo.getSkipIntervalsForMal("1", 2)
        assertEquals(listOf(SkipInterval(10.0, 90.0, "op", "animeskip")), result)
        assertTrue(result.all { it.endTime.isFinite() && it.endTime < Double.MAX_VALUE })
    }

    @Test fun `a lone open ended opening is dropped instead of hiding AniSkip`() = runTest {
        val h = Harness()
        h.timestamps = listOf(ts(10.0, "Intro"))
        h.aniIntervals = listOf(AniSkipResult(AniSkipInterval(15.0, 85.0), "op"))
        assertEquals(listOf(SkipInterval(15.0, 85.0, "op", "aniskip")), h.repo.getSkipIntervalsForKitsu("2", 2))
    }

    @Test fun `invalid AnimeSkip timestamps cannot form a safe looking interval across a bad boundary`() = runTest {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, -1.0)) {
            val h = Harness()
            h.timestamps = listOf(ts(10.0, "Intro"), ts(invalid, "Canon"), ts(90.0, "Preview"))
            assertTrue(h.repo.getSkipIntervalsForMal("1", 2).isEmpty())
        }
    }

    @Test fun `an anime without any IMDb mapping still queries AnimeSkip`() = runTest {
        val h = Harness(imdb = null)
        assertEquals("animeskip", h.repo.getSkipIntervalsForKitsu("2", 2).single().provider)
        h.verifyAnimeQueried()
    }

    @Test fun `cancelling AnimeSkip does not turn cancellation into a cached empty result`() = runTest {
        val h = Harness()
        coEvery { h.anime.query(any(), any()) } throws CancellationException("synthetic cancellation")
        try {
            h.repo.getSkipIntervalsForMal("1", 2)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    private class Harness(imdb: String? = "tt123") {
        val intro = mockk<IntroDbApi>()
        val anime = mockk<AnimeSkipApi>()
        private val ani = mockk<AniSkipApi>()
        private val resolver = mockk<SimklIdResolver>()
        var aniIntervals = emptyList<AniSkipResult>()
        var timestamps = listOf(ts(10.0, "Intro"), ts(90.0, "Canon"))
        val repo: SkipIntroRepository
        init {
            val ids = SimklIdResolver.ResolvedIds(123, "anime", mal = "1", anilist = "3", kitsu = "2", imdb = imdb)
            coEvery { resolver.resolveIds(any(), any(), any()) } returns ids
            coEvery { resolver.resolveIdsForImdbEpisode(any(), any(), any()) } returns ids
            coEvery { resolver.getEpisodeMapping(any(), any()) } returns emptyList()
            coEvery { resolver.resolveEpisodeTvdb(any(), any(), any()) } returns null
            coEvery { intro.getSegments(any(), any(), any(), any()) } returns Response.success(IntroDbSegmentsResponse())
            coEvery { ani.getSkipTimes(any(), any(), any(), any()) } answers {
                Response.success(AniSkipResponse(aniIntervals.isNotEmpty(), aniIntervals))
            }
            coEvery { anime.query(any(), any()) } answers {
                val query = secondArg<AnimeSkipRequest>().query
                val data = if (query.contains("findShowsByExternalId"))
                    AnimeSkipData(findShowsByExternalId = listOf(AnimeSkipShow("synthetic-show"))) else
                    AnimeSkipData(findEpisodesByShowId = listOf(AnimeSkipEpisode(number = "2", timestamps = timestamps)))
                Response.success(AnimeSkipResponse(data))
            }
            val settings = mockk<AnimeSkipSettingsDataStore> {
                every { clientId } returns flowOf("synthetic-client")
                every { enabled } returns flowOf(true)
            }
            repo = SkipIntroRepository(intro, ani, anime, resolver, settings, mockk<TmdbService>())
        }
        fun verifyAnimeQueried() {
            coVerify(exactly = 2) { anime.query("synthetic-client", any()) }
        }
    }
    private companion object {
        fun ts(at: Double, name: String) = AnimeSkipTimestamp(at, AnimeSkipTimestampType(name))
    }
}
