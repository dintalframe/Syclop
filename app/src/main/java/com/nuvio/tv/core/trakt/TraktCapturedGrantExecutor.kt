package com.nuvio.tv.core.trakt

import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** No credentials in display state, logs, or generated data-class toString. */
data class TraktActionSelection(val profileId: Int, val revision: Long)
internal interface TraktActionCredentials {
    fun selection(): TraktActionSelection
    fun registration(): TraktAppRegistration
    fun grantRevision(profileId: Int): Long
    suspend fun read(profileId: Int): TraktAuthState
    suspend fun saveRefresh(expected: TraktAuthState, revision: Long, token: TraktTokenResponseDto,
        selectionCurrent: () -> Boolean): Boolean
}

class TraktActionSession internal constructor(
    internal val selection: TraktActionSelection,
    internal val grantRevision: Long,
    internal val registration: TraktAppRegistration,
    internal var state: TraktAuthState
) {
    override fun toString() = "TraktActionSession(credentials=REDACTED)"
}

enum class TraktActionFailure { DISCONNECTED, STALE, NETWORK_UNCERTAIN, HTTP, INVALID_RESPONSE, ITEM_NOT_FOUND }
class TraktActionException(val failure: TraktActionFailure, val status: Int? = null) :
    Exception("Trakt action failed: ${failure.name}")

/** New manual writes have a captured grant and never replay ambiguous network failures. */
@Singleton
class TraktCapturedGrantExecutor internal constructor(
    private val credentials: TraktActionCredentials,
    private val refresh: suspend (TraktRefreshTokenRequestDto) -> Response<TraktTokenResponseDto>,
    private val coordinator: TraktTokenRefreshCoordinator,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    @Inject constructor(credentials: AndroidTraktActionCredentials,
        api: com.nuvio.tv.data.remote.api.TraktActionsApi,
        coordinator: TraktTokenRefreshCoordinator) : this(credentials, api::refreshToken, coordinator)

    private val writePacingMutex = Mutex()
    private var lastWriteMs = 0L
    fun selection(): TraktActionSelection = credentials.selection()

    suspend fun capture(expectedSelection: TraktActionSelection): TraktActionSession {
        currentCoroutineContext().ensureActive()
        if (credentials.selection() != expectedSelection) stale()
        val registration = credentials.registration()
        val revision = credentials.grantRevision(expectedSelection.profileId)
        val state = credentials.read(expectedSelection.profileId)
        if (credentials.selection() != expectedSelection || credentials.registration() != registration ||
            credentials.grantRevision(expectedSelection.profileId) != revision) stale()
        if (revision < 0 || !registration.isConfigured || !state.isAuthenticated || state.registrationClientId != registration.clientId)
            throw TraktActionException(TraktActionFailure.DISCONNECTED)
        return TraktActionSession(expectedSelection, revision, registration, state)
    }

    suspend fun isCurrent(session: TraktActionSession): Boolean = try { checkCurrent(session); true }
        catch (_: TraktActionException) { false }

    private fun selectionCurrent(session: TraktActionSession): Boolean =
        credentials.selection() == session.selection && credentials.registration() == session.registration &&
            credentials.grantRevision(session.selection.profileId) == session.grantRevision

    private suspend fun checkCurrent(session: TraktActionSession): TraktAuthState {
        currentCoroutineContext().ensureActive()
        if (!selectionCurrent(session)) stale()
        val current = credentials.read(session.selection.profileId)
        if (!selectionCurrent(session) || current.accessToken != session.state.accessToken ||
            current.refreshToken != session.state.refreshToken || current.registrationClientId != session.registration.clientId) stale()
        return current
    }

    suspend fun <T> execute(session: TraktActionSession, write: Boolean = false,
        request: suspend (authorization: String, publicClientId: String) -> Response<T>): Response<T> {
        try {
            var state = checkCurrent(session)
            if (expiring(state)) state = renew(session)
            if (write) writePacingMutex.withLock {
                val wait = (lastWriteMs + 1_000L - nowMs()).coerceAtLeast(0L)
                if (wait > 0L) pause(wait)
                checkCurrent(session)
                lastWriteMs = nowMs()
            }
            checkCurrent(session)
            var response = request("Bearer ${state.accessToken}", session.registration.clientId)
            checkAfterResponse(session, response)
            // A definite401 can safely retry once with the SAME captured viewer grant.
            if (response.code() == 401) {
                response.errorBody()?.close()
                state = renew(session, state.accessToken)
                checkCurrent(session)
                response = request("Bearer ${state.accessToken}", session.registration.clientId)
                checkAfterResponse(session, response)
            }
            return response
        } catch (e: CancellationException) { throw e }
        catch (e: TraktActionException) { throw e }
        catch (_: IOException) { throw TraktActionException(TraktActionFailure.NETWORK_UNCERTAIN) }
    }

    private suspend fun <T> checkAfterResponse(session: TraktActionSession, response: Response<T>) {
        try { checkCurrent(session) } catch (error: Throwable) {
            response.errorBody()?.close()
            throw error
        }
    }

    private suspend fun renew(session: TraktActionSession, failedAccessToken: String? = null): TraktAuthState = coordinator.mutex.withLock {
        val expected = checkCurrent(session)
        // A concurrent action may already have replaced the token that received401.
        if (failedAccessToken != null && expected.accessToken != failedAccessToken) return@withLock expected
        val response = refresh(TraktRefreshTokenRequestDto(expected.refreshToken!!,
            session.registration.clientId, session.registration.redirectUri))
        checkCurrent(session)
        val token = response.body()
        if (!response.isSuccessful || token == null || token.accessToken.isBlank() || token.refreshToken.isBlank() ||
            !token.tokenType.equals("bearer", true) || token.expiresIn <= 0 || token.createdAt <= 0L) {
            response.errorBody()?.close()
            throw TraktActionException(TraktActionFailure.HTTP, response.code())
        }
        if (!credentials.saveRefresh(expected, session.grantRevision, token) { selectionCurrent(session) }) stale()
        currentCoroutineContext().ensureActive()
        if (!selectionCurrent(session)) stale()
        session.state = expected.copy(accessToken = token.accessToken, refreshToken = token.refreshToken,
            tokenType = token.tokenType, createdAt = token.createdAt, expiresIn = token.expiresIn)
        checkCurrent(session)
    }

    private fun expiring(state: TraktAuthState): Boolean {
        val created = state.createdAt ?: return true
        val lifetime = state.expiresIn ?: return true
        if (created <= 0L || lifetime <= 0 || created > Long.MAX_VALUE / 1_000L - lifetime) return true
        return nowMs() >= (created + lifetime) * 1_000L - 60_000L
    }
    private fun stale(): Nothing = throw TraktActionException(TraktActionFailure.STALE)
}
