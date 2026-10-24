package com.nuvio.tv.data.mal

import android.content.Context
import com.nuvio.tv.core.server.DeviceIpAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class MalAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: MalAuthStore,
    private val api: MalApiClient
) {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private val sessionLock = Any()
    private var pending: Pending? = null
    private var server: NanoHTTPD? = null
    private val mutableSession = MutableStateFlow<MalAuthorizationSession?>(null)
    val session = mutableSession.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    val state = store.state

    init {
        worker.launch {
            state.map { it.scope }.distinctUntilChanged().collect { current ->
                synchronized(sessionLock) {
                    if (pending?.scope?.let { it != current } == true) cancelLocked()
                }
            }
        }
    }

    fun defaultRedirectUri(): String? = state.value.configuration.redirectUri.takeIf(String::isNotBlank)
        ?: runCatching { DeviceIpAddress.get(context) }.getOrNull()?.let {
            "http://$it:${MalRules.CALLBACK_PORT}${MalRules.CALLBACK_PATH}"
        }

    fun saveConfiguration(clientId: String, redirectUri: String) {
        val configuration = MalConfiguration(clientId.trim(), redirectUri.trim())
        MalRules.validateConfiguration(configuration)
        cancel()
        store.configure(configuration)
        mutableNotice.value = "MyAnimeList application settings saved on this device."
    }

    fun connect(): MalAuthorizationSession = synchronized(sessionLock) {
        cancelLocked()
        val account = state.value
        MalRules.validateConfiguration(account.configuration)
        val verifier = MalRules.randomUrlSafe(64)
        val localIp = runCatching { DeviceIpAddress.get(context) }.getOrNull()
        val relayReturn = if (MalRelayState.isRegisteredRedirect(account.configuration.redirectUri)) {
            requireNotNull(MalRelayState.privateReturnUri(localIp)) { "This MyAnimeList callback needs the TV's current private IPv4 network address." }
        } else null
        val nonce = MalRules.randomUrlSafe(32)
        val csrfState = if (relayReturn != null) MalRelayState.create(nonce, relayReturn) else nonce
        val expires = System.currentTimeMillis() + MalRules.AUTHORIZATION_DURATION_MS
        val pendingRequest = Pending(account.scope, account.configuration, verifier, csrfState, expires, relayReturn)
        var listening = false
        if (MalRules.isLocalCallback(account.configuration) || relayReturn != null) {
            val redirect = URI(relayReturn ?: account.configuration.redirectUri)
            require(redirect.host == localIp) { "The callback IP has changed. Register and save the current TV callback address first." }
            val listener = CallbackServer(redirect.rawAuthority)
            try {
                listener.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                server = listener
                listening = true
            } catch (_: Exception) {
                listener.stop()
                mutableNotice.value = "The TV callback port is unavailable. You can enter the returned callback URL manually."
            }
        }
        pending = pendingRequest
        val result = MalAuthorizationSession(
            api.authorizationUrl(account.configuration, csrfState, verifier),
            account.configuration.redirectUri, expires, listening
        )
        mutableSession.value = result
        if (listening) mutableNotice.value = "Scan the QR code on a phone connected to the same home network."
        worker.launch {
            delay(MalRules.AUTHORIZATION_DURATION_MS)
            synchronized(sessionLock) {
                if (pending === pendingRequest) {
                    cancelLocked()
                    mutableNotice.value = "The MyAnimeList connection expired. Start again."
                }
            }
        }
        result
    }

    fun submitCallback(returnedUri: String) {
        val accepted = synchronized(sessionLock) {
            val current = pending ?: error("Start a new MyAnimeList connection first.")
            require(System.currentTimeMillis() <= current.expiresAtMs && store.isCurrent(current.scope)) {
                "The MyAnimeList connection expired. Start again."
            }
            require(mutableSession.value?.completing != true) { "MyAnimeList sign-in is already completing." }
            val code = MalRules.callbackCode(returnedUri, current.configuration.redirectUri, current.state, current.localReturnUri)
            mutableSession.value = mutableSession.value?.copy(completing = true)
            current to code
        }
        worker.launch {
            val (request, code) = accepted
            try {
                val tokens = api.exchange(request.configuration, code, request.verifier)
                val username = api.username(tokens.accessToken)
                synchronized(sessionLock) {
                    if (pending === request && System.currentTimeMillis() <= request.expiresAtMs &&
                        store.complete(request.scope, tokens, username)) mutableNotice.value = "Connected to MyAnimeList."
                }
            } catch (_: Exception) {
                synchronized(sessionLock) {
                    if (pending === request && store.isCurrent(request.scope))
                        mutableNotice.value = "MyAnimeList sign-in failed. Check the native app registration and redirect URI, then try again."
                }
            } finally {
                // Allow the callback's generic confirmation page to finish before closing its socket.
                delay(500)
                synchronized(sessionLock) { if (pending === request) cancelLocked() }
            }
        }
    }

    fun cancel() = synchronized(sessionLock) { cancelLocked() }
    private fun cancelLocked() {
        pending = null
        mutableSession.value = null
        server?.stop()
        server = null
    }

    fun signOut() {
        cancel()
        store.signOut()
        mutableNotice.value = "MyAnimeList disconnected on this profile. Other accounts are unchanged."
    }

    suspend fun <T> authorized(expected: MalScope = state.value.scope, block: suspend (String) -> T): T {
        val token = accessToken(expected)
        return try {
            block(token)
        } catch (error: MalApiException) {
            if (error.statusCode != 401) throw error
            val refreshed = accessToken(expected, forceRefresh = true)
            block(refreshed)
        }
    }

    private suspend fun accessToken(expected: MalScope, forceRefresh: Boolean = false): String = refreshLock.withLock {
        require(store.isCurrent(expected)) { "The active profile changed." }
        val current = store.credentials(expected) ?: error("Connect MyAnimeList first.")
        if (!forceRefresh && current.expiresAtMs > System.currentTimeMillis() + 60_000) return@withLock current.accessToken
        val configuration = state.value.configuration
        val next = try { api.refresh(configuration, current) } catch (error: MalApiException) {
            if (error.statusCode in setOf(400, 401)) store.signOut(expected)
            throw error
        }
        require(store.replaceTokens(expected, next)) { "The active MyAnimeList account changed." }
        next.accessToken
    }

    private inner class CallbackServer(private val expectedAuthority: String) : NanoHTTPD(MalRules.CALLBACK_PORT) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET || session.uri != MalRules.CALLBACK_PATH ||
                session.headers["host"] != expectedAuthority) return response(Response.Status.NOT_FOUND, "Not found.")
            return try {
                // The raw callback URL is used only for exact URI/state validation and token exchange.
                submitCallback("http://$expectedAuthority${MalRules.CALLBACK_PATH}?${session.queryParameterString.orEmpty()}")
                response(Response.Status.OK, "Authorization received. Return to Syclop on your TV.")
            } catch (_: Exception) {
                response(Response.Status.BAD_REQUEST, "This authorization is invalid or expired. Start again on the TV.")
            }
        }
        private fun response(status: Response.Status, text: String): Response = newFixedLengthResponse(status,
            "text/html; charset=utf-8", "<!doctype html><meta name=viewport content='width=device-width'><title>Syclop</title><body style='background:#000;color:#eee;font:20px sans-serif;padding:40px'><h1>Syclop</h1><p>$text</p>")
            .also { it.addHeader("Cache-Control", "no-store"); it.addHeader("Referrer-Policy", "no-referrer") }
    }

    private data class Pending(
        val scope: MalScope, val configuration: MalConfiguration, val verifier: String,
        val state: String, val expiresAtMs: Long, val localReturnUri: String? = null
    ) {
        override fun toString() = "MalAuthorizationPending([redacted])"
    }
}
