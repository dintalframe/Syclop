package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.data.mal.MalCatalog
import com.nuvio.tv.data.simkl.catalog.SimklPublicCatalog
import com.nuvio.tv.data.catalog.RatedKidsCatalog
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val serverCatalog: ServerCatalog,
    private val malCatalog: MalCatalog? = null,
    private val simklCatalog: SimklPublicCatalog? = null,
    private val ratedKidsCatalog: RatedKidsCatalog? = null
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        if (RatedKidsCatalog.isNativeAddon(addonBaseUrl)) {
            try {
                require(addonId == RatedKidsCatalog.ADDON_ID && type in setOf("movie", "series"))
                emit(NetworkResult.Success(requireNotNull(ratedKidsCatalog).catalog(ContentType.fromString(type), catalogId, skip, extraArgs)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emit(NetworkResult.Error("Could not load rated Kids discovery. Try again after checking the public catalog connection."))
            }
            return@flow
        }

        if (SimklPublicCatalog.isNativeAddon(addonBaseUrl)) {
            try {
                require(addonId == SimklPublicCatalog.ADDON_ID)
                emit(NetworkResult.Success(requireNotNull(simklCatalog).catalog(catalogId,
                    extraArgs["skip"]?.toIntOrNull() ?: skip)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emit(NetworkResult.Error("Could not load Simkl Trending. Check Syclop's application configuration and try again."))
            }
            return@flow
        }

        if (MalCatalog.isNativeAddon(addonBaseUrl)) {
            try {
                emit(NetworkResult.Success(requireNotNull(malCatalog).catalog(catalogId,
                    extraArgs["skip"]?.toIntOrNull() ?: skip, extraArgs)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emit(NetworkResult.Error("Could not load MyAnimeList. Check its account/application settings and try again."))
            }
            return@flow
        }

        if (ServerCatalog.isServerAddon(addonBaseUrl)) {
            val row = try {
                serverCatalog.catalog(addonBaseUrl, addonId, addonName, catalogId, catalogName, type, skip, extraArgs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(NetworkResult.Error(context.getString(error.serverFailure().messageRes())))
                return@flow
            }
            emit(NetworkResult.Success(row))
            return@flow
        }

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Catalog request skip=$skip supportsSkip=$supportsSkip"
        )

        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val rawItemCount = result.data.metas.size
                val posterPattern = layoutPreferenceDataStore.customPosterUrlPattern
                    .let { flow -> flow.first() }
                val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()

                val items = result.data.metas
                    .mapNotNull { it?.toDomainOrNull(type, addonBaseUrl) }
                    .distinctBy { it.id }
                    .withCustomPosterUrls(
                        com.nuvio.tv.core.poster.patternForScreen(posterPattern, posterScreen, enabledScreens)
                    )
                Log.d(
                    TAG,
                    "Catalog request result=success items=${items.size}"
                )

                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && rawItemCount > 0,
                    currentPage = if (skipStep > 0) skip / skipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = skipStep,
                    nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
                    extraArgs = extraArgs
                )
                emit(NetworkResult.Success(catalogRow))
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog request result=failed status=${result.code?.takeIf { it in 100..599 } ?: "none"}"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
