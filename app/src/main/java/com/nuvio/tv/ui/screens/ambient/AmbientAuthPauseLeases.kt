package com.nuvio.tv.ui.screens.ambient

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory critical-auth scopes. Closing one scope cannot release another scope's pause. */
internal class AmbientAuthPauseLeases(private val onPauseChanged: (Boolean) -> Unit) {
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    private val owners = mutableSetOf<Any>()

    @Synchronized
    fun acquire(): AutoCloseable {
        val owner = Any()
        if (owners.isEmpty()) {
            owners += owner
            _isPaused.value = true
            onPauseChanged(true)
        } else owners += owner
        return AutoCloseable { release(owner) }
    }

    @Synchronized
    private fun release(owner: Any) {
        if (!owners.remove(owner)) return
        if (owners.isEmpty()) {
            // Reset the idle deadline before observers are allowed to start a new timer.
            onPauseChanged(false)
            _isPaused.value = false
        }
    }

    /** Serializes a timer firing against acquire, covering a timer already ready to resume. */
    @Synchronized
    fun runUnlessPaused(action: () -> Unit): Boolean {
        if (owners.isNotEmpty()) return false
        action()
        return true
    }
}
