package com.nuvio.tv.data.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exactly one refresh owner: refreshing immediately invalidates this grant's old access token. */
class SimklV2TokenManager(
    private val storage: SimklAuthStorage,
    private val oauth: SimklV2OAuthClient,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    suspend fun authorization(expected: SimklAuthorization, forceRefresh: Boolean): SimklAuthorization = mutex.withLock {
        if (!storage.isCurrent(expected.scope)) changed()
        val current = storage.oauthAuthorization()?.takeIf { it.scope == expected.scope } ?: revoked()
        // Another call has already refreshed this grant; reuse the newly stored token.
        if (current.tokens.accessToken != expected.accessToken) return@withLock current.asAuthorization()
        val now = nowEpochMs()
        if (!forceRefresh && current.tokens.expiresAtEpochMs - now > 5 * 60_000L) return@withLock current.asAuthorization()
        if (current.tokens.refreshExpiresAtEpochMs <= now) {
            storage.clearAuth(SimklAuthError.AUTHORIZATION_REVOKED, current.scope, current.tokens.accessToken)
            revoked()
        }
        val response = oauth.refresh(current.tokens.refreshToken)
        if (!storage.isCurrent(current.scope)) changed()
        if (response.status !in 200..299) {
            if (response.oauthError() in setOf("invalid_grant", "invalid_token")) {
                storage.clearAuth(SimklAuthError.AUTHORIZATION_REVOKED, current.scope, current.tokens.accessToken)
                revoked()
            }
            // Network, rate limits and invalid registration must not erase a usable credential.
            throw SimklAuthException(if (response.oauthError() == "invalid_client")
                SimklAuthError.MISSING_CLIENT_ID else SimklAuthError.NETWORK)
        }
        val tokens = response.v2Tokens(nowEpochMs())
        if (!storage.saveV2Tokens(tokens, current.scope, current.tokens.accessToken)) changed()
        storage.authorization()?.takeIf { it.scope == current.scope } ?: changed()
    }

    private fun SimklV2Authorization.asAuthorization() = SimklAuthorization(scope, tokens.accessToken)
    private fun changed(): Nothing = throw CancellationException("Simkl account changed")
    private fun revoked(): Nothing = throw SimklAuthException(SimklAuthError.AUTHORIZATION_REVOKED)
}
