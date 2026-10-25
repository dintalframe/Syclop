package com.nuvio.tv.data.catalog

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.ContentType
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Uses Trakt's existing public app-key requests, never executeAuthorizedRequest or account tokens. */
@Singleton
class TraktRatedKidsSource @Inject constructor(
    @Named("ratedKidsTrakt") private val api: TraktApi,
    private val auth: TraktAuthService
) : RatedKidsPageSource {
    override val configured: Boolean get() = auth.hasRequiredCredentials()

    override suspend fun page(descriptor: RatedKidsDescriptor, page: Int): RatedKidsPage {
        require(page in 1..RatedKidsCatalog.MAX_PAGES && descriptor in RatedKidsCatalog.DESCRIPTORS)
        return if (descriptor.type == ContentType.MOVIE) {
            val response = auth.executePublicRequest {
                api.getRatedKidsMovies(descriptor.genres, descriptor.certificates, descriptor.subgenres,
                    page = page, limit = RatedKidsCatalog.PAGE_SIZE)
            } ?: error("Public Trakt discovery is unavailable.")
            require(response.isSuccessful) { "Public Trakt discovery is unavailable." }
            val rows = response.body() ?: error("Public Trakt discovery returned no data.")
            require(rows.size <= RatedKidsCatalog.PAGE_SIZE) { "Public Trakt discovery exceeded its row limit." }
            RatedKidsPage(rows.mapNotNull { RatedKidsCatalogCodec.movie(it, descriptor) }.distinctBy { it.id },
                hasMore(response.headers()["X-Pagination-Page-Count"], page, rows.size))
        } else {
            val response = auth.executePublicRequest {
                api.getRatedKidsShows(descriptor.genres, descriptor.certificates, descriptor.subgenres,
                    page = page, limit = RatedKidsCatalog.PAGE_SIZE)
            } ?: error("Public Trakt discovery is unavailable.")
            require(response.isSuccessful) { "Public Trakt discovery is unavailable." }
            val rows = response.body() ?: error("Public Trakt discovery returned no data.")
            require(rows.size <= RatedKidsCatalog.PAGE_SIZE) { "Public Trakt discovery exceeded its row limit." }
            RatedKidsPage(rows.mapNotNull { RatedKidsCatalogCodec.show(it, descriptor) }.distinctBy { it.id },
                hasMore(response.headers()["X-Pagination-Page-Count"], page, rows.size))
        }
    }

    private fun hasMore(pageCount: String?, page: Int, size: Int): Boolean {
        if (page >= RatedKidsCatalog.MAX_PAGES) return false
        val count = pageCount?.toIntOrNull()?.takeIf { it >= 0 }
        return if (count != null) page < count else size == RatedKidsCatalog.PAGE_SIZE
    }
}
