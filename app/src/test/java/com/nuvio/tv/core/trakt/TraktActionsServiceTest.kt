package com.nuvio.tv.core.trakt

import com.nuvio.tv.data.repository.*
import com.nuvio.tv.data.remote.api.TraktActionsApi
import com.nuvio.tv.data.remote.dto.trakt.*
import com.squareup.moshi.Moshi
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TraktActionsServiceTest {
    private val target = TraktActionTarget(TraktActionKind.MOVIE, TraktIdsDto(imdb = "tt1254207"), "Fixture")
    @Test fun `rating lower and upper bounds reject before network`() = runTest {
        val h = Fixture()
        for(rating in listOf(0,11)) try { h.service.rate(h.session(), target, rating); fail() } catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { h.api.rate(any(), any(), any()) }
    }
    @Test fun `a201 not found response cannot be reported as saved`() = runTest {
        val h = Fixture()
        coEvery { h.api.rate(any(), any(), any()) } returns Response.success(201,
            TraktRatingMutationDto(added = TraktActionCountsDto(movies = 1), notFound = TraktRatingRequestDto(movies = listOf(TraktRatedMediaDto(target.ids, 8)))))
        assertFailure(TraktActionFailure.ITEM_NOT_FOUND) { h.service.rate(h.session(), target, 8) }
    }
    @Test fun `clear sends removal identity without zero rating`() = runTest {
        val h = Fixture(); val body = slot<TraktRatingRequestDto>()
        coEvery { h.api.removeRating(any(), any(), capture(body)) } returns Response.success(TraktRatingMutationDto(deleted = TraktActionCountsDto(movies = 1)))
        h.service.clearRating(h.session(), target)
        assertNull(body.captured.movies!!.single().rating)
        assertEquals(target.ids, body.captured.movies!!.single().ids)
        coVerify(exactly = 0) { h.api.rate(any(), any(), any()) }
    }
    @Test fun `checkin uses exact identity explicit no sharing and64bit history ID`() = runTest {
        val h = Fixture(); val body = slot<TraktCheckinRequestDto>()
        coEvery { h.api.checkin(any(), any(), capture(body)) } returns Response.success(TraktCheckinResponseDto(4_000_000_000L, movie = TraktMovieDto(ids = target.ids)))
        assertEquals(TraktCheckinResult.Started(4_000_000_000L), h.service.checkin(h.session(), target))
        assertEquals(TraktCheckinSharingDto(false,false,false), body.captured.sharing)
        assertEquals(target.ids, body.captured.movie!!.ids); assertNull(body.captured.episode)
    }
    @Test fun `conflict never deletes or retries existing checkin`() = runTest {
        val h = Fixture()
        coEvery { h.api.checkin(any(), any(), any()) } returns Response.error(409, "{}".toResponseBody())
        assertTrue(h.service.checkin(h.session(), target) is TraktCheckinResult.Conflict)
        coVerify(exactly = 1) { h.api.checkin(any(), any(), any()) }
        coVerify(exactly = 0) { h.api.cancelCheckin(any(), any()) }
    }
    @Test fun `show checkin cannot accidentally mark an inferred episode`() = runTest {
        val h = Fixture()
        try { h.service.checkin(h.session(), target.copy(kind = TraktActionKind.SHOW)); fail() } catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { h.api.checkin(any(), any(), any()) }
    }
    @Test fun `wrong response movie cannot confirm a successful checkin`() = runTest {
        val h = Fixture()
        coEvery { h.api.checkin(any(), any(), any()) } returns Response.success(TraktCheckinResponseDto(99, movie = TraktMovieDto(ids = TraktIdsDto(imdb = "tt9999999"))))
        assertFailure(TraktActionFailure.INVALID_RESPONSE) { h.service.checkin(h.session(), target) }
    }
    @Test fun `own rating scans pages for exact ID instead of taking first aggregate rating`() = runTest {
        val h = Fixture()
        coEvery { h.api.ratings(any(), any(), "movies", 1, 100) } returns Response.success(listOf(
            TraktUserRatingDto(1, "movie", movie = TraktMovieDto(ids = TraktIdsDto(imdb = "tt9999999")))), Headers.headersOf("X-Pagination-Page-Count", "2"))
        coEvery { h.api.ratings(any(), any(), "movies", 2, 100) } returns Response.success(listOf(
            TraktUserRatingDto(9, "movie", movie = TraktMovieDto(ids = target.ids))), Headers.headersOf("X-Pagination-Page-Count", "2"))
        assertEquals(TraktOwnRating(9,true), h.service.ownRating(h.session(), target))
    }
    @Test fun `watching204 is a successful absence not a playback error`() = runTest {
        val h = Fixture()
        coEvery { h.api.watching(any(), any()) } returns Response.success<TraktWatchingDto>(204, null)
        assertNull(h.service.watching(h.session()))
    }
    private class Fixture {
        val h = TraktActionHarness()
        val api = mockk<TraktActionsApi>()
        val service = TraktActionsService(api, h.executor, Moshi.Builder().build())
        suspend fun session() = h.session()
    }
}
