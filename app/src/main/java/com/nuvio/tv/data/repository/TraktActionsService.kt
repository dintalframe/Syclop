package com.nuvio.tv.data.repository

import com.nuvio.tv.core.trakt.*
import com.nuvio.tv.data.remote.api.TraktActionsApi
import com.nuvio.tv.data.remote.dto.trakt.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class TraktOwnRating(val value: Int?, val complete: Boolean)
data class TraktManualActionSnapshot(val rating: TraktOwnRating, val watching: TraktWatchingDto?)
sealed interface TraktCheckinResult {
    data class Started(val historyId: Long) : TraktCheckinResult
    data class Conflict(val expiresAt: String?) : TraktCheckinResult
}

@Singleton
class TraktActionsService @Inject constructor(private val api: TraktActionsApi,
    private val grants: TraktCapturedGrantExecutor, private val moshi: Moshi) {
    fun selection() = grants.selection()
    suspend fun capture(selection: TraktActionSelection) = grants.capture(selection)
    suspend fun isCurrent(session: TraktActionSession) = grants.isCurrent(session)

    suspend fun load(session: TraktActionSession, target: TraktActionTarget): TraktManualActionSnapshot =
        TraktManualActionSnapshot(ownRating(session, target), watching(session))

    suspend fun ownRating(session: TraktActionSession, target: TraktActionTarget): TraktOwnRating {
        val limit = 100
        for (page in 1..50) {
            val response = grants.execute(session) { auth, client -> api.ratings(auth, client, target.kind.ratingsType, page, limit) }
            if (!response.isSuccessful) failure(TraktActionFailure.HTTP, response.code())
            val body = response.body() ?: failure(TraktActionFailure.INVALID_RESPONSE)
            val found = body.firstOrNull { it.type == target.kind.apiType && TraktActionIdentity.matches(target, when(target.kind) {
                TraktActionKind.MOVIE -> it.movie?.ids; TraktActionKind.SHOW -> it.show?.ids; TraktActionKind.EPISODE -> it.episode?.ids
            }) }
            if (found != null) {
                if (found.rating !in 1..10) failure(TraktActionFailure.INVALID_RESPONSE)
                return TraktOwnRating(found.rating, true)
            }
            val count = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
            if ((count != null && count in 0..page) || (count == null && body.size < limit)) return TraktOwnRating(null, true)
        }
        // Bounded library scan; absence after truncation is not proof of no rating.
        return TraktOwnRating(null, false)
    }

    suspend fun rate(session: TraktActionSession, target: TraktActionTarget, rating: Int) {
        require(rating in 1..10) { "A rating must be between1 and10." }
        val response = grants.execute(session, write = true) { auth, client -> api.rate(auth, client, ratingRequest(target, rating)) }
        if (response.code() != 201) failure(TraktActionFailure.HTTP, response.code())
        val body = response.body() ?: failure(TraktActionFailure.INVALID_RESPONSE)
        if (notFound(body.notFound)) failure(TraktActionFailure.ITEM_NOT_FOUND)
        if (count(body.added, target.kind) + count(body.updated, target.kind) < 1) failure(TraktActionFailure.INVALID_RESPONSE)
    }
    suspend fun clearRating(session: TraktActionSession, target: TraktActionTarget) {
        val response = grants.execute(session, write = true) { auth, client -> api.removeRating(auth, client, ratingRequest(target, null)) }
        if (response.code() != 200) failure(TraktActionFailure.HTTP, response.code())
        val body = response.body() ?: failure(TraktActionFailure.INVALID_RESPONSE)
        if (notFound(body.notFound)) failure(TraktActionFailure.ITEM_NOT_FOUND)
        if (count(body.deleted, target.kind) < 1) failure(TraktActionFailure.INVALID_RESPONSE)
    }
    suspend fun watching(session: TraktActionSession): TraktWatchingDto? {
        val response = grants.execute(session) { auth, client -> api.watching(auth, client) }
        if (response.code() == 204) return null
        if (!response.isSuccessful) failure(TraktActionFailure.HTTP, response.code())
        return response.body() ?: failure(TraktActionFailure.INVALID_RESPONSE)
    }
    suspend fun checkin(session: TraktActionSession, target: TraktActionTarget): TraktCheckinResult {
        require(target.canCheckin) { "Select an exact movie or episode." }
        val media = TraktCheckinMediaDto(target.ids)
        val request = when(target.kind) {
            TraktActionKind.MOVIE -> TraktCheckinRequestDto(movie = media)
            TraktActionKind.EPISODE -> TraktCheckinRequestDto(episode = media)
            TraktActionKind.SHOW -> error("Select an exact episode.")
        }
        val response = grants.execute(session, write = true) { auth, client -> api.checkin(auth, client, request) }
        if (response.code() == 409) {
            val expiry = response.errorBody()?.use { body ->
                val source = body.source()
                if (source.request(65_537L)) null else runCatching {
                    moshi.adapter(TraktCheckinConflictDto::class.java).fromJson(source.readUtf8())?.expiresAt
                        ?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
                }.getOrNull()
            }
            return TraktCheckinResult.Conflict(expiry)
        }
        if (response.code() != 200) failure(TraktActionFailure.HTTP, response.code())
        val body = response.body() ?: failure(TraktActionFailure.INVALID_RESPONSE)
        val ids = if(target.kind == TraktActionKind.MOVIE) body.movie?.ids else body.episode?.ids
        if (body.id <= 0L || !TraktActionIdentity.matches(target, ids)) failure(TraktActionFailure.INVALID_RESPONSE)
        return TraktCheckinResult.Started(body.id)
    }
    /** Trakt endpoint cancels the account's active check-in, not a chosen media ID. */
    suspend fun cancelActiveCheckin(session: TraktActionSession) {
        val response = grants.execute(session, write = true) { auth, client -> api.cancelCheckin(auth, client) }
        if (response.code() != 204) failure(TraktActionFailure.HTTP, response.code())
    }
    private fun ratingRequest(target: TraktActionTarget, rating: Int?) = when(target.kind) {
        TraktActionKind.MOVIE -> TraktRatingRequestDto(movies = listOf(TraktRatedMediaDto(target.ids, rating)))
        TraktActionKind.SHOW -> TraktRatingRequestDto(shows = listOf(TraktRatedMediaDto(target.ids, rating)))
        TraktActionKind.EPISODE -> TraktRatingRequestDto(episodes = listOf(TraktRatedMediaDto(target.ids, rating)))
    }
    private fun count(value: TraktActionCountsDto?, kind: TraktActionKind) = when(kind) {
        TraktActionKind.MOVIE -> value?.movies; TraktActionKind.SHOW -> value?.shows; TraktActionKind.EPISODE -> value?.episodes
    } ?: 0
    private fun notFound(value: TraktRatingRequestDto?) = value?.let {
        !it.movies.isNullOrEmpty() || !it.shows.isNullOrEmpty() || !it.episodes.isNullOrEmpty()
    } ?: false
    private fun failure(type: TraktActionFailure, status: Int? = null): Nothing = throw TraktActionException(type, status)
}
