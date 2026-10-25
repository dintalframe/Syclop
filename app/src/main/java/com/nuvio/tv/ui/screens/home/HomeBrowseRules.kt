package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mal.MalCatalog
import com.nuvio.tv.data.catalog.RatedKidsCatalog
import com.nuvio.tv.data.simkl.catalog.SimklPublicCatalog
import com.nuvio.tv.domain.model.*

enum class HomeBrowseSection(val label: String) {
    HOME("Home"), MOVIES("Movies"), TV("TV"), ANIME("Anime"), DOCUMENTARY("Documentary"), KIDS("Kids")
}

internal enum class KidsBrowseGroup(val label: String) {
    SCIENCE("Science"), NATURE("Nature"), EDUCATION("Education"), DOCUMENTARY("Documentaries"),
    MOVIES("Family movies"), SHOWS("Family shows")
}

internal data class HomeBrowseRequest(val addon: Addon, val catalog: CatalogDescriptor, val extraArgs: Map<String, String>) {
    val key: String get() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(listOf(addon.id, addon.baseUrl, catalog.apiType, catalog.id).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun toString() = "HomeBrowseRequest(sectionCatalog=[redacted])"
}

/** Explicit metadata only. Animation, title words, language and country do not prove Anime. */
internal object HomeBrowseRules {
    private val animeId = Regex("(?i)^(?:mal|kitsu|anilist|anidb):[0-9]+(?:$|:)")
    private val animePrefixes = setOf("mal:", "kitsu:", "anilist:", "anidb:")
    private val documentaryGenres = setOf("documentary", "documentaries", "docuseries")
    private val scienceGenres = setOf("science", "science & technology", "science and technology")
    private val natureGenres = setOf("nature", "wildlife", "natural-history", "nature-documentary", "animal-documentary", "science & nature", "science and nature")
    private val educationGenres = setOf("education", "educational", "learning")
    private val matureGenres = setOf("adult", "mature", "hentai", "ecchi", "erotica", "pornography", "pornographic")
    private val browseMediaTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.TV)
    private val kidsRatingSeparators = Regex("[\\s_-]+")
    // Only explicit US certificates have a known meaning here. A bare age number or an
    // international code needs a typed region before it can safely join this whitelist.
    private val kidsRatings = setOf("G", "TVY", "TVY7", "TVY7FV", "TVG")

    fun catalogUrlIdentity(value: String): String {
        val trimmed = value.trim()
        val query = trimmed.indexOf('?')
        var path = (if (query < 0) trimmed else trimmed.substring(0, query)).trimEnd('/')
        if (path.endsWith("/manifest.json", true)) path = path.dropLast("/manifest.json".length).trimEnd('/')
        return path + if (query < 0) "" else trimmed.substring(query)
    }
    fun allowed(addon: Addon, enabledUrls: List<String>): Boolean = addon.enabled &&
        (addon.id == MalCatalog.ADDON_ID && MalCatalog.isNativeAddon(addon.baseUrl) ||
            addon.id == SimklPublicCatalog.ADDON_ID && SimklPublicCatalog.isNativeAddon(addon.baseUrl) ||
            addon.id == RatedKidsCatalog.ADDON_ID && RatedKidsCatalog.isNativeAddon(addon.baseUrl) ||
            enabledUrls.any { catalogUrlIdentity(it) == catalogUrlIdentity(addon.baseUrl) })
    fun isAnime(item: MetaPreview, row: CatalogRow? = null): Boolean =
        item.rawType.equals("anime", true) || animeId.containsMatchIn(item.id) ||
            item.genres.any { it.trim().equals("anime", true) } ||
            row?.addonId == MalCatalog.ADDON_ID || MalCatalog.isNativeAddon(item.sourceAddonBaseUrl.orEmpty()) ||
            (row != null && (row.rawType.equals("anime", true) || MalCatalog.isNativeAddon(row.addonBaseUrl) ||
                row.extraArgs.any { it.key.equals("genre", true) && it.value.trim().equals("anime", true) }))

    private fun hasGenre(item: MetaPreview, row: CatalogRow?, accepted: Set<String>): Boolean =
        item.genres.any { it.trim().lowercase(java.util.Locale.ROOT) in accepted } ||
            row?.extraArgs?.any { (key, value) -> key.equals("genre", true) && value.trim().lowercase(java.util.Locale.ROOT) in accepted } == true

    fun isDocumentary(item: MetaPreview, row: CatalogRow? = null): Boolean =
        hasGenre(item, row, documentaryGenres)

    /** This is a metadata browse filter, not parental controls or a guarantee about a title. */
    fun isRatedForKidsThroughSeven(item: MetaPreview): Boolean {
        val rating = item.ageRating?.trim()?.uppercase(java.util.Locale.ROOT)
            ?.replace(kidsRatingSeparators, "") ?: return false
        return rating in kidsRatings
    }

    fun kidsGroup(item: MetaPreview, row: CatalogRow? = null): KidsBrowseGroup? {
        if (item.type !in browseMediaTypes || !isRatedForKidsThroughSeven(item) || hasGenre(item, row, matureGenres)) return null
        return when {
            hasGenre(item, row, scienceGenres) && (isDocumentary(item, row) || hasGenre(item, row, educationGenres)) -> KidsBrowseGroup.SCIENCE
            hasGenre(item, row, natureGenres) || (isDocumentary(item, row) && hasGenre(item, row, setOf("animals"))) -> KidsBrowseGroup.NATURE
            hasGenre(item, row, educationGenres) -> KidsBrowseGroup.EDUCATION
            isDocumentary(item, row) -> KidsBrowseGroup.DOCUMENTARY
            item.type == ContentType.MOVIE -> KidsBrowseGroup.MOVIES
            item.type == ContentType.SERIES || item.type == ContentType.TV -> KidsBrowseGroup.SHOWS
            else -> null
        }
    }

    fun matches(section: HomeBrowseSection, item: MetaPreview, row: CatalogRow? = null): Boolean = when (section) {
        HomeBrowseSection.HOME -> true
        HomeBrowseSection.ANIME -> isAnime(item, row)
        HomeBrowseSection.DOCUMENTARY -> isDocumentary(item, row)
        HomeBrowseSection.KIDS -> kidsGroup(item, row) != null
        HomeBrowseSection.MOVIES -> item.type == ContentType.MOVIE && !isAnime(item, row)
        HomeBrowseSection.TV -> item.type in setOf(ContentType.SERIES, ContentType.TV) && !isAnime(item, row)
    }

    fun request(section: HomeBrowseSection, addon: Addon, catalog: CatalogDescriptor): HomeBrowseRequest? {
        if (section == HomeBrowseSection.HOME || !addon.enabled) return null
        val required = (catalog.extraRequired + catalog.extra.filter { it.isRequired }.map { it.name }).map { it.lowercase() }.toSet()
        if ("search" in required) return null
        val nativeAnime = addon.id == MalCatalog.ADDON_ID || MalCatalog.isNativeAddon(addon.baseUrl)
        val animePrefix = addon.idPrefixes.any { it.lowercase() in animePrefixes }
        val nativeSimkl = addon.id == SimklPublicCatalog.ADDON_ID && SimklPublicCatalog.isNativeAddon(addon.baseUrl)
        val explicitAnime = nativeAnime || catalog.apiType.equals("anime", true) ||
            (addon.idPrefixes.isNotEmpty() && addon.idPrefixes.all { it.lowercase() in animePrefixes })
        val genreExtra = catalog.extra.firstOrNull { it.name.equals("genre", true) }
        val animeGenre = genreExtra?.options?.firstOrNull { it.trim().equals("anime", true) }
        val documentaryGenre = genreExtra?.options?.firstOrNull {
            it.trim().lowercase(java.util.Locale.ROOT) in documentaryGenres
        }
        val eligible = when (section) {
            HomeBrowseSection.ANIME -> explicitAnime || (animePrefix && !nativeSimkl) || animeGenre != null
            HomeBrowseSection.MOVIES -> catalog.type == ContentType.MOVIE && !explicitAnime
            HomeBrowseSection.TV -> catalog.type in setOf(ContentType.SERIES, ContentType.TV) && !explicitAnime
            HomeBrowseSection.DOCUMENTARY, HomeBrowseSection.KIDS -> catalog.type in browseMediaTypes
            HomeBrowseSection.HOME -> false
        }
        if (!eligible) return null
        val extras = linkedMapOf<String, String>()
        catalog.extra.filterNot { it.name.equals("skip", true) }.forEach { extra -> extra.defaultValue?.takeIf { it.isNotBlank() && it.length <= 2_048 }?.let { extras[extra.name] = it } }
        if (section == HomeBrowseSection.ANIME && animeGenre != null) extras[genreExtra!!.name] = animeGenre
        if (section == HomeBrowseSection.DOCUMENTARY && documentaryGenre != null) extras[genreExtra!!.name] = documentaryGenre
        if (required.any { name -> name != "skip" && extras.keys.none { it.equals(name, true) } }) return null
        return HomeBrowseRequest(addon, catalog, extras)
    }

    /** Filtering never rewrites API type, catalog identity, cursor or upstream item identity. */
    fun scoped(section: HomeBrowseSection, row: CatalogRow): CatalogRow = row.copy(items = row.items.filter { matches(section, it, row) })
}
