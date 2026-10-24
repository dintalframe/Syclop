package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.trakt.TraktAppRegistrationStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TraktTokenPollResult {
    data object Pending : TraktTokenPollResult
    data object AlreadyUsed : TraktTokenPollResult
    data object Expired : TraktTokenPollResult
    data object Denied : TraktTokenPollResult
    data class SlowDown(val pollIntervalSeconds: Int) : TraktTokenPollResult
    data class Approved(val username: String?) : TraktTokenPollResult
    data class Failed(val reason: String) : TraktTokenPollResult
}

@Singleton
class TraktAuthService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApi,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    private val appRegistrationStore: TraktAppRegistrationStore,
    private val refreshCoordinator: com.nuvio.tv.core.trakt.TraktTokenRefreshCoordinator = com.nuvio.tv.core.trakt.TraktTokenRefreshCoordinator()
) {
    private val refreshLeewaySeconds = 60L
    private val writeRequestMutex = Mutex()
    private val tokenRefreshMutex = refreshCoordinator.mutex
    private var lastWriteRequestAtMs = 0L
    private val minWriteIntervalMs = 1_000L
    private val transientRetryStatusCodes = setOf(502, 503, 504, 520, 521, 522)
    private val nonRetryableStatusCodes = setOf(400, 403, 404, 405, 409, 412, 420, 422, 423, 426)

    @Volatile private var circuitOpenUntilMs = 0L
    private val circuitFailures = AtomicInteger(0)
    private val circuitBaseCooldownMs = 5 * 60_000L
    private val circuitMaxCooldownMs = 60 * 60_000L

    private val rateLimitWindowMs = 5 * 60_000L
    private val rateLimitMaxCalls = 900
    private val getRequestTimestamps = ArrayDeque<Long>()
    private val rateLimitMutex = Mutex()

    private fun isCircuitOpen(): Boolean {
        return System.currentTimeMillis() < circuitOpenUntilMs
    }

    fun isCircuitClosed(): Boolean = !isCircuitOpen()

    private fun tripCircuit(reason: String) {
        val failures = circuitFailures.incrementAndGet()
        val cooldownMs = (circuitBaseCooldownMs shl (failures - 1).coerceAtMost(4))
            .coerceAtMost(circuitMaxCooldownMs)
        circuitOpenUntilMs = System.currentTimeMillis() + cooldownMs
        Log.w("TraktAuthService",
            "Circuit breaker OPEN: $reason (failures=$failures, cooldown=${cooldownMs / 1000}s)")
    }

    private fun resetCircuit() {
        if (circuitFailures.get() > 0) {
            trace("Circuit breaker reset after successful request")
        }
        circuitFailures.set(0)
        circuitOpenUntilMs = 0L
    }

    private suspend fun acquireGetRateSlot() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val windowStart = now - rateLimitWindowMs
            while (getRequestTimestamps.isNotEmpty() && getRequestTimestamps.first() < windowStart) {
                getRequestTimestamps.removeFirst()
            }
            if (getRequestTimestamps.size >= rateLimitMaxCalls) {
                val oldestInWindow = getRequestTimestamps.first()
                val waitMs = oldestInWindow + rateLimitWindowMs - now + 100L
                if (waitMs > 0) {
                    Log.w("TraktAuthService",
                        "GET rate limit approaching (${getRequestTimestamps.size}/$rateLimitMaxCalls in window), delaying ${waitMs}ms")
                    delay(waitMs)
                }
                val afterDelay = System.currentTimeMillis()
                val newWindowStart = afterDelay - rateLimitWindowMs
                while (getRequestTimestamps.isNotEmpty() && getRequestTimestamps.first() < newWindowStart) {
                    getRequestTimestamps.removeFirst()
                }
            }
            getRequestTimestamps.addLast(System.currentTimeMillis())
        }
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("TraktAuthService", message)
        }
    }

    fun hasRequiredCredentials(): Boolean {
        return appRegistrationStore.current().isConfigured
    }

    suspend fun getCurrentAuthState(): TraktAuthState {
        val state = traktAuthDataStore.getCurrentState()
        if (state.registrationClientId != null && state.registrationClientId != appRegistrationStore.current().clientId) {
            traktAuthDataStore.clearAuthForProfile(state.profileId)
            return TraktAuthState(profileId = state.profileId)
        }
        return state
    }

    suspend fun startDeviceAuth(): Result<TraktDeviceCodeResponseDto> {
        val registration = appRegistrationStore.current()
        if (!registration.isConfigured) {
            return Result.failure(IllegalStateException(context.getString(R.string.syclop_trakt_registration_missing)))
        }

        // Reuse an existing, still-valid device flow if one is already active.
        // This avoids re-hitting /oauth/device/code (which is tightly rate-limited
        // by Trakt) when the user navigates back to the login screen or taps the
        // Connect button twice in a row — see issue #1197.
        val state = getCurrentAuthState()
        val existingExpiresAt = state.expiresAt
        val existingCode = state.deviceCode
        if (
            !existingCode.isNullOrBlank() &&
            existingExpiresAt != null &&
            System.currentTimeMillis() < existingExpiresAt
        ) {
            return Result.success(
                TraktDeviceCodeResponseDto(
                    deviceCode = existingCode,
                    userCode = state.userCode.orEmpty(),
                    verificationUrl = state.verificationUrl.orEmpty(),
                    expiresIn = ((existingExpiresAt - System.currentTimeMillis()) / 1000L)
                        .coerceAtLeast(0L)
                        .toInt(),
                    interval = state.pollInterval ?: 5
                )
            )
        }

        // Issue a request, auto-retrying once on a transient 429 using the
        // Retry-After header when the server supplies a short back-off.
        suspend fun requestOnce(): Response<TraktDeviceCodeResponseDto> =
            traktApi.requestDeviceCode(
                TraktDeviceCodeRequestDto(clientId = registration.clientId)
            )

        var response = try {
            requestOnce()
        } catch (e: IOException) {
            return Result.failure(IllegalStateException(context.getString(R.string.trakt_error_network_retry)))
        }

        if (response.code() == 429) {
            val retryAfterSeconds = response.headers()["Retry-After"]?.toLongOrNull()
            if (retryAfterSeconds != null && retryAfterSeconds in 1L..10L) {
                delay(retryAfterSeconds * 1000L)
                response = try {
                    requestOnce()
                } catch (e: IOException) {
                    return Result.failure(IllegalStateException(context.getString(R.string.trakt_error_network_retry)))
                }
            }
        }

        val body = response.body()
        if (response.isSuccessful && body != null) {
            traktAuthDataStore.saveDeviceFlow(body, state.profileId, registration.clientId)
            return Result.success(body)
        }

        if (response.code() == 429) {
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            val minutes = ((retryAfter ?: 300L) + 59L) / 60L
            return Result.failure(
                IllegalStateException(context.getString(R.string.trakt_error_rate_limited_minutes, minutes))
            )
        }

        return Result.failure(
            IllegalStateException(context.getString(R.string.trakt_error_failed_start_code, response.code()))
        )
    }

    suspend fun pollDeviceToken(): TraktTokenPollResult {
        val registration = appRegistrationStore.current()
        if (!registration.isConfigured) {
            return TraktTokenPollResult.Failed(context.getString(R.string.syclop_trakt_registration_missing))
        }

        val state = getCurrentAuthState()
        val deviceCode = state.deviceCode
        if (deviceCode.isNullOrBlank()) {
            return TraktTokenPollResult.Failed(context.getString(R.string.trakt_error_no_active_device_code))
        }

        val response = try {
            traktApi.requestDeviceToken(
                TraktDeviceTokenRequestDto(
                    code = deviceCode,
                    clientId = registration.clientId
                )
            )
        } catch (e: IOException) {
            return TraktTokenPollResult.Failed(context.getString(R.string.trakt_error_network_will_retry))
        }

        val tokenBody = response.body()
        if (response.isSuccessful && tokenBody != null) {
            traktAuthDataStore.saveToken(tokenBody, state.profileId, registration.clientId)
            authSessionNoticeDataStore.markTraktAuthenticated()
            resetCircuit()
            traktAuthDataStore.clearDeviceFlowForProfile(state.profileId)
            val user = fetchUserSettings()
            return TraktTokenPollResult.Approved(user)
        }

        return when (response.code()) {
            400 -> TraktTokenPollResult.Pending
            409 -> {
                traktAuthDataStore.clearDeviceFlowForProfile(state.profileId)
                TraktTokenPollResult.AlreadyUsed
            }
            404 -> {
                traktAuthDataStore.clearDeviceFlowForProfile(state.profileId)
                TraktTokenPollResult.Failed(context.getString(R.string.trakt_error_invalid_device_code))
            }
            410 -> {
                traktAuthDataStore.clearDeviceFlowForProfile(state.profileId)
                TraktTokenPollResult.Expired
            }
            418 -> {
                traktAuthDataStore.clearDeviceFlowForProfile(state.profileId)
                TraktTokenPollResult.Denied
            }
            429 -> {
                val nextInterval = ((state.pollInterval ?: 5) + 5).coerceAtMost(60)
                traktAuthDataStore.updatePollInterval(nextInterval, state.profileId)
                TraktTokenPollResult.SlowDown(nextInterval)
            }
            else -> TraktTokenPollResult.Failed(
                context.getString(R.string.trakt_error_token_polling_failed_code, response.code())
            )
        }
    }

    suspend fun refreshTokenIfNeeded(force: Boolean = false,
        expectedSelection: Pair<Int, Long>? = null): Boolean {
        val registration = appRegistrationStore.current()
        if (!registration.isConfigured) return false
        val selection = expectedSelection ?: currentSelection()
        fun selectionCurrent() = currentSelection() == selection && appRegistrationStore.current() == registration
        if (!selectionCurrent()) return false
        return tokenRefreshMutex.withLock {
            if (!selectionCurrent()) return@withLock false
            val revision = traktAuthDataStore.grantRevision(selection.first)
            if (revision < 0L) return@withLock false
            val state = traktAuthDataStore.getCurrentState(selection.first)
            if (!selectionCurrent() || traktAuthDataStore.grantRevision(selection.first) != revision ||
                state.registrationClientId != registration.clientId) return@withLock false
            val refreshToken = state.refreshToken ?: return@withLock false
            if (!force && !isTokenExpiredOrExpiring(state)) return@withLock true
            val response = try {
                traktApi.refreshToken(TraktRefreshTokenRequestDto(refreshToken, registration.clientId, registration.redirectUri))
            } catch (_: IOException) { return@withLock false }
            if (!selectionCurrent()) { response.errorBody()?.close(); return@withLock false }
            val tokenBody = response.body()
            if (!response.isSuccessful || tokenBody == null) {
                response.errorBody()?.close()
                if (response.code() in setOf(400, 401, 403) &&
                    traktAuthDataStore.clearAuthIfCurrent(state, revision, ::selectionCurrent)) {
                    authSessionNoticeDataStore.markTraktReconnectRequired()
                    tripCircuit("Token refresh returned ${response.code()}")
                }
                return@withLock false
            }
            if (tokenBody.accessToken.isBlank() || tokenBody.refreshToken.isBlank() ||
                !tokenBody.tokenType.equals("bearer", true) || tokenBody.expiresIn <= 0 || tokenBody.createdAt <= 0) return@withLock false
            if (!traktAuthDataStore.saveRefreshedTokenIfCurrent(tokenBody, state, revision, ::selectionCurrent)) return@withLock false
            if (!selectionCurrent()) return@withLock false
            authSessionNoticeDataStore.markTraktAuthenticated()
            resetCircuit()
            true
        }
    }

    private fun currentSelection(): Pair<Int, Long> = traktAuthDataStore.activeProfileId to
        traktAuthDataStore.activeProfileSelectionRevision

    suspend fun revokeAndLogout() {
        val registration = appRegistrationStore.current()
        val state = getCurrentAuthState()
        if (registration.isConfigured) {
            state.accessToken?.let { accessToken ->
                runCatching {
                    traktApi.revokeToken(
                        TraktRevokeRequestDto(
                            token = accessToken,
                            clientId = registration.clientId
                        )
                    )
                }
            }
        }
        authSessionNoticeDataStore.markTraktExplicitLogout()
        traktAuthDataStore.clearAuthForProfile(state.profileId)
    }

    suspend fun fetchUserSettings(): String? {
        val profileId = getCurrentAuthState().profileId
        val response = executeAuthorizedRequest { authHeader ->
            traktApi.getUserSettings(authorization = authHeader)
        } ?: return null

        if (!response.isSuccessful) return null

        val username = response.body()?.user?.username
        val slug = response.body()?.user?.ids?.slug
        traktAuthDataStore.saveUser(username = username, userSlug = slug, profileId = profileId)
        return username
    }

    private data class AuthorizedRequestBinding(val selection: Pair<Int, Long>,
        val registration: com.nuvio.tv.core.trakt.TraktAppRegistration, val grantRevision: Long)
    private fun captureRequestBinding(): AuthorizedRequestBinding {
        val selection = currentSelection()
        return AuthorizedRequestBinding(selection, appRegistrationStore.current(), traktAuthDataStore.grantRevision(selection.first))
    }
    private fun bindingCurrent(binding: AuthorizedRequestBinding) = binding.grantRevision >= 0L &&
        currentSelection() == binding.selection && appRegistrationStore.current() == binding.registration &&
        traktAuthDataStore.grantRevision(binding.selection.first) == binding.grantRevision

    suspend fun <T> executeAuthorizedRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? = executeAuthorizedBoundRequest(captureRequestBinding(), call)

    private suspend fun <T> executeAuthorizedBoundRequest(binding: AuthorizedRequestBinding,
        call: suspend (authorizationHeader: String) -> Response<T>): Response<T>? {
        val selection = binding.selection
        val registration = binding.registration
        val revision = binding.grantRevision
        fun requestCurrent() = revision >= 0L && currentSelection() == selection &&
            appRegistrationStore.current() == registration && traktAuthDataStore.grantRevision(selection.first) == revision
        if (!requestCurrent()) return null
        if (isCircuitOpen()) {
            trace("authorized request: circuit breaker is OPEN, skipping request")
            return null
        }

        var token = getValidAccessToken(selection) ?: return null
        if (!requestCurrent()) return null
        var retriedAuth = false
        var retriedRateLimit = false
        var retriedTransient = false
        var retriedNetwork = false

        while (true) {
            if (!requestCurrent()) return null
            acquireGetRateSlot()
            if (!requestCurrent()) return null

            val response = try {
                call("Bearer $token")
            } catch (e: IOException) {
                if (!retriedNetwork) {
                    trace("authorized request: network error, retrying once")
                    delay(1_000L)
                    retriedNetwork = true
                    continue
                }
                Log.w("TraktAuthService", "Network error during authorized request")
                return null
            }

            if (!requestCurrent()) { response.errorBody()?.close(); return null }
            val code = response.code()

            if (response.isSuccessful) {
                resetCircuit()
                return response
            }

            if (code == 403) {
                tripCircuit("403 Forbidden (invalid API key or unapproved app) for ${responseTarget(response)}")
                return response
            }

            if (code == 423) {
                tripCircuit("423 Locked User Account for ${responseTarget(response)}")
                return response
            }

            if (code in nonRetryableStatusCodes) {
                trace("authorized request: non-retryable $code for ${responseTarget(response)}")
                return response
            }

            if (code == 401 && !retriedAuth) {
                val refreshed = refreshTokenIfNeeded(force = true, expectedSelection = selection)
                if (refreshed) {
                    trace("authorized request: 401 for ${responseTarget(response)}, retrying after token refresh")
                    token = traktAuthDataStore.getCurrentState(selection.first).accessToken ?: return response
                    if (!requestCurrent()) { response.errorBody()?.close(); return null }
                    retriedAuth = true
                    continue
                }
                tripCircuit("401 Unauthorized and token refresh failed for ${responseTarget(response)}")
                return response
            }

            if (code == 429 && !retriedRateLimit) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 2L, maxSeconds = 60L)
                trace("authorized request: 429 for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedRateLimit = true
                continue
            }

            if (code in transientRetryStatusCodes && !retriedTransient) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 30L, maxSeconds = 30L)
                trace("authorized request: transient $code for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedTransient = true
                continue
            }

            return response
        }
    }

    suspend fun <T> executePublicRequest(
        call: suspend () -> Response<T>
    ): Response<T>? {
        if (!hasRequiredCredentials()) return null
        if (isCircuitOpen()) {
            trace("public request: circuit breaker is OPEN, skipping request")
            return null
        }

        var retriedRateLimit = false
        var retriedTransient = false
        var retriedNetwork = false

        while (true) {
            acquireGetRateSlot()

            val response = try {
                call()
            } catch (e: IOException) {
                if (!retriedNetwork) {
                    trace("public request: network error, retrying once")
                    delay(1_000L)
                    retriedNetwork = true
                    continue
                }
                Log.w("TraktAuthService", "Network error during public request")
                return null
            }

            val code = response.code()

            if (response.isSuccessful) {
                resetCircuit()
                return response
            }

            if (code == 423) {
                tripCircuit("423 Locked User Account for ${responseTarget(response)}")
                return response
            }

            if (code == 401 || code == 403 || code in nonRetryableStatusCodes) {
                trace("public request: non-retryable $code for ${responseTarget(response)}")
                return response
            }

            if (code == 429 && !retriedRateLimit) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 2L, maxSeconds = 60L)
                trace("public request: 429 for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedRateLimit = true
                continue
            }

            if (code in transientRetryStatusCodes && !retriedTransient) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 30L, maxSeconds = 30L)
                trace("public request: transient $code for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedTransient = true
                continue
            }

            return response
        }
    }

    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        val binding = captureRequestBinding() // Before mutex/pacing can suspend.
        if (!bindingCurrent(binding)) return null
        writeRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (lastWriteRequestAtMs + minWriteIntervalMs - now).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            lastWriteRequestAtMs = System.currentTimeMillis()
        }
        if (!bindingCurrent(binding)) return null
        return executeAuthorizedBoundRequest(binding, call)
    }

    private suspend fun getValidAccessToken(expectedSelection: Pair<Int, Long>? = null): String? {
        val state = getCurrentAuthState()
        if (state.accessToken.isNullOrBlank()) return null
        if (refreshTokenIfNeeded(force = false, expectedSelection = expectedSelection)) {
            return getCurrentAuthState().accessToken
        }
        return null
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAt = createdAt + expiresIn
        val nowSeconds = System.currentTimeMillis() / 1000L
        return nowSeconds >= (expiresAt - refreshLeewaySeconds)
    }

    private suspend fun delayForRetryAfter(
        response: Response<*>,
        fallbackSeconds: Long,
        maxSeconds: Long
    ): Long {
        val retryAfterSeconds = response.headers()["Retry-After"]
            ?.toLongOrNull()
            ?.coerceIn(1L, maxSeconds)
            ?: fallbackSeconds
        delay(retryAfterSeconds * 1000L)
        return retryAfterSeconds
    }

    private fun responseTarget(response: Response<*>): String {
        val requestUrl = response.raw().request.url
        return requestUrl.host // Never log tokens, account paths, query strings or response bodies.
    }

    private suspend fun invalidateCredentials(statusCode: Int, profileId: Int) {
        authSessionNoticeDataStore.markTraktReconnectRequired()
        traktAuthDataStore.clearAuthForProfile(profileId)
        tripCircuit("Token refresh returned $statusCode")
    }
}
