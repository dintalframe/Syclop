package com.nuvio.tv.ui.screens.ambient

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AmbientIdleController internal constructor(private val now: () -> Long) {
    constructor() : this({ SystemClock.uptimeMillis() })

    private val _lastInteractionAt = MutableStateFlow(now())
    val lastInteractionAt: StateFlow<Long> = _lastInteractionAt.asStateFlow()

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private val authPauseLeases = AmbientAuthPauseLeases {
        _isShowing.value = false
        _lastInteractionAt.value = now()
    }
    val criticalAuthPaused: StateFlow<Boolean> = authPauseLeases.isPaused

    /** The caller must close this scope on completion, cancellation, viewer change or disposal. */
    fun acquireCriticalAuthLease(): AutoCloseable = authPauseLeases.acquire()

    private var swallowedKeyCode: Int? = null

    fun onInteraction() {
        _lastInteractionAt.value = now()
    }

    fun show() {
        authPauseLeases.runUnlessPaused { _isShowing.value = true }
    }

    fun dismiss() {
        _isShowing.value = false
        onInteraction()
    }

    fun consumeKeyEvent(event: KeyEvent): Boolean {
        onInteraction()
        val continuesSwallowedPress = event.keyCode == swallowedKeyCode &&
            (event.action == KeyEvent.ACTION_UP || event.repeatCount > 0)
        if (continuesSwallowedPress) {
            if (event.action == KeyEvent.ACTION_UP) swallowedKeyCode = null
            return true
        }
        swallowedKeyCode = null
        if (_isShowing.value && event.action == KeyEvent.ACTION_DOWN) {
            swallowedKeyCode = event.keyCode
            dismiss()
            return true
        }
        return false
    }
}
