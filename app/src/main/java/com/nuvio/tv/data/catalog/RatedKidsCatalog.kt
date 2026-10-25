package com.nuvio.tv.data.catalog

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

enum class RatedKidsTopic { FAMILY, SCIENCE, NATURE, EDUCATION, DOCUMENTARY }
data class RatedKidsDescriptor(val id: String, val label: String, val type: ContentType,
    val genres: String, val subgenres: String? = null, val topic: RatedKidsTopic = RatedKidsTopic.FAMILY) {
    val certificates: String get() = if (type == ContentType.MOVIE) "g" else "tv-y,tv-y7,tv-y7-fv,tv-g"
}
data class RatedKidsPage(val items: List<MetaPreview>, val hasMore: Boolean)
interface RatedKidsPageSource {
    val configured: Boolean
    suspend fun page(descriptor: RatedKidsDescriptor, page: Int): RatedKidsPage
}

/** Public certified discovery only. It never reads a Trakt user's token, lists or viewing history. */
@Singleton
class RatedKidsCatalog internal constructor(
    private val source: RatedKidsPageSource,
    private val activeProfileId: () -> Int,
    private val selectionRevision: () -> Long,
    private val profileChanges: Flow<Int>,
    private val clock: () -> Long
) {
    @Inject constructor(source: TraktRatedKidsSource, profiles: ProfileManager) : this(source,
        { profiles.activeProfileId.value }, { profiles.activeProfileSelectionRevision }, profiles.activeProfileId,
        { System.nanoTime() / 1_000_000L })

    private data class Cached(val page: RatedKidsPage, val expiresAt: Long)
    private val cache = object : LinkedHashMap<String, Cached>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 24
    }
    private val locks = Array(16) { Mutex() }
    private val slots = Semaphore(2)

    val addon: Flow<Addon?> = flow {
        emit(if (!source.configured) null else Addon(id = ADDON_ID, name = "Rated Kids · Trakt",
            version = "1.0.0", description = "Public Trakt discovery with known US ratings suitable through age 7. Not parental controls.",
            logo = null, baseUrl = BASE_URL, types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource("catalog", listOf("movie", "series"), listOf("tt"))),
            idPrefixes = listOf("tt"), catalogs = DESCRIPTORS.map { descriptor ->
                CatalogDescriptor(type = descriptor.type, id = descriptor.id, name = descriptor.label,
                    extra = listOf(CatalogExtra("skip")), extraSupported = listOf("skip"), pageSize = PAGE_SIZE,
                    showInHome = false, hasExplicitShowInHome = true)
            }))
    }

    suspend fun catalog(type: ContentType, catalogId: String, skip: Int, extra: Map<String, String>): CatalogRow = coroutineScope {
        val descriptor = DESCRIPTORS.singleOrNull { it.id == catalogId && it.type == type }
            ?: error("Unknown rated Kids catalog.")
        // The normal catalog cursor is authoritative. Unknown/filter extras cannot change a fixed feed.
        require(extra.keys.all { it == "skip" } && ("skip" !in extra || extra.getValue("skip").toIntOrNull() == skip)) {
            "Unknown rated Kids catalog options."
        }
        require(skip >= 0 && skip % PAGE_SIZE == 0 && skip < PAGE_SIZE * MAX_PAGES) { "Unknown rated Kids catalog page." }
        require(source.configured) { "Syclop's public Trakt application is not configured." }
        val profile = activeProfileId()
        val revision = selectionRevision()
        val current = { profile == activeProfileId() && revision == selectionRevision() }
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("The active catalog profile changed.")
        }
        checkCurrent()
        val pageNumber = skip / PAGE_SIZE + 1
        val key = "$catalogId:$pageNumber"
        val result = async(start = CoroutineStart.LAZY) {
            locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                checkCurrent()
                synchronized(cache) { cache[key]?.takeIf { it.expiresAt > clock() }?.page }?.let {
                    checkCurrent(); return@withLock it
                }
                val page = slots.withPermit {
                    checkCurrent()
                    withTimeout(18_000L) { source.page(descriptor, pageNumber) }
                }
                checkCurrent()
                require(page.items.size <= PAGE_SIZE) { "Rated Kids catalog exceeded its row limit." }
                val bounded = page.copy(hasMore = page.hasMore && pageNumber < MAX_PAGES)
                val now = clock()
                synchronized(cache) { cache[key] = Cached(bounded, if (now > Long.MAX_VALUE - CACHE_TTL_MS) Long.MAX_VALUE else now + CACHE_TTL_MS) }
                bounded
            }
        }
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
            profileChanges.collect { if (!current()) result.cancel(CancellationException("The active catalog profile changed.")) }
        }
        try {
            val page = result.await()
            checkCurrent()
            CatalogRow(addonId = ADDON_ID, addonName = "Trakt", addonBaseUrl = BASE_URL,
                catalogId = catalogId, catalogName = descriptor.label, type = type, items = page.items,
                hasMore = page.hasMore, supportsSkip = true, skipStep = PAGE_SIZE,
                currentPage = skip / PAGE_SIZE, nextSkip = skip + PAGE_SIZE)
        } finally {
            monitor.cancel()
            result.cancel()
        }
    }

    companion object {
        const val ADDON_ID = "syclop.rated-kids"
        const val BASE_URL = "syclop-kids://catalog"
        const val PAGE_SIZE = 25
        const val MAX_PAGES = 8
        internal const val CACHE_TTL_MS = 60 * 60 * 1000L
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        fun isNativeAddon(url: String) = url.trimEnd('/').equals(BASE_URL, ignoreCase = true)
        fun isNativeItemId(id: String) = id.matches(Regex("tt[0-9]{5,12}"))
        internal val DESCRIPTORS = listOf(
            RatedKidsDescriptor("family-movies", "Trakt · Family movies", ContentType.MOVIE, "family"),
            RatedKidsDescriptor("family-shows", "Trakt · Family shows", ContentType.SERIES, "children,family"),
            RatedKidsDescriptor("education-shows", "Trakt · Education", ContentType.SERIES, "children,family,special-interest", "education,educational", RatedKidsTopic.EDUCATION),
            RatedKidsDescriptor("science-shows", "Trakt · Science", ContentType.SERIES, "documentary", "engineering,technology,science", RatedKidsTopic.SCIENCE),
            RatedKidsDescriptor("nature-shows", "Trakt · Nature", ContentType.SERIES, "documentary", "nature,nature-documentary,natural-history,animals,wildlife", RatedKidsTopic.NATURE),
            RatedKidsDescriptor("documentary-shows", "Trakt · Documentaries", ContentType.SERIES, "documentary", topic = RatedKidsTopic.DOCUMENTARY)
        )
    }
}
