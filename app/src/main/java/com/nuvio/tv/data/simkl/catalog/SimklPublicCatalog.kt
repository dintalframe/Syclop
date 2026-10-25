package com.nuvio.tv.data.simkl.catalog

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Native public discovery. Simkl account login/sync remains a separate, optional user action. */
@Singleton
class SimklPublicCatalog internal constructor(
    private val client: SimklPublicCatalogClient,
    private val activeProfileId: () -> Int,
    private val selectionRevision: () -> Long,
    private val profileChanges: Flow<Int>
) {
    @Inject constructor(client: SimklPublicCatalogClient, profiles: ProfileManager) : this(client,
        { profiles.activeProfileId.value }, { profiles.activeProfileSelectionRevision }, profiles.activeProfileId)

    private val pageLinks = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1_500
    }

    val addon: Flow<Addon?> = flowOf(if (!client.configured) null else Addon(
        id = ADDON_ID, name = "Simkl Trending", version = "1.0.0",
        description = "Public movie, TV and anime discovery from Simkl. Account sync is available in Tracking settings.",
        logo = LOGO_URL, baseUrl = BASE_URL, types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series", "anime"), idPrefixes = listOf("mal:", "tt"),
        resources = listOf(AddonResource("catalog", listOf("movie", "series", "anime"), null)),
        catalogs = DESCRIPTORS.map { descriptor -> CatalogDescriptor(type = descriptor.type.contentType,
            rawType = if (descriptor.type == SimklFeedType.ANIME) "anime" else descriptor.type.contentType.toApiString(),
            id = descriptor.id, name = descriptor.label,
            extra = listOf(CatalogExtra("skip")), extraSupported = listOf("skip"), pageSize = PAGE_SIZE,
            showInHome = descriptor.id == "anime-week", hasExplicitShowInHome = true) }
    ))

    suspend fun catalog(catalogId: String, skip: Int): CatalogRow = coroutineScope {
        require(skip in 0..500 && skip % PAGE_SIZE == 0) { "Unknown Simkl catalog page." }
        val descriptor = DESCRIPTORS.singleOrNull { it.id == catalogId } ?: error("Unknown Simkl catalog.")
        val profile = activeProfileId()
        val revision = selectionRevision()
        val current = { profile == activeProfileId() && revision == selectionRevision() }
        if (!current()) throw CancellationException("The active catalog profile changed.")
        val result = async(start = CoroutineStart.LAZY) {
            val items = if (descriptor.genre == null) client.trending(descriptor.type, descriptor.period!!, current)
                else client.genre(descriptor.type, descriptor.genre, current)
            if (!current()) throw CancellationException("The active catalog profile changed.")
            items
        }
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
            profileChanges.collect { if (!current()) result.cancel(CancellationException("The active catalog profile changed.")) }
        }
        try {
            val items = result.await()
            if (!current()) throw CancellationException("The active catalog profile changed.")
            synchronized(pageLinks) { items.forEach { pageLinks[it.preview.id] = it.pageUrl } }
            val page = items.drop(skip).take(PAGE_SIZE)
            CatalogRow(addonId = ADDON_ID, addonName = "Simkl Trending", addonBaseUrl = BASE_URL,
                catalogId = catalogId, catalogName = descriptor.label, type = descriptor.type.contentType,
                rawType = if (descriptor.type == SimklFeedType.ANIME) "anime" else descriptor.type.contentType.toApiString(),
                items = page.map { it.preview }, hasMore = skip + page.size < items.size,
                supportsSkip = true, skipStep = PAGE_SIZE, currentPage = skip / PAGE_SIZE,
                nextSkip = skip + page.size)
        } finally {
            monitor.cancel()
            result.cancel()
        }
    }

    /** Called only for this virtual addon's exact ID route; it never performs a metadata request. */
    fun withAttribution(meta: Meta): Meta {
        val page = synchronized(pageLinks) { pageLinks[meta.id] } ?: return meta
        if (!isItemPageUrl(page)) return meta
        return meta.copy(links = (meta.links + MetaLink("Simkl", "metadata", page)).distinctBy { it.url })
    }

    companion object {
        const val ADDON_ID = "syclop.simkl.trending"
        const val BASE_URL = "syclop-simkl://catalog"
        const val LOGO_URL = "https://us.simkl.in/img_favicon/v2/favicon-192x192.png"
        const val PAGE_SIZE = 100
        fun isNativeAddon(url: String) = url.trimEnd('/').equals(BASE_URL, ignoreCase = true)
        fun isItemPageUrl(url: String): Boolean = Regex(
            "https://simkl\\.com/(?:anime|movies|tv)/[1-9][0-9]{0,11}/[a-z0-9][a-z0-9-]{0,239}"
        ).matches(url)
        fun isNativeItemId(id: String) = id.matches(Regex("(?:mal:[1-9][0-9]{0,11}|tt[0-9]{5,12})"))
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        private data class Descriptor(val id: String, val label: String, val type: SimklFeedType,
            val period: String? = null, val genre: String? = null)
        private val DESCRIPTORS = buildList {
            for (type in listOf(SimklFeedType.ANIME, SimklFeedType.MOVIES, SimklFeedType.TV)) {
                val label = when (type) { SimklFeedType.ANIME -> "Anime"; SimklFeedType.MOVIES -> "Movies"; SimklFeedType.TV -> "TV" }
                for ((period, duration) in listOf("week" to "this week", "today" to "today", "month" to "this month"))
                    add(Descriptor("${type.path}-$period", "Simkl Trending · $label $duration", type, period))
            }
            for (type in listOf(SimklFeedType.MOVIES, SimklFeedType.TV)) {
                val label = if (type == SimklFeedType.MOVIES) "movies" else "TV"
                add(Descriptor("${type.path}-genre-documentary", "Simkl Trending · Documentary $label", type, genre = "Documentary"))
            }
        }
    }
}
