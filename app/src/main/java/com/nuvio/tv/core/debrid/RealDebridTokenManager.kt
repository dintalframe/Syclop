package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.RealDebridAuthStateStore
import com.nuvio.tv.data.local.RealDebridAuthStore
import com.nuvio.tv.data.remote.api.RealDebridOAuthApi
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response

/** A separate OAuth transport avoids recursive authentication. Refreshes are single-flight. */
@Singleton
class RealDebridTokenManager internal constructor(
    private val api: RealDebridOAuthApi,
    private val store: RealDebridAuthStateStore,
    private val clock: () -> Long
) {
    @Inject constructor(api: RealDebridOAuthApi, store: RealDebridAuthStore) : this(api, store, System::currentTimeMillis)
    private val mutex = Mutex()
    // Requests in one source-resolution session can still contain an earlier token snapshot.
    // Aliases stay in memory only, bounded, and are always checked against the current viewer.
    private data class Owner(val profileId: Int, val clientId: String?)
    private val tokenOwners = linkedMapOf<String, Owner>()

    suspend fun authorizationToken(token: String, unauthorized: Boolean = false): String = mutex.withLock {
        val pid = store.activeProfileId
        val owner = tokenOwners[token]
        if (owner != null && owner.profileId != pid) throw IOException("The active viewer changed. Try again.")
        val storedOwner = if (owner == null || owner.clientId == null) store.ownerOfToken(token) else null
        if (storedOwner != null && storedOwner != pid) throw IOException("The active viewer changed. Try again.")
        val record = store.read(pid) ?: run {
            if (owner?.clientId != null || (owner != null && storedOwner != pid)) throw IOException("The account changed. Try again.")
            if (storedOwner != pid) throw IOException("This account is not connected for the active viewer.")
            if (storedOwner == pid) remember(token, pid, null)
            if (store.activeProfileId != pid) throw IOException("The active viewer changed. Try again.")
            return@withLock token // Manually entered keys have no OAuth state.
        }
        val state = record.state
        if (owner?.clientId != null && owner.clientId != state.clientId) throw IOException("The account changed. Try again.")
        val known = token == state.accessToken || (owner?.clientId != null && owner.clientId == state.clientId)
        if (store.activeProfileId != pid) throw IOException("The active viewer changed. Try again.")
        if (!known) {
            throw IOException("This account is not connected for the active viewer.")
        }
        remember(state.accessToken, pid, state.clientId)
        if (unauthorized && token != state.accessToken) return@withLock state.accessToken // Another request refreshed it.
        if (!unauthorized && state.expiresAtEpochMillis - clock() > 30_000L) return@withLock state.accessToken
        try {
            val response = api.token(state.clientId, state.clientSecret, state.refreshToken)
            val body = response.body()?.takeIf { response.isSuccessful }
            response.errorBody()?.close()
            val access = body?.accessToken?.takeIf { it.isNotBlank() }
                ?: throw IOException("Real-Debrid sign-in needs attention. Reconnect the account.")
            val refresh = body.refreshToken?.takeIf { it.isNotBlank() }
                ?: throw IOException("Real-Debrid returned an incomplete sign-in response.")
            val expiry = body.expiresIn?.takeIf { it in 1..31_536_000 }
                ?: throw IOException("Real-Debrid returned an invalid sign-in expiry.")
            if (body.tokenType?.equals("Bearer", true) == false) throw IOException("Real-Debrid returned an unsupported sign-in response.")
            val replacement = state.copy(accessToken = access, refreshToken = refresh,
                expiresAtEpochMillis = clock() + expiry * 1000L)
            if (!store.replace(pid, state.accessToken, replacement)) throw IOException("The active viewer or account changed. Try again.")
            remember(token, pid, state.clientId)
            remember(access, pid, state.clientId)
            access
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IOException("Real-Debrid sign-in could not be refreshed. Reconnect or try again.")
        }
    }

    private fun remember(token: String, pid: Int, clientId: String?) {
        if (tokenOwners.size >= 64) tokenOwners.remove(tokenOwners.keys.first())
        tokenOwners[token] = Owner(pid, clientId)
    }
}

/** At most one 401 retry. Only the fixed RD REST transport installs this interceptor. */
class RealDebridAuthorizationInterceptor(private val tokens: RealDebridTokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = original.header("Authorization")?.takeIf { it.startsWith("Bearer ", true) }
            ?.substring(7)?.takeIf { it.isNotBlank() } ?: return chain.proceed(original)
        val prepared = runBlocking(Dispatchers.IO) { tokens.authorizationToken(token) }
        val request = original.newBuilder().header("Authorization", "Bearer $prepared").build()
        val response = chain.proceed(request)
        if (response.code != 401) return response
        val refreshed = try {
            runBlocking(Dispatchers.IO) { tokens.authorizationToken(prepared, unauthorized = true) }
        } catch (_: IOException) { return response }
        if (refreshed == prepared) return response // A manual key cannot be refreshed.
        response.close()
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $refreshed").build())
    }
}
