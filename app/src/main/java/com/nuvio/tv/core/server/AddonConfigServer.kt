package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddonConfigServer(
    private val context: Context,
    private val webConfigMode: AddonWebConfigMode,
    private val currentPageStateProvider: () -> PageState,
    private val onChangeProposed: (PendingAddonChange) -> Unit,
    private val tmdbMetadataProvider: ((TmdbSourceMetadataRequest) -> TmdbSourceMetadataInfo?)? = null,
    private val tmdbSearchProvider: ((TmdbSourceSearchRequest) -> List<TmdbSourceSearchResultInfo>)? = null,
    private val traktMetadataProvider: ((TraktSourceMetadataRequest) -> TraktSourceMetadataInfo?)? = null,
    private val traktSearchProvider: ((TraktSourceSearchRequest) -> List<TraktSourceSearchResultInfo>)? = null,
    private val logoProvider: (() -> ByteArray?)? = null,
    private val isCurrent: () -> Boolean = { true },
    port: Int = 8080
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val pendingChanges = ConcurrentHashMap<String, PendingAddonChange>()
    private val addonIdsByUrl = ConcurrentHashMap<String, String>()
    private val pairing = LocalConfigSession()

    private data class BrowserAddon(val id: String, val name: String)

    fun pairingUrl(ipAddress: String): String = pairing.pairingUrl(ipAddress, listeningPort)

    fun confirmChange(id: String) {
        pendingChanges[id]?.status = AddonChangeStatus.CONFIRMED
    }

    fun rejectChange(id: String) {
        pendingChanges[id]?.status = AddonChangeStatus.REJECTED
    }

    override fun serve(session: IHTTPSession): Response = pairing.respond(session) {
        if (!isCurrent()) throw InvalidConfigRequest(Response.Status.FORBIDDEN, "Pairing required")
        val uri = session.uri
        val method = session.method

        when {
            method == Method.GET && uri == "/" -> serveWebPage()
            method == Method.GET && uri == "/logo.png" -> serveLogo()
            method == Method.GET && uri == "/api/state" -> servePageState()
            method == Method.GET && uri == "/api/addons" -> serveAddonList()
            method == Method.POST && uri == "/api/addons" -> handleAddonUpdate(session)
            method == Method.GET && uri == "/api/collections" -> serveCollections()
            method == Method.GET && uri == "/api/tmdb/metadata" -> serveTmdbMetadata(session)
            method == Method.GET && uri == "/api/tmdb/search" -> serveTmdbSearch(session)
            method == Method.GET && uri == "/api/trakt/metadata" -> serveTraktMetadata(session)
            method == Method.GET && uri == "/api/trakt/search" -> serveTraktSearch(session)
            method == Method.GET && uri.startsWith("/api/status/") -> serveChangeStatus(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            AddonWebPage.getHtml(context, webConfigMode)
        )
    }

    private fun serveLogo(): Response {
        val bytes = logoProvider?.invoke()
        return if (bytes != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveCollections(): Response {
        val collections = currentPageState().collections
        val json = gson.toJson(collections)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun serveAddonList(): Response {
        val json = gson.toJson(browserAddons(currentPageState()))
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun servePageState(): Response {
        val state = currentPageState()
        val catalogs = state.catalogs.map { catalog ->
            catalog.copy(addonName = browserName(catalog.addonName, state.addons))
        }
        val publicState = gson.toJsonTree(state.copy(addons = emptyList(), catalogs = catalogs)).asJsonObject
        publicState.add("addons", gson.toJsonTree(browserAddons(state)))
        val json = gson.toJson(publicState)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun addonId(addon: AddonInfo): String =
        addonIdsByUrl.computeIfAbsent(addon.url) { UUID.randomUUID().toString() }

    private fun browserAddons(state: PageState): List<BrowserAddon> = state.addons.map { addon ->
        BrowserAddon(addonId(addon), browserName(addon.name, state.addons))
    }

    private fun browserName(name: String, addons: List<AddonInfo>): String =
        name.takeIf {
            it.isNotBlank() && addons.none { addon ->
                val urlLabel = addon.url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                it == urlLabel || it.contains(addon.url)
            }
        }
            ?: context.getString(com.nuvio.tv.R.string.stream_info_subtitle_source_addon)

    private fun currentPageState(): PageState = currentPageStateProvider().also {
        if (!isCurrent()) throw InvalidConfigRequest(Response.Status.FORBIDDEN, "Pairing required")
    }

    private fun serveTmdbMetadata(session: IHTTPSession): Response {
        val provider = tmdbMetadataProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "TMDB metadata unavailable")))
        val sourceType = session.parameters["sourceType"]?.firstOrNull()?.trim().orEmpty()
        val tmdbId = session.parameters["id"]?.firstOrNull()?.trim()?.toIntOrNull()
        if (sourceType.isBlank() || tmdbId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid TMDB metadata request")))
        }
        val metadata = runCatching {
            provider(TmdbSourceMetadataRequest(sourceType = sourceType, tmdbId = tmdbId))
        }.getOrNull()
        return if (metadata != null) {
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(metadata))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "TMDB source not found")))
        }
    }

    private fun serveTmdbSearch(session: IHTTPSession): Response {
        val provider = tmdbSearchProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "TMDB search unavailable")))
        val sourceType = session.parameters["sourceType"]?.firstOrNull()?.trim().orEmpty()
        val query = session.parameters["query"]?.firstOrNull()?.trim().orEmpty()
        if (sourceType.isBlank() || query.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid TMDB search request")))
        }
        val results = runCatching {
            provider(TmdbSourceSearchRequest(sourceType = sourceType, query = query))
        }.getOrElse { emptyList() }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(results))
    }

    private fun serveTraktMetadata(session: IHTTPSession): Response {
        val provider = traktMetadataProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Trakt metadata unavailable")))
        val input = session.parameters["input"]?.firstOrNull()?.trim().orEmpty()
        if (input.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid Trakt metadata request")))
        }
        val metadata = runCatching {
            provider(TraktSourceMetadataRequest(input = input))
        }.getOrNull()
        return if (metadata?.traktListId != null) {
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(metadata))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Trakt list not found")))
        }
    }

    private fun serveTraktSearch(session: IHTTPSession): Response {
        val provider = traktSearchProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Trakt search unavailable")))
        val query = session.parameters["query"]?.firstOrNull()?.trim().orEmpty()
        if (query.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid Trakt search request")))
        }
        val results = runCatching {
            provider(TraktSourceSearchRequest(query = query))
        }.getOrElse { emptyList() }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(results))
    }

    private fun handleAddonUpdate(session: IHTTPSession): Response {
        val body = readConfigJsonBody(session)

        val change: PendingAddonChange = try {
            val parsed = gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
            require("urls" !in parsed)
            val currentState = currentPageState()
            val urls = if (webConfigMode.allowAddonManagement) {
                val addons = currentState.addons.associateBy(::addonId)
                val ids = requireNotNull(parsed["addonIds"] as? List<*>)
                require(ids.size == ids.distinct().size)
                ids.map { id -> requireNotNull(addons[id as? String]).url }
            } else currentState.addons.map { it.url }
            val catalogOrderKeys = parseStringList(parsed["catalogOrderKeys"])
            val disabledCatalogKeys = parseStringList(parsed["disabledCatalogKeys"])
            val collectionsRaw = parsed["collections"]
            val collectionsJson = if (collectionsRaw != null) gson.toJson(collectionsRaw) else null
            val disabledCollectionKeys = parseStringList(parsed["disabledCollectionKeys"])
            val followAddonsOrder = parsed["followAddonsOrder"] as? Boolean
            sanitizePendingAddonChange(
                mode = webConfigMode,
                proposedChange = PendingAddonChange(
                    proposedUrls = urls,
                    proposedCatalogOrderKeys = catalogOrderKeys,
                    proposedDisabledCatalogKeys = disabledCatalogKeys,
                    proposedCollectionsJson = collectionsJson,
                    proposedDisabledCollectionKeys = disabledCollectionKeys,
                    proposedFollowAddonsOrder = followAddonsOrder,
                    expectedAddonUrls = currentState.addons.map { it.url }.takeIf { webConfigMode.allowAddonManagement }
                ),
                currentState = currentState
            )
        } catch (error: InvalidConfigRequest) {
            throw error
        } catch (_: Exception) {
            val error = mapOf("error" to context.getString(com.nuvio.tv.R.string.web_error_invalid_request_body))
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                gson.toJson(error)
            )
        }

        pendingChanges.values
            .filter { it.status == AddonChangeStatus.PENDING }
            .forEach { it.status = AddonChangeStatus.REJECTED }
        pendingChanges[change.id] = change
        onChangeProposed(change)

        val response = mapOf("status" to "pending_confirmation", "id" to change.id)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(response))
    }

    private fun serveChangeStatus(uri: String): Response {
        val id = uri.removePrefix("/api/status/")
        val change = pendingChanges[id]
        val status = change?.status?.name?.lowercase() ?: "not_found"
        val response = mapOf("status" to status)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(response))
    }

    private fun parseStringList(rawValue: Any?): List<String> {
        val values = rawValue as? List<*> ?: return emptyList()
        return values.asSequence()
            .mapNotNull { (it as? String)?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    companion object {
        fun startOnAvailablePort(
            context: Context,
            webConfigMode: AddonWebConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider: () -> PageState,
            onChangeProposed: (PendingAddonChange) -> Unit,
            tmdbMetadataProvider: ((TmdbSourceMetadataRequest) -> TmdbSourceMetadataInfo?)? = null,
            tmdbSearchProvider: ((TmdbSourceSearchRequest) -> List<TmdbSourceSearchResultInfo>)? = null,
            traktMetadataProvider: ((TraktSourceMetadataRequest) -> TraktSourceMetadataInfo?)? = null,
            traktSearchProvider: ((TraktSourceSearchRequest) -> List<TraktSourceSearchResultInfo>)? = null,
            logoProvider: (() -> ByteArray?)? = null,
            isCurrent: () -> Boolean = { true },
            startPort: Int = 8080,
            maxAttempts: Int = 10
        ): AddonConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AddonConfigServer(
                        context = context,
                        webConfigMode = webConfigMode,
                        currentPageStateProvider = currentPageStateProvider,
                        onChangeProposed = onChangeProposed,
                        tmdbMetadataProvider = tmdbMetadataProvider,
                        tmdbSearchProvider = tmdbSearchProvider,
                        traktMetadataProvider = traktMetadataProvider,
                        traktSearchProvider = traktSearchProvider,
                        logoProvider = logoProvider,
                        isCurrent = isCurrent,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                    // Port in use, try next
                }
            }
            return null
        }
    }
}
