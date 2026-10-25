package com.nuvio.tv.core.mediafacts

import com.nuvio.tv.core.streams.SyclopPlaybackPolicy
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/** Bounded random-access reads of one already-authorized file. No provider resolution or API keys. */
internal class SelectedFileRangeReader(
    private val url: String,
    private val stillAuthorized: () -> Boolean,
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build(),
    private val nowNanos: () -> Long = System::nanoTime,
    private val budgetNanos: Long = TimeUnit.SECONDS.toNanos(4),
    private val maxRequests: Int = 16,
    private val maxBytes: Long = 2L * 1024L * 1024L,
    private val chunkBytes: Int = 128 * 1024,
    private val allowLocalHttpForTests: Boolean = false
) : Closeable {
    init {
        require(budgetNanos in 1L..TimeUnit.SECONDS.toNanos(4) && maxRequests in 1..16 && maxBytes in 1L..2L*1024L*1024L && chunkBytes in 1..128*1024)
    }
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).build()
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicReference<Call?>(null)
    private val started = nowNanos()
    private val cache = linkedMapOf<Long, ByteArray>()
    private var validator: String? = null
    private var validatorHeader: String? = null
    @Volatile var knownSize: Long? = null; private set
    @Volatile var requestCount: Int = 0; private set
    @Volatile var bodyBytes: Long = 0; private set

    fun prime() { val byte = ByteArray(1); readAt(0, byte, 0, 1) }

    @Synchronized fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) fail()
        if (size == 0) return 0
        checkScope()
        if (knownSize?.let { position >= it } == true) return -1
        val start = position / chunkBytes * chunkBytes
        val bytes = cache[start] ?: fetch(start).also { cache[start] = it }
        checkScope()
        val from = (position - start).toInt()
        if (from >= bytes.size) return -1
        val count = minOf(size, bytes.size - from)
        bytes.copyInto(buffer, offset, from, from + count)
        return count
    }

    private fun fetch(start: Long): ByteArray {
        checkScope()
        val parsed = url.toHttpUrlOrNull() ?: fail()
        val localTest = allowLocalHttpForTests && parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1")
        if (!localTest && !SyclopPlaybackPolicy.isHttps(url)) fail()
        if (requestCount >= maxRequests || bodyBytes >= maxBytes) fail()
        val end = runCatching { Math.addExact(start, chunkBytes.toLong() - 1L) }.getOrNull() ?: fail()
        val boundedEnd = knownSize?.let { minOf(end, it - 1L) } ?: end
        val request = Request.Builder().url(parsed).header("Range", "bytes=$start-$boundedEnd")
            .header("Accept-Encoding", "identity").header("User-Agent", "Syclop metadata")
            .header("Cache-Control", "no-store").apply { validator?.let { header("If-Range", it) } }.build()
        val call = client.newCall(request)
        call.timeout().timeout(remainingNanos(), TimeUnit.NANOSECONDS)
        inFlight.set(call)
        checkScope()
        requestCount += 1
        try {
            call.execute().use { response ->
                checkScope()
                if (response.code != 206 || response.header("Content-Encoding").let { it != null && !it.equals("identity", true) }) fail()
                val range = RANGE.matchEntire(response.header("Content-Range").orEmpty()) ?: fail()
                val first = range.groupValues[1].toLongOrNull() ?: fail()
                val last = range.groupValues[2].toLongOrNull() ?: fail()
                val total = range.groupValues[3].toLongOrNull()?.takeIf { it > 0 } ?: fail()
                if (first != start || last < first || last != minOf(boundedEnd, total - 1L) || last >= total || knownSize?.let { it != total } == true) fail()
                val span = last - first + 1L
                if (span !in 1L..chunkBytes.toLong() || span > maxBytes - bodyBytes) fail()
                val prior = validator
                if (prior != null && response.header(validatorHeader!!) != prior) fail()
                if (prior == null) {
                    val etag = response.header("ETag")?.takeIf { !it.startsWith("W/") && it.length in 1..512 }
                    val modified = response.header("Last-Modified")?.takeIf { it.length in 1..200 }
                    validator = etag ?: modified
                    validatorHeader = if (etag != null) "ETag" else if (modified != null) "Last-Modified" else null
                }
                val body = response.body ?: fail()
                val declared = body.contentLength()
                if (declared >= 0L && declared != span) fail()
                val output = Buffer()
                var read = 0L
                while (read <= span) {
                    checkScope()
                    val count = body.source().read(output, minOf(8192L, span + 1L - read))
                    if (count < 0L) break
                    if (count == 0L) fail()
                    read += count; bodyBytes += count
                    if (bodyBytes > maxBytes || read > span) fail()
                }
                if (read != span) fail()
                checkScope()
                knownSize = total
                return output.readByteArray()
            }
        } catch (_: IOException) {
            fail()
        } finally {
            inFlight.compareAndSet(call, null)
        }
    }

    private fun remainingNanos(): Long {
        val elapsed = nowNanos() - started
        if (elapsed < 0L || elapsed >= budgetNanos) fail()
        return budgetNanos - elapsed
    }
    private fun checkScope() {
        if (closed.get() || Thread.currentThread().isInterrupted || !stillAuthorized()) fail()
        remainingNanos()
    }
    override fun close() { closed.set(true); inFlight.getAndSet(null)?.cancel() }
    private fun fail(): Nothing = throw IOException("Selected-file metadata is unavailable.")
    private companion object { val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE) }
}
