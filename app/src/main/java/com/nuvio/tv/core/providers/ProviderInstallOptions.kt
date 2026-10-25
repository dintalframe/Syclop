package com.nuvio.tv.core.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.express.ExpressHttpClient
import com.nuvio.tv.core.express.ExpressException
import com.nuvio.tv.core.express.validateExpressJsonBounds
import com.nuvio.tv.core.express.expressDiscoveryUrls
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Stable path/query semantics; only the stremio scheme is translated. No credential headers. */
object ProviderInstallUrls {
    fun normalize(value: String): String? {
        val input = value.trim().takeIf { it.length in 1..4096 } ?: return null
        val translated = if (input.startsWith("stremio://", true)) "https://" + input.substring(10) else input
        val url = translated.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.port != 443) return null
        return url.toString()
    }
}
data class ProviderInstallOption(val name: String, val manifestUrl: String, val isDefault: Boolean = false) {
    override fun toString() = "ProviderInstallOption(name=$name, url=[redacted], default=$isDefault)"
}

@Singleton
class ProviderInstallOptions @Inject constructor(private val http: ExpressHttpClient) {
    suspend fun inspect(entered: String): List<ProviderInstallOption> {
        val url = ProviderInstallUrls.normalize(entered) ?: throw ExpressException("Enter an HTTPS provider manifest or vendor URL.")
        val candidates = expressDiscoveryUrls(url)
        for (candidate in candidates) {
            val document = try { http.get(candidate, 2 * 1024 * 1024) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
            if (!document.text.trimStart().startsWith('{')) continue
            val root = withContext(Dispatchers.Default) {
                validateExpressJsonBounds(document.text)
                runCatching { JsonParser.parseString(document.text).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
            } ?: continue
            val options = withContext(Dispatchers.Default) { parseVendor(root, document.url) }
            return completeLabels(options)
        }
        return emptyList()
    }
    internal fun parseVendor(root: JsonObject, base: String): List<ProviderInstallOption> {
        val packages = root.get("packages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        if (packages.size() !in 1..30) throw ExpressException("Vendor package list is invalid or too large.")
        val defaults = root.get("defaults")?.takeIf { it.isJsonObject }?.asJsonObject?.get("packages")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty().toSet()
        return packages.map { value ->
            val entry = value.takeIf { it.isJsonObject }?.asJsonObject ?: throw ExpressException("Vendor package entry is invalid.")
            val reference = entry.get("manifest")?.takeIf { it.isJsonPrimitive }?.asString ?: throw ExpressException("Vendor package has no manifest URL.")
            val resolved = base.toHttpUrlOrNull()?.resolve(reference)?.toString()?.let(ProviderInstallUrls::normalize)
                ?: throw ExpressException("Vendor package URL must use HTTPS without embedded credentials.")
            // Publisher descriptions often identify variants when entries contain no name.
            // Never infer Full/Lite or any other variant from the manifest path.
            val name = publisherLabel(entry, "name") ?: publisherLabel(entry, "description").orEmpty()
            ProviderInstallOption(name, resolved, reference in defaults)
        }.distinctBy { it.manifestUrl }
    }
    private fun publisherLabel(value: JsonObject, key: String): String? = value.get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
        ?.takeIf { it.isNotEmpty() }?.take(120)

    private suspend fun completeLabels(options: List<ProviderInstallOption>): List<ProviderInstallOption> = coroutineScope {
        options.mapIndexed { index, option -> async {
            if (option.name.isNotBlank()) return@async option
            // All lookups share the isolated client's maximum-three request semaphore. Timers
            // include waiting for that semaphore, so a large vendor cannot stall the chooser.
            val label = withTimeoutOrNull(3_000L) {
                try {
                    val document = http.get(option.manifestUrl, 256 * 1024)
                    withContext(Dispatchers.Default) {
                        validateExpressJsonBounds(document.text)
                        val header = JsonParser.parseString(document.text).takeIf { it.isJsonObject }?.asJsonObject
                        val embedded = header?.get("_manifest")?.takeIf { it.isJsonObject }?.asJsonObject
                        header?.let { publisherLabel(it, "name") } ?: embedded?.let { publisherLabel(it, "name") }
                            ?: header?.let { publisherLabel(it, "description") }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            // Fetch only the exact manifest header. Do not follow Express package URLs or code.
            option.copy(name = label ?: "Package ${index + 1} (name unavailable)")
        } }.awaitAll()
    }

}
