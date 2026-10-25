package com.nuvio.tv.core.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep the existing internal player intact until an external launch has actually succeeded. */
internal object ExternalPlayerHandoff {
    suspend fun run(
        isCurrent: () -> Boolean,
        preflight: suspend () -> Boolean,
        prepare: suspend () -> Unit,
        launch: suspend () -> Boolean,
        releaseInternal: () -> Unit
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!isCurrent() || !preflight() || !isCurrent()) return false
        prepare()
        currentCoroutineContext().ensureActive()
        if (!isCurrent() || !launch() || !isCurrent()) return false
        releaseInternal()
        return true
    }
}
