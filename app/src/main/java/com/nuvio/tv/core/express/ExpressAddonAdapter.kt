package com.nuvio.tv.core.express

import android.util.Log

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.debrid.EpisodeSourcePolicy
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.SourceSearchDiagnostics
import com.nuvio.tv.domain.model.SourceSearchReason
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import java.util.ArrayDeque
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

data class ExpressSearchReport(
    val providers: Int, val completed: Int, val failed: Int, val unsupported: Int,
    val missingMetadata: Int, val sourceCount: Int, val timedOut: Boolean,
    val reasons: Map<SourceSearchReason, Int> = emptyMap(),
    val complete: Boolean = true
) {
    fun diagnostics() = SourceSearchDiagnostics(providers, completed, failed, sourceCount, complete, reasons)
    fun summary(): String = "Express sources: " + diagnostics().summary() +
        if (timedOut) "; search time limit reached" else ""
}

data class ExpressSearchUpdate(
    val streams: List<Stream>,
    val report: ExpressSearchReport,
    val error: String? = null
)

private class ExpressBudgetExceeded : ExpressException("Provider request limit reached.")
private class ExpressParserFailure : ExpressException("Provider response did not match its declarative parser.")

/** User-selected immutable declarative configuration, with an atomic last-valid disk snapshot. */
@Singleton
class ExpressAddonAdapter internal constructor(
    private val http: ExpressHttpClient,
    private val metadata: ExpressMetadataSource,
    private val clock: () -> Long,
    private val snapshots: ExpressConfigurationStore? = null,
    private val searchTimeoutMs: Long = SEARCH_TIMEOUT_MS
) {
    @Inject constructor(http: ExpressHttpClient, metadata: ExpressMetadataResolver, snapshots: AndroidExpressConfigurationStore) : this(http, metadata, System::currentTimeMillis, snapshots)

    private data class Package(val name: String, val version: String, val providers: List<Pair<String, JsonObject>>)
    private data class Configuration(val addon: Addon, val packages: List<Package>, val loadedAt: Long)
    private val configurations = ConcurrentHashMap<String, Configuration>()
    private val configurationMutex = Mutex()
    private val reports = ConcurrentHashMap<String, ExpressSearchReport>()

    fun isExpress(addon: Addon): Boolean = addon.id.startsWith(ID_PREFIX)
    fun lastReport(addon: Addon): ExpressSearchReport? = reports[addon.id]

    suspend fun discover(enteredUrl: String, forceRefresh: Boolean = false): NetworkResult<Addon> = try {
        NetworkResult.Success(configuration(enteredUrl, forceRefresh).addon)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        NetworkResult.Error((error as? ExpressException)?.message ?: "Express provider configuration is unavailable.")
    }

    suspend fun search(addon: Addon, request: ExpressMediaRequest): NetworkResult<List<Stream>> {
        val result = searchProgressively(addon, request).last()
        return result.error?.let { NetworkResult.Error(it) } ?: NetworkResult.Success(result.streams)
    }

    /**
     * Request-local immutable snapshots. The caller owns cancellation; no singleton mutable report
     * is used to associate a result with a title, profile or source configuration.
     */
    fun searchProgressively(addon: Addon, request: ExpressMediaRequest): Flow<ExpressSearchUpdate> = channelFlow {
        var stage = "configuration"
        val counts = ConcurrentHashMap<SourceSearchReason, AtomicInteger>()
        fun count(reason: SourceSearchReason, amount: Int = 1) {
            if (amount > 0) counts.getOrPut(reason) { AtomicInteger() }.addAndGet(amount)
        }
        var providerCount = 0
        try {
            // A configuration replacement cannot mutate this in-flight search snapshot.
            val config = configuration(addon.baseUrl)
            providerCount = config.packages.sumOf { it.providers.size }
            stage = "metadata"
            val media = metadata.resolve(request)
            val episodeRequest = EpisodeSourcePolicy.isEpisodeRequest(media.type, media.episode)
            if (request.videoId.startsWith("mal:") && media.nativeAnimeIdentity == null)
                throw ExpressException("A confirmed anime title identity is unavailable.")
            stage = "provider search"
            val all = config.packages.flatMap { it.providers }
            providerCount = all.size
            val unsupported = AtomicInteger()
            val missing = AtomicInteger()
            val completed = AtomicInteger()
            val failed = AtomicInteger()
            val streams = LinkedHashMap<String, Stream>()
            val resultMutex = Mutex()
            fun report(complete: Boolean = false, timedOut: Boolean = false) = ExpressSearchReport(
                all.size, completed.get(), failed.get(), unsupported.get(), missing.get(), streams.size,
                timedOut, SourceSearchReason.entries.mapNotNull { reason ->
                    counts[reason]?.get()?.takeIf { it > 0 }?.let { reason to it }
                }.toMap(), complete)
            suspend fun publish() = resultMutex.withLock {
                send(ExpressSearchUpdate(streams.values.toList(), report()))
            }
            suspend fun admit(row: ExpressRow, providerName: String) {
                val stream = row.toStream(providerName)
                if (stream == null) { count(SourceSearchReason.INVALID_HASH); return }
                resultMutex.withLock {
                    val key = listOf(stream.infoHash, stream.fileIdx, stream.behaviorHints?.filename).joinToString("|")
                    if (streams.containsKey(key)) count(SourceSearchReason.DUPLICATE)
                    else if (streams.size < MAX_RESULTS) streams[key] = stream
                }
            }
            data class Task(val url: String, val row: ExpressRow? = null)
            class Provider(val id: String, val definition: JsonObject, urls: List<String>) {
                val queries = ArrayDeque(urls.map { Task(it) })
                val subpages = ArrayDeque<Task>()
                var requests = 0
                var succeeded = false
                var finalized = false
                fun next(): Task? = subpages.peekFirst() ?: queries.peekFirst()
                fun removeNext(): Task = if (subpages.isNotEmpty()) subpages.removeFirst() else queries.removeFirst()
                fun finish() {
                    if (!finalized && next() == null) {
                        finalized = true
                        if (succeeded) completed.incrementAndGet() else failed.incrementAndGet()
                    }
                }
            }
            val providers = all.mapNotNull { (id, definition) ->
                try {
                    if (!ExpressParser.supported(definition)) {
                        unsupported.incrementAndGet(); count(SourceSearchReason.UNSUPPORTED); null
                    } else {
                        val urls = providerQueries(definition, media)
                        if (urls.isEmpty()) {
                            missing.incrementAndGet(); count(SourceSearchReason.MISSING_COORDINATES); null
                        } else Provider(id, definition, urls)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failed.incrementAndGet(); count(SourceSearchReason.REQUEST_FAILED); null
                }
            }
            var requests = 0
            val hostRequests = mutableMapOf<String, Int>()
            val budgetLock = Any()
            fun reserve(provider: Provider, url: okhttp3.HttpUrl): Boolean = synchronized(budgetLock) {
                val host = "${url.scheme}://${url.host}:${url.port}"
                if (requests >= MAX_REQUESTS || provider.requests >= MAX_REQUESTS_PER_PROVIDER ||
                    hostRequests.getOrDefault(host, 0) >= MAX_REQUESTS_PER_HOST) false
                else {
                    requests++; provider.requests++
                    hostRequests[host] = hostRequests.getOrDefault(host, 0) + 1
                    true
                }
            }
            val finished = withTimeoutOrNull(searchTimeoutMs) {
                while (providers.any { it.next() != null }) {
                    // One request per provider per round, interleaved by origin. Every eligible
                    // provider gets its first turn before another consumes an alias/subpage turn.
                    val byHost = providers.filter { it.next() != null }.groupBy {
                        http.validatedUrl(it.next()!!.url).let { url -> "${url.scheme}://${url.host}:${url.port}" }
                    }.values.map { ArrayDeque(it) }
                    val round = buildList {
                        while (byHost.any { it.isNotEmpty() }) byHost.forEach { queue ->
                            if (queue.isNotEmpty()) add(queue.removeFirst())
                        }
                    }
                    val scheduled = round.mapNotNull { provider ->
                        val task = provider.next() ?: return@mapNotNull null
                        if (!reserve(provider, http.validatedUrl(task.url))) {
                            count(SourceSearchReason.REQUEST_BUDGET)
                            provider.queries.clear(); provider.subpages.clear(); provider.finish()
                            null
                        } else {
                            provider to provider.removeNext()
                        }
                    }
                    coroutineScope {
                        scheduled.map { (provider, task) -> async {
                            try {
                                val document = http.get(task.url, beforeRedirect = { destination ->
                                    if (!reserve(provider, destination)) throw ExpressBudgetExceeded()
                                })
                                if (task.row != null) {
                                    val magnets = Jsoup.parse(document.text).select("a[href]")
                                        .map { it.attr("href") }.filter { it.startsWith("magnet:", true) }
                                        .distinctBy { it.substringAfter("urn:btih:", "").substringBefore('&').lowercase() }
                                    if (magnets.size == 1) admit(task.row.copy(url = magnets.single()), provider.definition.string("name") ?: provider.id)
                                    else count(SourceSearchReason.INVALID_HASH)
                                } else {
                                    val rows = try { ExpressParser.parse(provider.definition, document.text) }
                                    catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        count(SourceSearchReason.PARSER_ERROR)
                                        throw ExpressParserFailure()
                                    }
                                    var admittedRows = 0
                                    for (row in rows) {
                                        if (!media.acceptsNativeAnimeRow(row)) { count(SourceSearchReason.TITLE_MISMATCH); continue }
                                        if (episodeRequest && EpisodeSourcePolicy.hasReportedPackText(row.title, row.filename)) {
                                            count(SourceSearchReason.SEASON_PACK_HIDDEN); continue
                                        }
                                        if (admittedRows++ >= MAX_ROWS) break
                                        if (row.hash == null && row.url?.startsWith("magnet:", true) != true &&
                                            provider.definition.get("source_is_in_sub_page")?.asBoolean == true) {
                                            val link = row.url?.let { document.url.toHttpUrl().resolve(it)?.toString() }
                                            if (link == null) count(SourceSearchReason.INVALID_HASH)
                                            else provider.subpages.addLast(Task(http.validatedUrl(link).toString(), row))
                                        } else admit(row, provider.definition.string("name") ?: provider.id)
                                    }
                                }
                                provider.succeeded = true
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                when {
                                    error is ExpressBudgetExceeded -> {
                                        count(SourceSearchReason.REQUEST_BUDGET)
                                        provider.queries.clear(); provider.subpages.clear()
                                    }
                                    error is ExpressParserFailure -> Unit // Already counted at the parser boundary.
                                    error is ExpressHttpException && error.failure == ExpressHttpFailure.RATE_LIMITED -> {
                                        count(SourceSearchReason.RATE_LIMITED)
                                        // The host cooldown persists in HTTP; don't repeatedly spend
                                        // this search's budget on aliases known to be blocked.
                                        provider.queries.clear(); provider.subpages.clear()
                                    }
                                    error is ExpressHttpException && error.failure == ExpressHttpFailure.TIMEOUT -> count(SourceSearchReason.TIMEOUT)
                                    else -> count(SourceSearchReason.REQUEST_FAILED)
                                }
                            } finally {
                                provider.finish()
                            }
                            publish()
                        } }.awaitAll()
                    }
                }
                true
            } ?: false
            if (!finished) count(SourceSearchReason.TIMEOUT, providers.count { !it.finalized }.coerceAtLeast(1))
            resultMutex.withLock {
                val finalReport = report(complete = true, timedOut = !finished)
                reports[addon.id] = finalReport // Compatibility diagnostics only, never consumed by the repository.
                val error = if (streams.isEmpty() && (finalReport.failed > 0 || finalReport.unsupported > 0 ||
                        finalReport.missingMetadata > 0 || finalReport.timedOut)) finalReport.summary() else null
                send(ExpressSearchUpdate(streams.values.toList(), finalReport, error))
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            count(if (stage == "metadata" && request.videoId.startsWith("mal:")) SourceSearchReason.MISSING_MAPPING
                else SourceSearchReason.REQUEST_FAILED)
            Log.w("SyclopExpress", "Source search failed stage=$stage class=${error.javaClass.simpleName}")
            val report = ExpressSearchReport(providerCount, 0, 0, 0, 0, 0, false,
                counts.mapValues { it.value.get() }, complete = true)
            reports[addon.id] = report
            send(ExpressSearchUpdate(emptyList(), report,
                (error as? ExpressException)?.message ?: "Express source search failed."))
        }
    }

    private suspend fun configuration(enteredUrl: String, forceRefresh: Boolean = false): Configuration =
        withContext(Dispatchers.IO) { loadConfiguration(enteredUrl, forceRefresh) }

    private suspend fun loadConfiguration(enteredUrl: String, forceRefresh: Boolean): Configuration {
        val parsed = http.validatedUrl(enteredUrl)
        val normalized = parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/').ifEmpty { "/" }).build()
        val url = if (normalized.encodedQuery == null) normalized.toString().trimEnd('/') else normalized.toString()
        if (!forceRefresh) configurations[url]?.let { return it }
        return configurationMutex.withLock {
            if (!forceRefresh) {
                configurations[url]?.let { return@withLock it }
                val saved = snapshots?.read(url)
                if (saved != null) runCatching { buildConfiguration(url, restorePackages(saved)) }.getOrNull()?.let { config ->
                    configurations[url] = config
                    return@withLock config
                }
            }
            val packages = discoverPackages(url)
            val config = buildConfiguration(url, packages)
            // Validation/persistence complete before replacement; a failed refresh keeps last good.
            snapshots?.write(url, snapshot(packages))
            if (configurations.size >= 30) configurations.clear()
            configurations[url] = config
            config
        }
    }

    private fun buildConfiguration(url: String, packages: List<Package>): Configuration {
        if (packages.isEmpty()) throw ExpressException("No supported Express package was found at this URL.")
        val definitions = packages.flatMap { it.providers }
        definitions.forEach { validateProviderConfiguration(it.second) }
        if (definitions.size > MAX_PROVIDERS || definitions.none { ExpressParser.supported(it.second) })
            throw ExpressException("The package has no supported declarative providers or exceeds the definition limit.")
        val types = listOf("movie", "series", "anime")
        val unsupported = definitions.count { !ExpressParser.supported(it.second) }
        val addon = Addon(
            id = ID_PREFIX + fingerprint(url), name = packages.joinToString(" + ") { it.name }.take(120),
            version = packages.joinToString("+") { it.version }.take(120),
            description = "User-selected Express source package. ${definitions.size} definitions; $unsupported unsupported.",
            logo = null, baseUrl = url, catalogs = emptyList(),
            types = listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.UNKNOWN), rawTypes = types,
            resources = listOf(AddonResource("stream", types, null)))
        return Configuration(addon, packages, clock())
    }

    private fun validateProviderConfiguration(provider: JsonObject) {
        val scopes = listOf("movie", "episode", "season", "anime").mapNotNull { name ->
            val scope = provider.get(name) ?: return@mapNotNull null
            if (!scope.isJsonObject) throw ExpressException("Provider media scope is invalid.")
            scope.asJsonObject
        }
        if (scopes.isEmpty()) throw ExpressException("Provider media scope is missing.")
        scopes.forEach { scope ->
            val query = scope.get("query")
            if (query == null || !query.isJsonPrimitive || !query.asJsonPrimitive.isString ||
                query.asString.length !in 1..4_096) throw ExpressException("Provider query configuration is invalid.")
            val raw = scope.get("keywords") ?: throw ExpressException("Provider query configuration is invalid.")
            val keywords = if (raw.isJsonArray) raw.asJsonArray.toList() else listOf(raw)
            if (keywords.size !in 1..30 || keywords.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString ||
                    it.asString.length !in 1..2_048 }) throw ExpressException("Provider query configuration is invalid.")
        }
        provider.get("source_is_in_sub_page")?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) throw ExpressException("Provider page configuration is invalid.")
        }
    }

    private fun snapshot(packages: List<Package>): String = JsonObject().apply {
        add("packages", JsonArray().apply { packages.forEach { pkg -> add(JsonObject().apply {
            addProperty("name", pkg.name);addProperty("version", pkg.version)
            add("providers", JsonObject().apply { pkg.providers.forEach { (name, config) -> add(name, config.deepCopy()) } })
        }) } })
    }.toString().also(::validateExpressJsonBounds)

    private fun restorePackages(json: String): List<Package> {
        validateExpressJsonBounds(json)
        val root = parseExpressObject(json)
        val values = root.get("packages")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw ExpressException("Saved provider configuration is invalid.")
        if (values.size() !in 1..5) throw ExpressException("Saved provider package limit exceeded.")
        return values.map { element ->
            val item = element.asJsonObject
            val definitions = item.getAsJsonObject("providers").entrySet().map { it.key to it.value.asJsonObject.deepCopy() }
            if (definitions.size !in 1..MAX_PROVIDERS) throw ExpressException("Saved provider definition limit exceeded.")
            definitions.forEach { http.validatedUrl(it.second.string("base_url") ?: throw ExpressException("Provider base URL is missing.")) }
            Package(item.string("name")?.take(120) ?: "Express sources", item.string("version")?.take(120) ?: "1", definitions)
        }
    }

    private suspend fun discoverPackages(url: String): List<Package> {
        val candidates = expressDiscoveryUrls(url)
        for (candidate in candidates) {
            val document = try { http.get(candidate, CONFIG_MAX_BYTES) } catch (error: Exception) {
                if (error is CancellationException) throw error
                continue
            }
            if (!document.text.trimStart().startsWith('{')) continue
            validateExpressJsonBounds(document.text)
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
                validateExpressJsonBounds(document.text)
                resolvePackage(document.url, parseExpressObject(document.text), visited, depth + 1)
            }
        }
        val type = root.string("type")
        if (type != null) {
            if (type != "express") throw ExpressException("This package requires unsupported downloaded JavaScript. Only declarative Express packages are supported.")
            val reference = root.string("url") ?: root.string("permaUrl") ?: throw ExpressException("Express manifest has no package URL.")
            val destination = url.toHttpUrl().resolve(reference)?.toString() ?: throw ExpressException("Express package URL is invalid.")
            val document = http.get(destination, CONFIG_MAX_BYTES)
            validateExpressJsonBounds(document.text)
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
        val scopes = when {
            media.type == "movie" -> listOf("movie")
            media.anime && media.hasMappedEpisodeCoordinates -> listOf("anime", "episode")
            media.anime -> listOf("anime")
            else -> listOf("episode")
        }
        val scopeQueries = scopes.map { scope ->
            val config = provider.get(scope)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@map emptyList<String>()
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
            keywords.take(3).flatMap { keyword ->
                media.queryTitles().mapNotNull { title ->
                    val variables = media.variables(title)
                    val search = expressTemplate(keyword, variables) ?: return@mapNotNull null
                    val path = expressTemplate(query, variables.mapValues { expressEncode(it.value) } + ("query" to expressEncode(search))) ?: return@mapNotNull null
                    val base = provider.string("base_url") ?: return@mapNotNull null
                    // Query fragments are relative to the provider's configured path, not the host root.
                    http.validatedUrl(base.trimEnd('/') + "/" + path.trimStart('/')).toString()
                }
            }
        }
        // Alternate coordinate strategies before taking the bound: a valid mapped S/E query
        // cannot be crowded out by three absolute-title aliases from the first scope.
        return buildList {
            repeat(scopeQueries.maxOfOrNull { it.size } ?: 0) { index ->
                scopeQueries.forEach { queries -> queries.getOrNull(index)?.let(::add) }
            }
        }.distinct().let { if (media.anime) it.take(6) else it }
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)

    companion object {
        const val ID_PREFIX = "syclop.express:"
        private const val CONFIG_MAX_BYTES = 2 * 1024 * 1024
        // Periodic ProviderUpdateCoordinator explicitly refreshes validated snapshots every24h.
        private const val MAX_PROVIDERS = 80
        private const val MAX_REQUESTS = 90
        private const val MAX_REQUESTS_PER_PROVIDER = 9
        private const val MAX_REQUESTS_PER_HOST = 30
        private const val MAX_ROWS = 20
        private const val MAX_RESULTS = 300
        private const val SEARCH_TIMEOUT_MS = 30_000L
    }
}
