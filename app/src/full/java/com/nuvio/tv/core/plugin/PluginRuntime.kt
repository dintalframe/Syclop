package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.LocalScraperResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRuntime @Inject constructor() {
    @Suppress("UNUSED_PARAMETER")
    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any> = emptyMap()
    ): List<LocalScraperResult> {
        throw UnsupportedOperationException(RemoteExecutablePluginPolicy.MESSAGE)
    }
}
