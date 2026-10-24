package com.nuvio.tv.core.plugin

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtractorRegistry
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.RemotePluginInfo
import io.mockk.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RemoteExecutablePluginPolicyTest {
    @Test fun `stored and requested executable plugin enable cannot make manager run or download`() = runBlocking {
        val manager = PluginManager()
        manager.setPluginsEnabled(true)
        manager.toggleAllScrapersForRepo("old-saved-repository", true)
        manager.reconcileWithRemoteRepoUrls(listOf(RemotePluginInfo("https://example.test/saved")))
        assertFalse(BuildConfig.FEATURE_PLUGINS_ENABLED)
        assertFalse(manager.pluginsEnabled.first())
        assertTrue(manager.repositories.first().isEmpty()); assertTrue(manager.enabledScrapers.first().isEmpty())
        assertTrue(manager.addRepository("https://example.test/arbitrary-code").isFailure)
        assertTrue(manager.refreshRepository("saved").isFailure)
        assertTrue(manager.testScraper("old-cached-scraper").isFailure)
        assertTrue(manager.executeScrapers("603", "movie").isEmpty())
        assertTrue(manager.executeScrapersStreaming("603", "movie").toList().isEmpty())
    }

    @Test fun `actual JS runtime denies downloaded code before native evaluation or network`() = runBlocking {
        MockWebServer().use { server ->
            val failure = runCatching {
                PluginRuntime().executePlugin("fetch('${server.url("/must-not-run")}'); throw new Error('downloaded code ran')",
                    "603", "movie", null, null, "saved-js")
            }.exceptionOrNull()
            blocked(failure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `DEX loader refuses downloads before any storage runtime or extractor access`() = runBlocking {
        val h = LoaderFixture()
        blocked(runCatching { h.loader.downloadExtension("saved", "https://example.test/plugin.cs3") }.exceptionOrNull())
        verify { h.context wasNot Called; h.registry wasNot Called }
    }

    @Test fun `DEX cache cannot bypass getApi or ordinary load policy`() {
        val h = LoaderFixture(); val cachedApi = mockk<MainAPI>()
        @Suppress("UNCHECKED_CAST")
        val cache = ExternalExtensionLoader::class.java.getDeclaredField("apiCache").apply { isAccessible = true }
            .get(h.loader) as ConcurrentHashMap<String, MainAPI>
        cache["saved"] = cachedApi
        blocked(runCatching { h.loader.getApi("saved") }.exceptionOrNull())
        blocked(runCatching { h.loader.loadExtension("saved") }.exceptionOrNull())
        verify { cachedApi wasNot Called; h.context wasNot Called; h.registry wasNot Called }
    }

    @Test fun `diagnostic test and extractor preload cannot instantiate cached DEX classes`() {
        val h = LoaderFixture()
        blocked(runCatching { h.loader.loadExtensionWithDiagnostics("saved", TestDiagnostics()) }.exceptionOrNull())
        blocked(runCatching { h.loader.ensureExtractorsLoaded(listOf("saved"), TestDiagnostics()) }.exceptionOrNull())
        verify { h.context wasNot Called; h.registry wasNot Called }
    }

    @Test fun `runner direct and diagnostic routes deny before metadata lookup or extension invocation`() = runBlocking {
        val loader = mockk<ExternalExtensionLoader>(); val registry = mockk<ExternalExtractorRegistry>()
        val metadata = mockk<TmdbMetadataService>(); val tmdb = mockk<TmdbService>()
        val runner = ExternalExtensionRunner(loader, registry, metadata, tmdb)
        blocked(runCatching { runner.execute("saved", "603", "movie", null, null) }.exceptionOrNull())
        blocked(runCatching { runner.executeWithDiagnostics("saved", "603", "movie", null, null, TestDiagnostics()) }.exceptionOrNull())
        verify { loader wasNot Called; registry wasNot Called; metadata wasNot Called; tmdb wasNot Called }
    }

    @Test fun `extractor direct route cannot execute previously registered extension code`() = runBlocking {
        val registry = ExternalExtractorRegistry()
        blocked(runCatching { registry.resolveExtractor("https://example.test/stream", null, {}, {}) }.exceptionOrNull())
    }

    private fun blocked(failure: Throwable?) {
        assertTrue(failure?.javaClass == UnsupportedOperationException::class.java)
        assertEquals(RemoteExecutablePluginPolicy.MESSAGE, failure?.message)
    }
    private class LoaderFixture {
        val context = mockk<Context>()
        val registry = mockk<ExternalExtractorRegistry>()
        val loader = ExternalExtensionLoader(context, registry)
    }
}
