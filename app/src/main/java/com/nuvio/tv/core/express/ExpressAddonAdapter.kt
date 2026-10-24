package com.nuvio.tv.core.express

import android.util.Log

import com.google.gson.JsonObject
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Stream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

data class ExpressSearchReport(
    val providers: Int, val completed: Int, val failed: Int, val unsupported: Int,
    val missingMetadata: Int, val sourceCount: Int, val timedOut: Boolean
) {
    fun summary(): String = "Express sources: $completed/$providers completed, $failed failed, " +
        "$unsupported unsupported, $missingMetadata missing metadata, $sourceCount results" + if (timedOut) "; search time limit reached" else ""
}

/** Runtime configuration is fetched from the user's URL and kept only in memory. */
@Singleton
class ExpressAddonAdapter internal constructor(
    private val http: ExpressHttpClient,
    private val metadata: ExpressMetadataSource,
    private val clock: () -> Long
) {
    @Inject constructor(http: ExpressHttpClient, metadata: ExpressMetadataResolver) : this(http, metadata, System::currentTimeMillis)

    private data class Package(val name: String, val version: String, val providers: List<Pair<String, JsonObject>>)
    private data class Configuration(val addon: Addon, val packages: List<Package>, val loadedAt: Long)
    private val configurations = ConcurrentHashMap<String, Configuration>()
    private val reports = ConcurrentHashMap<String, ExpressSearchReport>()

    fun isExpress(addon: Addon): Boolean = addon.id.startsWith(ID_PREFIX)
    fun lastReport(addon: Addon): ExpressSearchReport? = reports[addon.id]

    suspend fun discover(enteredUrl: String): NetworkResult<Addon> = try {
        NetworkResult.Success(configuration(enteredUrl).addon)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        NetworkResult.Error((error as? ExpressException)?.message ?: "Express provider configuration is unavailable.")
    }

    suspend fun search(addon: Addon, request: ExpressMediaRequest): NetworkResult<List<Stream>> {
        var stage = "configuration"
        return try {
            val config = configuration(addon.baseUrl)
            stage = "metadata"
            val media = metadata.resolve(request)
            stage = "provider search"
            val all = config.packages.flatMap { it.providers }
            val unsupported = AtomicInteger()
            val missing = AtomicInteger()
            val completed = AtomicInteger()
            val failed = AtomicInteger()
            val requests = AtomicInteger()
            val streams = ConcurrentLinkedQueue<Stream>()
            val finished = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                coroutineScope {
                    all.map { (id, provider) -> async {
                        try {
                            if (!ExpressParser.supported(provider)) { unsupported.incrementAndGet(); return@async }
                            val urls = providerQueries(provider, media)
                            if (urls.isEmpty()) { missing.incrementAndGet(); return@async }
                            var succeeded = false
                            for (url in urls) {
                                if (requests.incrementAndGet() > MAX_REQUESTS) break
                                try {
                                    val document = http.get(url)
                                    val rows = ExpressParser.parse(provider, document.text)
                                    for (row in rows.take(MAX_ROWS)) {
                                        var resolved = row
                                        if (row.hash == null && row.url?.startsWith("magnet:", true) != true &&
                                            provider.get("source_is_in_sub_page")?.asBoolean == true) {
                                            val link = row.url?.let { document.url.toHttpUrl().resolve(it)?.toString() } ?: continue
                                            if (requests.incrementAndGet() > MAX_REQUESTS) break
                                            val page = http.get(link)
                                            val magnets = Jsoup.parse(page.text).select("a[href]")
                                                .map { it.attr("href") }.filter { it.startsWith("magnet:", true) }
                                                .distinctBy { it.substringAfter("urn:btih:", "").substringBefore('&').lowercase() }
                                            if (magnets.size != 1) continue
                                            resolved = row.copy(url = magnets.single())
                                        }
                                        resolved.toStream(provider.string("name") ?: id)?.let(streams::add)
                                        if (streams.size >= MAX_RESULTS) break
                                    }
                                    succeeded = true
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                            }
                        }
                        if (succeeded) completed.incrementAndGet() else failed.incrementAndGet()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failed.incrementAndGet()
                            // Only a fixed stage and exception class; no provider/media URLs or payloads.
                            Log.w("SyclopExpress", "Provider query/setup failed class=${error.javaClass.simpleName}")
                        }
                    } }.awaitAll()
                }
                true
            } ?: false
            stage = "results"
            val unique = streams.distinctBy { listOf(it.infoHash, it.fileIdx, it.behaviorHints?.filename).joinToString("|") }.take(MAX_RESULTS)
            val report = ExpressSearchReport(all.size, completed.get(), failed.get(), unsupported.get(), missing.get(), unique.size, !finished)
            reports[addon.id] = report
            if (unique.isEmpty() && (report.failed > 0 || report.unsupported > 0 || report.missingMetadata > 0 || report.timedOut)) {
                NetworkResult.Error(report.summary())
            } else NetworkResult.Success(unique)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Avoid Throwable.toString()/message: either can contain a credential-bearing URL.
            Log.w("SyclopExpress", "Source search failed stage=$stage class=${error.javaClass.simpleName}")
            NetworkResult.Error((error as? ExpressException)?.message ?: "Express source search failed.")
        }
    }

    private suspend fun configuration(enteredUrl: String): Configuration {
        val url = http.validatedUrl(enteredUrl).toString().trimEnd('/')
        configurations[url]?.takeIf { clock() - it.loadedAt < CONFIG_TTL_MS }?.let { return it }
        val packages = discoverPackages(url)
        if (packages.isEmpty()) throw ExpressException("No supported Express package was found at this URL.")
        val types = listOf("movie", "series", "anime")
        val unsupported = packages.sumOf { pkg -> pkg.providers.count { !ExpressParser.supported(it.second) } }
        val addon = Addon(
            id = ID_PREFIX + fingerprint(url), name = packages.joinToString(" + ") { it.name }.take(120),
            version = packages.joinToString("+") { it.version }.take(120),
            description = "User-selected Express source package. ${packages.sumOf { it.providers.size }} definitions; $unsupported unsupported.",
            logo = null, baseUrl = url, catalogs = emptyList(),
            types = listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.UNKNOWN), rawTypes = types,
            resources = listOf(AddonResource("stream", types, null)))
        return Configuration(addon, packages, clock()).also {
            if (configurations.size >= 20) configurations.clear()
            configurations[url] = it
        }
    }

    private suspend fun discoverPackages(url: String): List<Package> {
        val candidates = listOf(url, url.trimEnd('/') + "/vendor", url.trimEnd('/') + "/manifest").distinct()
        for (candidate in candidates) {
            val document = try { http.get(candidate, CONFIG_MAX_BYTES) } catch (error: Exception) {
                if (error is CancellationException) throw error
                continue
            }
            if (!document.text.trimStart().startsWith('{')) continue
            return resolvePackage(document.url, parseExpressObject(document.text), linkedSetOf(), 0)
        }
        throw ExpressException("No Express manifest was found. Enter the package manifest or vendor URL.")
    }

    private suspend fun resolvePackage(url: String, root: JsonObject, visited: MutableSet<String>, depth: Int): List<Package> {
        if (depth > 4 || !visited.add(url)) throw ExpressException("Express manifest references form a cycle or exceed the limit.")
        if (root.get("packages")?.isJsonArray == true) {
            val listed = root.getAsJsonArray("packages").mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.string("manifest")
            }
            val defaults = root.get("defaults")?.takeIf { it.isJsonObject }?.asJsonObject?.get("packages")
                ?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.scalarString() }.orEmpty()
            val selected = defaults.ifEmpty { listed.singleOrNull()?.let(::listOf).orEmpty() }
            if (selected.isEmpty()) throw ExpressException("This vendor has several packages. Enter the manifest URL of the package you want.")
            if (selected.size > 5) throw ExpressException("Vendor package selection exceeds the limit.")
            return selected.distinct().flatMap { reference ->
                val destination = url.toHttpUrl().resolve(reference)?.toString() ?: throw ExpressException("Vendor package URL is invalid.")
                val document = http.get(destination, CONFIG_MAX_BYTES)
                resolvePackage(document.url, parseExpressObject(document.text), visited, depth + 1)
            }
        }
        val type = root.string("type")
        if (type != null) {
            if (type != "express") throw ExpressException("This package requires unsupported downloaded JavaScript. Only declarative Express packages are supported.")
            val reference = root.string("url") ?: root.string("permaUrl") ?: throw ExpressException("Express manifest has no package URL.")
            val destination = url.toHttpUrl().resolve(reference)?.toString() ?: throw ExpressException("Express package URL is invalid.")
            val document = http.get(destination, CONFIG_MAX_BYTES)
            return resolvePackage(document.url, parseExpressObject(document.text), visited, depth + 1)
        }
        val providers = root.entrySet().filter { (key, value) -> !key.startsWith('_') && value.isJsonObject && value.asJsonObject.string("base_url") != null }
            .take(MAX_PROVIDERS + 1).map { it.key to it.value.asJsonObject }
        if (providers.isEmpty()) throw ExpressException("The response contains no Express provider definitions.")
        if (providers.size > MAX_PROVIDERS) throw ExpressException("The package exceeds the provider definition limit.")
        providers.forEach { http.validatedUrl(it.second.string("base_url")!!) }
        val manifest = root.get("_manifest")?.takeIf { it.isJsonObject }?.asJsonObject
        return listOf(Package(manifest?.string("name") ?: "Express sources", manifest?.string("version") ?: "1", providers))
    }

    internal fun providerQueries(provider: JsonObject, media: ExpressMedia): List<String> {
        val variables = media.variables()
        val scopes = when {
            media.type == "movie" -> listOf("movie")
            media.anime -> listOf("anime")
            else -> listOf("episode", "season")
        }
        return scopes.flatMap { scope ->
            val config = provider.get(scope)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMap emptyList()
            val queryValue = config.get("query")
            if (queryValue == null || !queryValue.isJsonPrimitive || !queryValue.asJsonPrimitive.isString) {
                throw ExpressException("Provider query configuration is invalid.")
            }
            val query = queryValue.asString
            val raw = config.get("keywords")
                ?: throw ExpressException("Provider query configuration is invalid.")
            val keywordValues = if (raw.isJsonArray) raw.asJsonArray.toList() else listOf(raw)
            if (keywordValues.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) {
                throw ExpressException("Provider query configuration is invalid.")
            }
            val keywords = keywordValues.map { it.asString }
            keywords.take(3).mapNotNull { keyword ->
                val search = expressTemplate(keyword, variables) ?: return@mapNotNull null
                val path = expressTemplate(query, variables.mapValues { expressEncode(it.value) } + ("query" to expressEncode(search))) ?: return@mapNotNull null
                val base = provider.string("base_url") ?: return@mapNotNull null
                // Query fragments are relative to the provider's configured path, not the host root.
                http.validatedUrl(base.trimEnd('/') + "/" + path.trimStart('/')).toString()
            }
        }.distinct()
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)

    companion object {
        const val ID_PREFIX = "syclop.express:"
        private const val CONFIG_MAX_BYTES = 2 * 1024 * 1024
        private const val CONFIG_TTL_MS = 15 * 60 * 1000L
        private const val MAX_PROVIDERS = 80
        private const val MAX_REQUESTS = 90
        private const val MAX_ROWS = 20
        private const val MAX_RESULTS = 300
        private const val SEARCH_TIMEOUT_MS = 30_000L
    }
}
