package com.nuvio.tv.core.player

import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry

/** Each physical Android handoff keeps its own result key, including after Activity recreation. */
internal class ExternalPlayerResultBroker(
    val registry: ActivityResultRegistry,
    initiallyActive: Boolean = true,
    private val deferCleanup: ((() -> Unit) -> Unit) = ::deferExternalResultCleanup,
    private val onBuffered: (String, ExternalPlayerResult?) -> Unit = { _, _ -> },
    private val onResult: (String, ExternalPlayerResult?) -> Unit
) {
    private class Registration {
        var active = true
        var launched = false
        var received = false
        var hasPending = false
        var pendingResult: ExternalPlayerResult? = null
        var launcher: ActivityResultLauncher<ExternalPlayerInput>? = null
    }

    private val registrations = mutableMapOf<String, Registration>()
    private var closed = false
    private var active = initiallyActive
    private var dispatchDepth = 0

    /** Deliver buffered results only when the host Activity is foregrounded. */
    fun activate() {
        if (closed) return
        active = true
        registrations.toMap().forEach { (id, registration) ->
            if (registration.active && registration.launcher == null) attach(id, registration)
            if (registration.active && registration.hasPending) {
                val result = registration.pendingResult
                registration.hasPending = false
                registration.pendingResult = null
                onResult(id, result)
            }
        }
    }

    fun deactivate() {
        active = false
    }

    /** Re-register the persisted key without sending another external intent. */
    fun recover(handoffId: String, buffered: BufferedExternalPlayerResult? = null) {
        register(handoffId, buffered)
    }

    fun launch(handoffId: String, input: ExternalPlayerInput) {
        check(active) { "External result registry is not foregrounded" }
        val registration = register(handoffId)
        check(registration.active && !registration.launched) { "External handoff has already launched or completed" }
        registration.launched = true
        try {
            checkNotNull(registration.launcher).launch(input)
        } catch (failure: Exception) {
            retire(handoffId)
            throw failure
        }
    }

    fun retire(handoffId: String) {
        val registration = registrations.remove(handoffId) ?: return
        registration.active = false
        val launcher = registration.launcher
        registration.launcher = null
        if (launcher != null) {
            // AndroidX removes launchedKeys after invoking its callback. Unregistering inside
            // that callback would retain its request-code mapping indefinitely.
            if (dispatchDepth > 0) deferCleanup { launcher.unregister() } else launcher.unregister()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        registrations.keys.toList().forEach(::retire)
    }

    private fun register(handoffId: String, buffered: BufferedExternalPlayerResult? = null): Registration {
        check(!closed) { "External result registry is no longer attached" }
        require(handoffId.matches(HANDOFF_ID)) { "Invalid external handoff identity" }
        registrations[handoffId]?.let { registration ->
            if (registration.launcher == null) attach(handoffId, registration)
            return registration
        }
        val registration = Registration()
        if (buffered != null) {
            registration.received = true
            registration.hasPending = true
            registration.pendingResult = buffered.result
        }
        registrations[handoffId] = registration
        attach(handoffId, registration)
        return registration
    }

    private fun attach(handoffId: String, registration: Registration) {
        try {
            registration.launcher = registry.register(KEY_PREFIX + handoffId, ExternalPlayerResultContract()) { result ->
                // A parsed callback can already be queued when its watchdog retires the handoff.
                if (registration.active && !closed && !registration.received) {
                    registration.received = true
                    dispatchDepth++
                    try {
                        if (active) onResult(handoffId, result)
                        else {
                            registration.hasPending = true
                            registration.pendingResult = result
                            onBuffered(handoffId, result)
                        }
                    } finally { dispatchDepth-- }
                }
            }
            // register() may synchronously deliver a restored pending result and retire this key.
            if (!registration.active) {
                registration.launcher?.unregister()
                registration.launcher = null
            }
        } catch (failure: Exception) {
            registrations.remove(handoffId)
            registration.active = false
            throw failure
        }
    }

    private companion object {
        const val KEY_PREFIX = "syclop.external-player."
        val HANDOFF_ID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    }
}

/** Wrapper distinguishes a received null/cancel result from no result received. */
internal data class BufferedExternalPlayerResult(val result: ExternalPlayerResult?)

private fun deferExternalResultCleanup(action: () -> Unit) {
    Handler(Looper.getMainLooper()).post { action() }
}
