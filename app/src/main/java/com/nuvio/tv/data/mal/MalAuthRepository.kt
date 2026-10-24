package com.nuvio.tv.data.mal

import android.content.Context
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface MalCallbackListener { fun start(); fun stop() }
internal fun interface MalCallbackListenerFactory {
    fun create(authority: String, onCallback: (String) -> Unit): MalCallbackListener
}

@Singleton
class MalAuthRepository internal constructor(
    val store: MalAuthStore,
    private val api: MalApiClient,
    private val localAddress: () -> String?,
    private val worker: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val elapsedMs: () -> Long,
    private val listeners: MalCallbackListenerFactory,
    private val diagnose: (MalAuthStage, Throwable?) -> Unit = MalAuthDiagnostics::event,
    private val liveSelection: (() -> Pair<Int, Long>)? = null
) {
    @Inject constructor(@ApplicationContext context: Context, store: MalAuthStore, api: MalApiClient, profiles: ProfileManager) : this(
        store, api, { DeviceIpAddress.get(context) }, CoroutineScope(SupervisorJob() + Dispatchers.IO),
        System::currentTimeMillis, { System.nanoTime() / 1_000_000L },
        MalCallbackListenerFactory { authority, callback -> MalLocalCallbackServer(authority, callback) },
        liveSelection = { profiles.activeProfileId.value to profiles.activeProfileSelectionRevision }
    )
    private val refreshLock = Mutex()
    private val sessionLock = Any()
    private var pending: Pending? = null
    private var server: MalCallbackListener? = null
    private var completionJob: Job? = null
    private var expiryJob: Job? = null
    private val mutableSession = MutableStateFlow<MalAuthorizationSession?>(null)
    val session = mutableSession.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    val state = store.state

    init {
        worker.launch {
            var previous: MalScope? = null
            state.map { it.scope }.distinctUntilChanged().collect { current ->
                synchronized(sessionLock) {
                    if (pending?.scope?.let { it != current } == true) cancelLocked(MalAuthStage.ACCOUNT_CHANGED)
                    else if (previous?.profileId?.let { it != current.profileId } == true) mutableNotice.value = null
                    previous = current
                }
            }
        }
    }

    fun defaultRedirectUri(): String? = state.value.configuration.redirectUri.takeIf(String::isNotBlank)
        ?: runCatching(localAddress).getOrNull()?.let { "http://$it:${MalRules.CALLBACK_PORT}${MalRules.CALLBACK_PATH}" }

    fun saveConfiguration(clientId: String, redirectUri: String) {
        val configuration = MalConfiguration(clientId.trim(), redirectUri.trim())
        MalRules.validateConfiguration(configuration)
        cancel(MalAuthStage.ACCOUNT_CHANGED)
        store.configure(configuration)
        mutableNotice.value = "MyAnimeList application settings saved on this device."
    }

    fun connect(): MalAuthorizationSession = synchronized(sessionLock) {
        cancelLocked(MalAuthStage.USER_CANCELLED)
        val account = state.value
        val selection = liveSelection?.invoke()
        require(selection == null || selection.first == account.scope.profileId) { "The active profile is still loading." }
        require(account.scope.profileId in 1..6) { "The active profile is still loading." }
        MalRules.validateConfiguration(account.configuration)
        val verifier = MalRules.randomUrlSafe(64)
        val localIp = runCatching(localAddress).getOrNull()
        val relayReturn = if (MalRelayState.isRegisteredRedirect(account.configuration.redirectUri)) {
            requireNotNull(MalRelayState.privateReturnUri(localIp)) { "This MyAnimeList callback needs the TV's current private IPv4 network address." }
        } else null
        val nonce = MalRules.randomUrlSafe(32)
        val csrfState = if (relayReturn != null) MalRelayState.create(nonce, relayReturn) else nonce
        val expires = nowEpochMs() + MalRules.AUTHORIZATION_DURATION_MS
        val request = Pending(account.scope, account.configuration, verifier, csrfState,
            elapsedMs() + MalRules.AUTHORIZATION_DURATION_MS, relayReturn, selection)
        var listening = false
        if (MalRules.isLocalCallback(account.configuration) || relayReturn != null) {
            val redirect = URI(relayReturn ?: account.configuration.redirectUri)
            require(redirect.host == localIp) { "The callback IP has changed. Register and save the current TV callback address first." }
            val listener = listeners.create(redirect.rawAuthority, ::submitCallback)
            try { listener.start(); server = listener; listening = true }
            catch (_: Exception) { listener.stop() }
        }
        pending = request
        val result = MalAuthorizationSession(api.authorizationUrl(account.configuration, csrfState, verifier),
            account.configuration.redirectUri, expires, listening)
        mutableSession.value = result
        mutableNotice.value = if (listening) "Scan the QR code on a phone connected to the same home network."
            else "The TV callback port is unavailable. Use Manual fallback after approving on your phone."
        diagnose(MalAuthStage.SCAN_CREATED, null)
        expiryJob = worker.launch {
            delay(MalRules.AUTHORIZATION_DURATION_MS)
            synchronized(sessionLock) { if (pending === request) cancelLocked(MalAuthStage.EXPIRED) }
        }
        result
    }

    fun submitCallback(returnedUri: String): Unit = synchronized(sessionLock) {
        val request = pending ?: error("Start a new MyAnimeList connection first.")
        require(validLocked(request)) { "The MyAnimeList connection expired or the active profile changed. Start again." }
        require(mutableSession.value?.completing != true) { "MyAnimeList sign-in is already completing." }
        val code = MalRules.callbackCode(returnedUri, request.configuration.redirectUri, request.state, request.localReturnUri)
        mutableSession.value = mutableSession.value?.copy(completing = true)
        diagnose(MalAuthStage.CALLBACK_RECEIVED, null)
        val job = worker.launch(start = CoroutineStart.LAZY) { complete(request, code) }
        completionJob = job
        job.start()
        Unit
    }

    private suspend fun complete(request: Pending, code: String) {
        var completedListener: MalCallbackListener? = null
        var stage = MalAuthStage.TOKEN_EXCHANGE
        try {
            if (!checkpoint(request)) return
            diagnose(stage, null)
            val tokens = api.exchange(request.configuration, code, request.verifier)
            if (!checkpoint(request)) return
            stage = MalAuthStage.ACCOUNT_READ
            diagnose(stage, null)
            val username = api.username(tokens.accessToken)
            if (!checkpoint(request)) return
            stage = MalAuthStage.PERSIST
            diagnose(stage, null)
            synchronized(sessionLock) {
                if (!validLocked(request)) return@synchronized
                if (store.complete(request.scope, tokens, username)) {
                    // Retire this request under the same session lock before the scope observer can
                    // see the successfully replaced grant. Its Connected notice must survive.
                    pending = null
                    mutableSession.value = null
                    completionJob = null
                    expiryJob?.cancel(); expiryJob = null
                    completedListener = server; server = null
                    mutableNotice.value = "Connected to MyAnimeList."
                    diagnose(MalAuthStage.CONNECTED, null)
                } else cancelLocked(MalAuthStage.ACCOUNT_CHANGED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnose(stage, failure)
            synchronized(sessionLock) {
                if (pending === request && store.isCurrent(request.scope)) {
                    mutableNotice.value = if (stage == MalAuthStage.PERSIST && !store.storageAvailable)
                        "MyAnimeList sign-in could not be saved. Restart Syclop and try again."
                    else if (stage == MalAuthStage.PERSIST)
                        "MyAnimeList sign-in could not be saved on this TV. Start a new connection and try again."
                    else "MyAnimeList sign-in failed. Start a new connection and try again."
                }
            }
        } finally {
            // Keep the generic received page alive long enough to finish; it never claims token persistence succeeded.
            try {
                if (currentCoroutineContext().isActive) delay(500)
            } finally {
                synchronized(sessionLock) { if (pending === request) cancelLocked() }
                completedListener?.stop()
            }
        }
    }

    private suspend fun checkpoint(request: Pending): Boolean {
        currentCoroutineContext().ensureActive()
        return synchronized(sessionLock) { validLocked(request) }
    }
    private fun validLocked(request: Pending): Boolean {
        if (pending !== request) return false
        if (!store.isCurrent(request.scope) || (request.selection != null && liveSelection?.invoke() != request.selection)) { cancelLocked(MalAuthStage.ACCOUNT_CHANGED); return false }
        if (elapsedMs() >= request.deadlineElapsedMs) { cancelLocked(MalAuthStage.EXPIRED); return false }
        return true
    }

    fun cancel() = cancel(MalAuthStage.USER_CANCELLED)
    internal fun cancel(reason: MalAuthStage) = synchronized(sessionLock) { cancelLocked(reason) }
    private fun cancelLocked(reason: MalAuthStage? = null) {
        if (pending != null && reason != null) {
            mutableNotice.value = when (reason) {
                MalAuthStage.EXPIRED -> "The MyAnimeList connection expired. Start again."
                MalAuthStage.ACCOUNT_CHANGED -> "The MyAnimeList connection was cancelled because the active account changed."
                MalAuthStage.SCREEN_CLOSED -> "The MyAnimeList connection was cancelled when its screen closed."
                else -> "The MyAnimeList connection was cancelled. Start again when ready."
            }
            diagnose(reason, null)
        }
        pending = null
        mutableSession.value = null
        completionJob?.cancel(); completionJob = null
        expiryJob?.cancel(); expiryJob = null
        server?.stop(); server = null
    }

    fun signOut(expected: MalScope = state.value.scope): Boolean = synchronized(sessionLock) {
        if (!store.isCurrent(expected) || !store.signOut(expected)) return@synchronized false
        cancelLocked()
        mutableNotice.value = "MyAnimeList disconnected on this profile. Other accounts are unchanged."
        true
    }
    suspend fun <T> authorized(expected: MalScope = state.value.scope, block: suspend (String) -> T): T {
        val selection = liveSelection?.invoke()
        fun current() = store.isCurrent(expected) && (selection == null ||
            (selection.first == expected.profileId && liveSelection?.invoke() == selection))
        require(current()) { "The active profile changed." }
        val token = accessToken(expected, selectionCurrent = ::current)
        require(current()) { "The active profile changed." }
        val result = try {
            block(token)
        } catch (error: MalApiException) {
            if (error.statusCode != 401) throw error
            require(current()) { "The active profile changed." }
            val refreshed = accessToken(expected, forceRefresh = true, failedAccessToken = token, selectionCurrent = ::current)
            require(current()) { "The active profile changed." }
            block(refreshed)
        }
        require(current()) { "The active profile changed." }
        return result
    }

    private suspend fun accessToken(expected: MalScope, forceRefresh: Boolean = false, failedAccessToken: String? = null, selectionCurrent: () -> Boolean = { true }): String = refreshLock.withLock {
        require(selectionCurrent() && store.isCurrent(expected)) { "The active profile changed." }
        val current = store.credentials(expected) ?: error("Connect MyAnimeList first.")
        if (!forceRefresh && current.expiresAtMs > System.currentTimeMillis() + 60_000) return@withLock current.accessToken
        // Reuse a concurrent successful refresh for this same grant rather than rotating twice.
        if (forceRefresh && failedAccessToken != null && current.accessToken != failedAccessToken) return@withLock current.accessToken
        val configuration = state.value.configuration
        val next = try { api.refresh(configuration, current) } catch (error: MalApiException) {
            if (error.statusCode in setOf(400, 401) && selectionCurrent()) store.signOutIfTokensCurrent(expected, current)
            throw error
        }
        require(selectionCurrent()) { "The active profile changed." }
        require(store.replaceTokens(expected, current, next)) { "The active MyAnimeList account changed." }
        next.accessToken
    }

    private data class Pending(
        val scope: MalScope, val configuration: MalConfiguration, val verifier: String,
        val state: String, val deadlineElapsedMs: Long, val localReturnUri: String? = null,
        val selection: Pair<Int, Long>? = null
    ) {
        override fun toString() = "MalAuthorizationPending([redacted])"
    }
}

private class MalLocalCallbackServer(
    private val expectedAuthority: String,
    private val callback: (String) -> Unit
) : NanoHTTPD(MalRules.CALLBACK_PORT), MalCallbackListener {
    override fun start() { super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
    override fun stop() { super.stop() }
    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET || session.uri != MalRules.CALLBACK_PATH ||
            session.headers["host"] != expectedAuthority) return response(Response.Status.NOT_FOUND, "Not found.")
        return try {
            callback("http://$expectedAuthority${MalRules.CALLBACK_PATH}?${session.queryParameterString.orEmpty()}")
            response(Response.Status.OK, "Authorization received. Check Syclop on your TV for the completed connection.")
        } catch (_: Exception) {
            response(Response.Status.BAD_REQUEST, "This authorization is invalid or expired. Start again on the TV.")
        }
    }
    private fun response(status: Response.Status, text: String): Response = newFixedLengthResponse(status,
        "text/html; charset=utf-8", "<!doctype html><meta name=viewport content='width=device-width'><title>Syclop</title><body style='background:#000;color:#eee;font:20px sans-serif;padding:40px'><h1>Syclop</h1><p>$text</p>")
        .also { it.addHeader("Cache-Control", "no-store"); it.addHeader("Referrer-Policy", "no-referrer") }
}
