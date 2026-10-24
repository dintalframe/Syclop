package com.nuvio.tv.core.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Owns only the explicit manual-key dialog operation; closing it invalidates a late response. */
class DebridManualCredentialSaver(
    private val scope: CoroutineScope,
    private val captureScope: () -> DebridAuthProfileGuard.Ticket,
    private val isCurrent: (DebridAuthProfileGuard.Ticket) -> Boolean,
    private val credential: suspend (Int, String) -> String,
    private val validate: suspend (String, String) -> Boolean,
    private val commit: suspend (Int, String, String, String?) -> Boolean,
    private val setBusy: (Boolean) -> Unit,
    private val onFailure: (Failure) -> Unit
) {
    enum class Failure { INVALID_KEY, PROFILE_CHANGED }
    private var job: Job? = null
    private var generation = 0L

    fun submit(providerId: String, value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        // Clear is an explicit replacement of the pending save, never a silent no-op.
        if (trimmed.isNotBlank() && job?.isActive == true) return
        cancel()
        val operation = generation
        val ticket = captureScope()
        setBusy(true)
        job = scope.launch {
            suspend fun current(): Boolean {
                currentCoroutineContext().ensureActive()
                return operation == generation && isCurrent(ticket)
            }
            try {
                if (!current()) { onFailure(Failure.PROFILE_CHANGED); return@launch }
                val expected = if (trimmed.isBlank()) null else credential(ticket.profileId, providerId)
                if (!current()) { onFailure(Failure.PROFILE_CHANGED); return@launch }
                if (trimmed.isNotBlank() && !validate(providerId, trimmed)) {
                    if (current()) onFailure(Failure.INVALID_KEY)
                    return@launch
                }
                if (!current()) { onFailure(Failure.PROFILE_CHANGED); return@launch }
                val saved = commit(ticket.profileId, providerId, trimmed, expected)
                if (!current()) return@launch
                if (saved) onSuccess() else onFailure(Failure.PROFILE_CHANGED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current()) onFailure(Failure.INVALID_KEY)
            } finally {
                if (operation == generation) { job = null; setBusy(false) }
            }
        }
    }

    fun cancel() {
        generation += 1L
        job?.cancel()
        job = null
        setBusy(false)
    }
}
