package com.nuvio.tv.data.mal

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** A local virtual add-on: ordinary Home, search and See All screens consume native MAL rows. */
@Singleton
class MalCatalog @Inject constructor(
    private val auth: MalAuthRepository,
    private val api: MalApiClient,
    private val repository: MalRepository
) {
    val addon: Flow<Addon?> = combine(auth.state, repository.catalogRevision) { account, revision ->
        if (account.configuration.clientId.isBlank()) null else Addon(
            id = ADDON_ID, name = "MyAnimeList", version = "1.0.$revision", description = "Native MyAnimeList catalogs",
            logo = null, baseUrl = BASE_URL, types = listOf(ContentType.SERIES, ContentType.MOVIE),
            resources = listOf(AddonResource("catalog", listOf("series", "movie"), listOf("mal:")),
                AddonResource("meta", listOf("series", "movie"), listOf("mal:"))),
            idPrefixes = listOf("mal:"), configVersion = account.scope.generation,
            catalogs = buildList {
                add(descriptor("ranking", "MAL · Top anime"))
                if (account.connected) MalListStatus.entries.forEach { status ->
                    add(descriptor(status.wireValue, "MAL · ${status.label}"))
                }
                add(CatalogDescriptor(type = ContentType.SERIES, id = "search", name = "MyAnimeList",
                    extra = listOf(CatalogExtra("search", isRequired = true), CatalogExtra("skip")),
                    extraRequired = listOf("search"), extraSupported = listOf("search", "skip"),
                    pageSize = PAGE_SIZE, showInHome = false, hasExplicitShowInHome = true))
            }
        )
    }.distinctUntilChanged()

    suspend fun catalog(catalogId: String, skip: Int, extra: Map<String, String>): CatalogRow {
        require(skip in 0..100_000) { "MyAnimeList page is outside the supported range." }
        val account = auth.state.value
        val result = when (catalogId) {
            "ranking" -> api.ranking(skip, account.configuration.clientId)
            "search" -> {
                val query = extra["search"].orEmpty().trim()
                if (query.length < 3) MalPage(emptyList(), false) else api.publicSearch(query, skip, account.configuration.clientId)
            }
            else -> {
                val status = MalListStatus.entries.firstOrNull { it.wireValue == catalogId }
                    ?: error("Unknown MyAnimeList catalog.")
                auth.authorized(account.scope) { api.libraryPage(status, skip, it) }
            }
        }
        require(auth.state.value.scope == account.scope &&
            auth.state.value.configuration.clientId == account.configuration.clientId) { "The active MyAnimeList profile changed." }
        return CatalogRow(addonId = ADDON_ID, addonName = "MyAnimeList", addonBaseUrl = BASE_URL,
            catalogId = catalogId, catalogName = if (catalogId == "ranking") "MAL · Top anime" else
                MalListStatus.entries.firstOrNull { it.wireValue == catalogId }?.let { "MAL · ${it.label}" } ?: "MyAnimeList",
            type = ContentType.SERIES, items = result.items.map { MalMetadataService.preview(it) },
            hasMore = result.hasMore, supportsSkip = true, skipStep = PAGE_SIZE,
            currentPage = skip / PAGE_SIZE, nextSkip = skip + result.items.size, extraArgs = extra)
    }

    companion object {
        const val ADDON_ID = "syclop.myanimelist"
        const val BASE_URL = "syclop-mal://catalog"
        const val PAGE_SIZE = 100
        fun isNativeAddon(url: String) = url.trimEnd('/').equals(BASE_URL, ignoreCase = true)
        private fun descriptor(id: String, label: String) = CatalogDescriptor(type = ContentType.SERIES,
            id = id, name = label, extra = listOf(CatalogExtra("skip")), pageSize = PAGE_SIZE,
            extraSupported = listOf("skip"), showInHome = true, hasExplicitShowInHome = true)
    }
}
