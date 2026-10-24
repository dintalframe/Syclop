package com.nuvio.tv.ui.screens.ambient

import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

val LocalAmbientIdleController = staticCompositionLocalOf<AmbientIdleController?> { null }

@Composable
fun AmbientDialogEffect(onDismiss: () -> Unit, criticalAuthActive: Boolean = false) {
    val controller = LocalAmbientIdleController.current
    val dismiss by rememberUpdatedState(onDismiss)
    val critical by rememberUpdatedState(criticalAuthActive)
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window

    DisposableEffect(controller, view, criticalAuthActive) {
        val lease = if (criticalAuthActive) controller?.acquireCriticalAuthLease() else null
        val previouslyKeptScreenOn = view.keepScreenOn
        if (criticalAuthActive) view.keepScreenOn = true
        onDispose {
            if (criticalAuthActive) view.keepScreenOn = previouslyKeptScreenOn
            lease?.close()
        }
    }

    DisposableEffect(window, controller) {
        val original = window?.callback
        if (window != null && original != null) {
            window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    controller?.onInteraction()
                    return original.dispatchKeyEvent(event)
                }
            }
        }
        onDispose {
            if (window != null && original != null) window.callback = original
        }
    }

    LaunchedEffect(controller) {
        controller?.isShowing?.collect { showing -> if (showing && !critical) dismiss() }
    }
}
