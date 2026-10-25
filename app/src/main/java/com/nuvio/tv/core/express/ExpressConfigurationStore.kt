package com.nuvio.tv.core.express

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Only validated declarative JSON is stored. Native account credentials are never supplied here. */
interface ExpressConfigurationStore { fun read(url: String): String?; fun write(url: String, json: String) }

@Singleton
class AndroidExpressConfigurationStore @Inject constructor(@ApplicationContext context: Context): ExpressConfigurationStore {
    private val directory = File(context.filesDir, "express_configuration_cache")
    @Synchronized override fun read(url: String): String? = runCatching {
        val file = File(directory, key(url))
        AtomicFile(file).openRead().use { input ->
            if (file.length() !in 1..MAX_BYTES.toLong()) null else input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }.getOrNull()
    @Synchronized override fun write(url: String, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(File(directory, key(url)))
        val stream = atomic.startWrite()
        try { stream.write(bytes);atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream);throw error }
        directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            ?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
    }
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) } + ".json"
    companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

internal fun validateExpressJsonBounds(text: String) {
    if (text.toByteArray(Charsets.UTF_8).size > AndroidExpressConfigurationStore.MAX_BYTES) throw ExpressException("Provider configuration exceeds the size limit.")
    var depth = 0;var quoted = false;var escaped = false
    text.forEach { char ->
        if (quoted) { if (escaped) escaped=false else if (char=='\\') escaped=true else if (char=='"') quoted=false }
        else when (char) {
            '"' -> quoted=true
            '{','[' -> { depth++;if (depth>48) throw ExpressException("Provider configuration exceeds the nesting limit.") }
            '}',']' -> depth--
        }
    }
}

/** Append discovery endpoints to the path, preserving case-sensitive query configuration. */
internal fun expressDiscoveryUrls(value: String): List<String> {
    val url = value.toHttpUrl()
    val path = url.encodedPath.trimEnd('/')
    return (listOf(value) + listOf("manifest.json", "vendor", "manifest").map { endpoint ->
        url.newBuilder().encodedPath("$path/$endpoint").build().toString()
    }).distinct()
}
