@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.media.Image
import android.os.Debug
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Fixed owned-emulator screening gates, not a configurable benchmark. */
internal object OwnedPlaybackSoakChecks {
    const val DURATION_MS = 7_200_000L
    const val HEALTHY_MS = 6_840_000L
    const val OUTER_MS = 8_100_000L
    val caps = mapOf("pssKiB" to 24_576L, "nativeHeapKiB" to 12_288L, "fds" to 3L, "threads" to 3L)
    fun resources(): JSONObject {
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject().put("pssKiB", memory.totalPss.toLong())
            .put("nativeHeapKiB", Debug.getNativeHeapAllocatedSize() / 1024L)
            .put("fds", requireNotNull(File("/proc/self/fd").list()).size.toLong())
            .put("threads", requireNotNull(File("/proc/self/task").list()).size.toLong())
            .also { row -> caps.keys.forEach { check(row.getLong(it) > 0L) { "Missing owned resource metric: $it" } } }
    }
    fun requireObservation(ownerMatches: Boolean, gapMs: Long, outputAdvanced: Boolean) {
        check(ownerMatches && gapMs in 1L..5_000L && outputAdvanced) { "Owned soak observation lost ownership, time or output." }
    }
    fun requireDuration(elapsed: Long, uptime: Long, healthy: Long, seeks: Int, recreations: Int) {
        check(elapsed >= DURATION_MS && uptime >= DURATION_MS && healthy >= HEALTHY_MS && seeks == 120 && recreations == 23) {
            "Owned soak fixed duration/workload/healthy-output gate failed."
        }
    }
    fun requireGrowth(postWarmup: List<JSONObject>): JSONObject {
        check(postWarmup.size >= 10) { "Insufficient settled post-warmup observations." }
        return JSONObject().also { result -> caps.forEach { (key, cap) ->
            fun median(rows: List<JSONObject>) = rows.map { it.getLong(key).also { value -> check(value > 0L) } }.sorted()[2]
            val before = median(postWarmup.take(5)); val after = median(postWarmup.takeLast(5))
            check(after - before <= cap) { "Owned settled resource growth exceeded $key cap." }
            result.put(key, JSONObject().put("firstMedian", before).put("lastMedian", after).put("growth", after - before).put("cap", cap))
        } }
    }
    /** Hand-fixed negative controls run before any soak clock; none can satisfy the full run. */
    fun selfChecks() {
        fun rejects(block: () -> Unit) { check(runCatching(block).exceptionOrNull() is IllegalStateException) }
        requireDuration(DURATION_MS, DURATION_MS, HEALTHY_MS, 120, 23)
        rejects { requireDuration(DURATION_MS - 1, DURATION_MS, HEALTHY_MS, 120, 23) }
        rejects { requireDuration(DURATION_MS, DURATION_MS - 1, HEALTHY_MS, 120, 23) }
        rejects { requireDuration(DURATION_MS, DURATION_MS, HEALTHY_MS - 1, 120, 23) }
        rejects { requireDuration(DURATION_MS, DURATION_MS, HEALTHY_MS, 119, 23) }
        rejects { requireDuration(DURATION_MS, DURATION_MS, HEALTHY_MS, 120, 22) }
        requireObservation(true, 1_000L, true)
        rejects { requireObservation(false, 1_000L, true) }
        rejects { requireObservation(true, 1_000L, false) }
        rejects { requireObservation(true, 5_001L, true) }
        fun row(extra: Long = 0L) = JSONObject().also { result -> caps.keys.forEach { result.put(it, 10L + extra) } }
        requireGrowth(List(10) { row() })
        rejects { requireGrowth(List(9) { row() }) }
        rejects { requireGrowth(List(10) { row().put("pssKiB", 0L) }) }
        caps.forEach { (key, cap) ->
            requireGrowth(List(5) { row() } + List(5) { row().put(key, 10L + cap) })
            rejects { requireGrowth(List(5) { row() } + List(5) { row().put(key, 10L + cap + 1L) }) }
        }
    }
}

/** Acquired ImageReader images are closed serially; no decoder/GPU buffer enumeration claim. */
internal class OwnedSoakImages(private val output: AtomicInteger) {
    private val acquired = AtomicInteger()
    private val closed = AtomicInteger()
    private val outstanding = AtomicInteger()
    private val maximum = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    fun close(image: Image) {
        acquired.incrementAndGet()
        val active = outstanding.incrementAndGet()
        maximum.accumulateAndGet(active) { before, now -> maxOf(before, now) }
        try { check(active <= 1) { "Owned image callback retained multiple acquired images." } }
        finally {
            image.close()
            closed.incrementAndGet()
            outstanding.decrementAndGet()
            output.incrementAndGet()
        }
    }
    fun requireBalanced() {
        check(failure.get() == null) { "Owned image callback failed." }
        check(outstanding.get() == 0 && acquired.get() == closed.get() && maximum.get() <= 1) { "Owned image close balance failed." }
    }
    fun snapshot() = JSONObject().put("acquired", acquired.get()).put("closed", closed.get())
        .put("outstanding", outstanding.get()).put("maxOutstanding", maximum.get())
        .put("callbackFailureClass", failure.get()?.javaClass?.name ?: JSONObject.NULL)
}
