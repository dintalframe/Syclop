package com.nuvio.tv.core.mediafacts

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A stuck platform extractor cannot spawn additional threads or delay the UI's timeout. */
internal class SelectedSourceProbeWorker {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "SyclopSelectedMetadata").apply { isDaemon = true } })

    suspend fun <T> run(timeoutMillis: Long, cancel: () -> Unit, work: () -> T): T? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<T?> { continuation ->
                val future = AtomicReference<Future<*>?>(null)
                continuation.invokeOnCancellation {
                    cancel()
                    future.get()?.cancel(true)
                    (future.get() as? Runnable)?.let(executor::remove)
                }
                try {
                    val submitted = executor.submit {
                        try {
                            val result = work()
                            if (continuation.isActive) continuation.resume(result)
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resume(null)
                        } catch (_: LinkageError) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    future.set(submitted)
                    if (!continuation.isActive) { cancel(); future.get()?.cancel(true); (future.get() as? Runnable)?.let(executor::remove) }
                } catch (_: RejectedExecutionException) {
                    cancel()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
}
