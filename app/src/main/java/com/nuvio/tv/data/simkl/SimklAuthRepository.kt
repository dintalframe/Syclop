package com.nuvio.tv.data.simkl

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Singleton
class SimklAuthRepository internal constructor(
    private val apiClient: SimklApiClient,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val oauth: SimklV2OAuthClient,
    private val nowEpochMs: () -> Long,
    private val monotonicMillis: () -> Long,
    private val revokeScope: CoroutineScope
) {
    @Inject constructor(apiClient: SimklApiClient, configuration: SimklApiConfiguration,
        storage: SimklAuthStorage, oauth: SimklV2OAuthClient) : this(
        apiClient, configuration, storage, oauth, System::currentTimeMillis,
        { System.nanoTime() / 1_000_000 }, CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var pending: Pending? = null
    val state: StateFlow<SimklAuthState> = storage.state
    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()
    fun currentScope(): SimklAuthScope = storage.currentScope()
    fun isCurrentScope(scope: SimklAuthScope): Boolean = storage.isCurrent(scope)

    // UI name kept for compatibility; this is AUTH V2's standard device flow, not V1 GET /oauth/pin.
    suspend fun startPinAuth(): SimklPinSession = mutex.withLock {
        cancelPinAuth()
        val scope = storage.currentScope()
        if (scope.profileId !in 1..6) throw CancellationException("Simkl profile is loading")
        if (!hasRequiredCredentials()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        val response = oauth.start()
        if (!storage.isCurrent(scope)) throw CancellationException("Simkl account changed")
        if (response.status !in 200..299) throw SimklAuthException(
            if (response.oauthError() == "invalid_client") SimklAuthError.MISSING_CLIENT_ID else SimklAuthError.NETWORK
        )
        val payload = runCatching { json.decodeFromString<SimklV2DeviceResponse>(response.body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE) }
        val code = payload.userCode?.takeIf { it.matches(Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}")) }
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        val deviceCode = payload.deviceCode?.takeIf { it.isNotBlank() && it.length <= 4096 && it.none { c -> c <= ' ' } }
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        val seconds = payload.expiresIn?.takeIf { it in 1L..900L }
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        val interval = payload.interval?.takeIf { it in 1..300 }?.coerceAtLeast(5)
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        val base = payload.verificationUri?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host == "simkl.com" && it.port == 443 && it.username.isEmpty() &&
                it.password.isEmpty() && it.encodedPath == "/pin" && it.fragment == null && it.query == null
        } ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        val now = nowEpochMs()
        if (now < 0 || now > Long.MAX_VALUE - seconds * 1000L) throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        val session = SimklPinSession(code, base.toString(), now + seconds * 1000, interval,
            base.newBuilder().addQueryParameter("user_code", code).build().toString())
        if (!storage.savePinSession(session, scope)) throw CancellationException("Simkl account changed")
        pending = Pending(scope, deviceCode, monotonicMillis() + seconds * 1000L,
            monotonicMillis() + interval * 1000L, interval)
        session
    }

    suspend fun pollPin(): SimklPinPollResult = mutex.withLock {
        val flow = pending ?: return@withLock SimklPinPollResult.Invalidated
        if (!storage.isCurrent(flow.scope)) { pending = null; return@withLock SimklPinPollResult.Invalidated }
        if (monotonicMillis() >= flow.deadline) return@withLock expire(flow)
        if (monotonicMillis() < flow.nextPollAt) return@withLock SimklPinPollResult.Pending
        pending = flow.copy(nextPollAt = monotonicMillis() + flow.intervalSeconds * 1000L)
        val response = oauth.poll(flow.deviceCode)
        if (!storage.isCurrent(flow.scope) || pending == null) return@withLock SimklPinPollResult.Invalidated
        if (monotonicMillis() >= flow.deadline) return@withLock expire(flow)
        if (response.status in 200..299) {
            val tokens = try { response.v2Tokens(nowEpochMs()) }
            catch (error: SimklAuthException) {
                pending = null
                storage.clearPinSession(error.error, flow.scope)
                throw error
            }
            if (!storage.saveV2Tokens(tokens, flow.scope)) return@withLock SimklPinPollResult.Invalidated
            pending = null
            val authorizedScope = flow.scope.copy(generation = flow.scope.generation + 1)
            if (!storage.isCurrent(authorizedScope)) return@withLock SimklPinPollResult.Invalidated
            try { refreshUserSettings(scope = authorizedScope) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { Unit }
            return@withLock SimklPinPollResult.Authorized
        }
        when (response.oauthError()) {
            "authorization_pending" -> SimklPinPollResult.Pending // Denial also remains pending until expiry.
            "slow_down" -> {
                val interval = (flow.intervalSeconds + 5).coerceAtMost(300)
                pending = flow.copy(intervalSeconds = interval, nextPollAt = monotonicMillis() + interval * 1000L)
                storage.state.value.pinSession?.let { storage.savePinSession(it.copy(intervalSeconds = interval), flow.scope) }
                SimklPinPollResult.Pending
            }
            "expired_token" -> expire(flow)
            "invalid_client" -> {
                pending = null
                storage.clearPinSession(SimklAuthError.MISSING_CLIENT_ID, flow.scope)
                throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
            }
            "access_denied" -> { // Defensive terminal handling if the service adds RFC8628 denial later.
                pending = null
                storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, flow.scope)
                SimklPinPollResult.Invalidated
            }
            else -> throw SimklAuthException(SimklAuthError.NETWORK)
        }
    }

    private fun expire(flow: Pending): SimklPinPollResult {
        pending = null
        storage.clearPinSession(SimklAuthError.PIN_EXPIRED, flow.scope)
        return SimklPinPollResult.Expired
    }

    fun cancelPinAuth() {
        pending = null
        storage.clearPinSession()
    }

    // Clear local credentials synchronously; revocation is best-effort for this captured grant only.
    fun disconnect(expectedScope: SimklAuthScope = storage.currentScope()): SimklAuthScope? {
        if (!storage.isCurrent(expectedScope)) return null
        val auth = storage.oauthAuthorization()
        if (auth != null && auth.scope != expectedScope) return null
        if (!storage.clearAuth(scope = expectedScope, expectedAccessToken = auth?.tokens?.accessToken)) return null
        pending = null
        if (auth != null) revokeScope.launch {
            try { oauth.revoke(auth.tokens.refreshToken) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { Unit }
        }
        // V2 clearAuth atomically advances this scope once. The caller must still
        // check it after suspension before publishing any viewer-specific UI result.
        return expectedScope.copy(generation = expectedScope.generation + 1)
    }

    private data class Pending(val scope: SimklAuthScope, val deviceCode: String, val deadline: Long,
        val nextPollAt: Long, val intervalSeconds: Int) {
        override fun toString() = "SimklPendingDeviceAuthorization(scope=$scope)"
    }

    suspend fun refreshUserSettings(
        activityWatermark: String? = null,
        scope: SimklAuthScope = storage.currentScope()
    ): String? {
        if (!storage.isCurrent(scope) || !storage.state.value.isAuthenticated) return null
        val response = apiClient.execute(
            request = SimklApiRequest(SimklHttpMethod.GET, "/users/settings"),
            expectedAuthScope = scope
        )
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }.getOrNull()
            ?: return null
        val username = settings.user?.name?.trim()?.takeIf(String::isNotBlank)
        val saved = storage.saveIdentity(
            username = username,
            accountId = settings.account?.id,
            settingsActivityWatermark = activityWatermark,
            scope = scope
        )
        return username.takeIf { saved }
    }

    suspend fun synchronizeUserSettings(activityWatermark: String?) {
        val authScope = storage.currentScope()
        if (!storage.state.value.isAuthenticated) return
        try {
            when (simklSettingsRefreshAction(storage.state.value, activityWatermark)) {
                SimklSettingsRefreshAction.NONE -> Unit
                SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                    storage.recordSettingsActivityWatermark(requireNotNull(activityWatermark), authScope)
                }
                SimklSettingsRefreshAction.FETCH -> refreshUserSettings(activityWatermark, authScope)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

}
