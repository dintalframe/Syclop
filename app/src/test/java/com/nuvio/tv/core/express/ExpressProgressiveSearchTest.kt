package com.nuvio.tv.core.express

import com.google.gson.JsonObject
import com.nuvio.tv.core.anime.NativeAnimeTitleIdentity
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.SourceSearchReason
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual discovery/HTTP/parser admission; no real provider or account calls. */
class ExpressProgressiveSearchTest {
    @Test fun `healthy sources emit before a pending provider and collector cancellation cancels HTTP`() = runBlocking {
        MockWebServer().use { server ->
            val cancellations = AtomicInteger()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancellations.incrementAndGet() }
            }).build()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, listOf("slow", "healthy")))
                    request.path!!.startsWith("/slow/") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else -> json(rows("Show.S01E04.mkv"))
                }
            }
            val adapter = adapter(client)
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val first = withTimeout(3_000) {
                adapter.searchProgressively(addon, request()).first { it.streams.isNotEmpty() }
            }
            assertEquals("Show.S01E04.mkv", first.streams.single().title)
            assertFalse(first.report.complete)
            assertTrue(cancellations.get() >= 1)
        }
    }

    @Test fun `localized followup query returns sources while a same-origin provider still waits for its first response`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, listOf("slow", "healthy")))
                    request.path!!.startsWith("/slow/") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    request.requestUrl?.queryParameter("q")?.contains("Bölüm") == true -> json(rows("Show.S01E04.mkv"))
                    else -> json("""{"streams":[]}""")
                }
            }
            val adapter = adapter(metadata = ExpressMetadataSource {
                media().copy(originalTitle = "Türkçe Başlık", preferOriginalTitle = true)
            })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val update = withTimeoutOrNull(3_000) {
                adapter.searchProgressively(addon, request()).first { it.streams.isNotEmpty() }
            }
            assertEquals("Show.S01E04.mkv", update?.streams?.singleOrNull()?.title)
            assertFalse(update!!.report.complete)
        }
    }

    @Test fun `invalid subpage URL leaves a later valid source available`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(config(server, listOf("example"), subpages = true)))
            server.enqueue(json("""{"streams":[
                {"title":"Show.S01E04.720p","url":"http://user@example.org/detail"},
                {"title":"Show.S01E04.mkv","infoHash":"$HASH"}]}"""))
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val update = adapter.searchProgressively(addon, request()).toList().last()

            assertEquals(listOf("Show.S01E04.mkv"), update.streams.map { it.title })
            assertEquals(1, update.report.completed)
            assertEquals(1, update.report.reasons[SourceSearchReason.INVALID_HASH])
            assertNull(update.report.reasons[SourceSearchReason.REQUEST_FAILED])
        }
    }

    @Test fun `movie search rejects explicit unrelated release labels and retains aliases and sparse rows`() = runBlocking {
        MockWebServer().use { server ->
            val providerConfig = parseExpressObject(config(server, listOf("example"))).apply {
                getAsJsonObject("example").add("movie", parseExpressObject(
                    """{"query":"/search?q={query}","keywords":"{imdbId}"}"""))
            }
            server.enqueue(json(providerConfig.toString()))
            server.enqueue(json("""{"streams":[
                {"title":"Other.Film.2020.2160p.mkv\nSeeds: 100","infoHash":"${"1".repeat(40)}"},
                {"title":"Selected Film","behaviorHints":{"filename":"Other.Film.2020.2160p.mkv"},"infoHash":"${"2".repeat(40)}"},
                {"title":"[Group] Seçilen.Film.2020.IMAX.2160p.mkv","infoHash":"${"3".repeat(40)}"},
                {"title":"Alternate.Film.2020.2160p.mkv","infoHash":"${"4".repeat(40)}"},
                {"title":"High Quality 2160p HDR","infoHash":"${"5".repeat(40)}"},
                {"behaviorHints":{"filename":"Downloads/2160p.mkv"},"infoHash":"${"6".repeat(40)}"},
                {"title":"Selected.Film.2.2020.2160p.mkv","infoHash":"${"7".repeat(40)}"}]}"""))
            val adapter = adapter(metadata = ExpressMetadataSource {
                ExpressMedia("movie", "Selected Film", "tt1", 2020, null, null,
                    originalTitle = "Seçilen Film", titleAliases = listOf("Alternate Film"))
            })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val update = adapter.searchProgressively(addon, ExpressMediaRequest("movie", "tt1")).toList().last()

            assertEquals(listOf('3', '4', '5', '6').map { it.toString().repeat(40) }, update.streams.map { it.infoHash })
            assertEquals(3, update.report.reasons[SourceSearchReason.TITLE_MISMATCH])
        }
    }

    @Test fun `every provider gets first round before subpages and host cap prevents monopoly`() = runBlocking {
        MockWebServer().use { server ->
            val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
            val names = (1..12).map { "provider$it" }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/config") return json(config(server, names, subpages = true))
                    paths += request.path!!
                    return if (request.path!!.contains("/search")) json("""{"streams":[${(1..20).joinToString { """{"title":"Show.S01E04.$it.mkv","url":"/detail/${request.path!!.substringBefore("/search").removePrefix("/")}/$it"}""" }}]}""")
                    else MockResponse().setBody("<a href=\"magnet:?xt=urn:btih:$HASH\">source</a>")
                }
            }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val updates = withTimeout(10_000) { adapter.searchProgressively(addon, request()).toList() }
            assertEquals(30, paths.size)
            assertEquals(names.toSet(), paths.take(12).map { it.substringBefore("/search").removePrefix("/") }.toSet())
            assertTrue(paths.take(12).all { it.contains("/search") })
            val detailCounts = paths.drop(12).groupingBy { it.split('/')[2] }.eachCount().values
            assertTrue(detailCounts.max() - detailCounts.min() <= 1)
            assertTrue(updates.last().report.complete)
            assertTrue(updates.last().report.reasons.getValue(SourceSearchReason.REQUEST_BUDGET) > 0)
            assertTrue(updates.any { it.streams.isNotEmpty() && !it.report.complete })
        }
    }

    @Test fun `single provider cannot use more than nine query and detail requests`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, listOf("example"), subpages = true))
                    request.path!!.contains("/search") -> json("""{"streams":[${(1..20).joinToString { """{"title":"Show.S01E04.$it","url":"/detail/$it"}""" }}]}""")
                    else -> MockResponse().setBody("<a href=\"magnet:?xt=urn:btih:$HASH\">source</a>")
                }
            }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = withTimeout(10_000) { adapter.searchProgressively(addon, request()).toList().last() }
            assertEquals(10, server.requestCount) // configuration plus nine search/detail requests
            assertEquals(1, last.report.reasons[SourceSearchReason.REQUEST_BUDGET])
        }
    }

    @Test fun `request local reports count hidden packs invalid hashes duplicates and parser failures`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, listOf("good", "broken")))
                    request.path!!.startsWith("/broken/") -> json("not JSON")
                    else -> json("""{"streams":[
                        {"title":"Show.S01-S12.Complete","infoHash":"$HASH"},
                        {"title":"Show.S01E04.mkv","infoHash":"$HASH"},
                        {"title":"Show.S01E04.duplicate.mkv","infoHash":"$HASH"},
                        {"title":"Show.S01E04.invalid.mkv","infoHash":"invalid"}]}""")
                }
            }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val updates = withTimeout(10_000) { adapter.searchProgressively(addon, request()).toList() }
            val last = updates.last()
            assertEquals(1, last.streams.size)
            assertEquals(1, last.report.reasons[SourceSearchReason.SEASON_PACK_HIDDEN])
            assertEquals(1, last.report.reasons[SourceSearchReason.INVALID_HASH])
            assertEquals(1, last.report.reasons[SourceSearchReason.DUPLICATE])
            assertEquals(1, last.report.reasons[SourceSearchReason.PARSER_ERROR])
            assertFalse(last.report.summary().contains(server.url("/").toString()))
            assertFalse(last.report.summary().contains(HASH))
        }
    }

    @Test fun `missing native mapping terminates with structured diagnostic before provider requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(config(server, listOf("example"))))
            val adapter = adapter(metadata = ExpressMetadataSource { throw ExpressAnimeMappingUnavailable() })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = adapter.searchProgressively(addon, ExpressMediaRequest("series", "mal:1:1", 1, 1)).toList().last()
            assertEquals(1, server.requestCount)
            assertTrue(last.report.complete)
            assertEquals(1, last.report.reasons[SourceSearchReason.MISSING_MAPPING])
            assertNull(last.report.reasons[SourceSearchReason.ANIME_METADATA_FAILED])
            assertNotNull(last.error)
        }
    }

    @Test fun `MAL catalog request failure does not claim an episode mapping is missing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(config(server, listOf("example"))))
            val adapter = adapter(metadata = ExpressMetadataSource { throw IOException("private request details") })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = adapter.searchProgressively(addon, ExpressMediaRequest("series", "mal:1:1", 1, 1)).toList().last()

            assertEquals(1, server.requestCount)
            assertTrue(last.report.complete)
            assertEquals(0, last.report.completed)
            assertNull(last.report.reasons[SourceSearchReason.MISSING_MAPPING])
            assertEquals(1, last.report.reasons[SourceSearchReason.ANIME_METADATA_FAILED])
            assertEquals("MyAnimeList details could not be verified.", last.error)
        }
    }

    @Test fun `request limit is visible even when a provider responded without a usable source`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, listOf("example"), subpages = true))
                    request.path!!.contains("/search") -> json("""{"streams":[${(1..9).joinToString { """{"title":"Show.S01E04.$it","url":"/detail/$it"}""" }}]}""")
                    else -> MockResponse().setBody("<html>No source here</html>")
                }
            }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = withTimeout(10_000) { adapter.searchProgressively(addon, request()).toList().last() }

            assertEquals(10, server.requestCount)
            assertEquals(1, last.report.completed)
            assertEquals(1, last.report.reasons[SourceSearchReason.REQUEST_BUDGET])
            assertTrue(last.streams.isEmpty())
            assertEquals(last.report.summary(), last.error)
        }
    }

    @Test fun `successful empty provider response remains a completed search without an error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(config(server, listOf("example"))))
            server.enqueue(json("""{"streams":[]}"""))
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = withTimeout(10_000) { adapter.searchProgressively(addon, request()).toList().last() }

            assertEquals(2, server.requestCount)
            assertEquals(1, last.report.completed)
            assertTrue(last.report.reasons.isEmpty())
            assertTrue(last.streams.isEmpty())
            assertNull(last.error)
        }
    }

    @Test fun `deadline preserves admitted results and counts pending provider timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, listOf("healthy", "slow")))
                    request.path!!.startsWith("/slow/") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else -> json(rows("Show.S01E04.mkv"))
                }
            }
            val adapter = ExpressAddonAdapter(ExpressHttpClient(OkHttpClient(), true),
                ExpressMetadataSource { media() }, System::currentTimeMillis, searchTimeoutMs = 1_000L)
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val updates = withTimeout(3_000) { adapter.searchProgressively(addon, request()).toList() }
            assertTrue(updates.any { it.streams.size == 1 && !it.report.complete })
            assertEquals(1, updates.last().streams.size)
            assertTrue(updates.last().report.timedOut)
            assertTrue(updates.last().report.complete)
            assertEquals(1, updates.last().report.reasons[SourceSearchReason.TIMEOUT])
            assertEquals(1, updates.last().report.completed)
            assertEquals(0, updates.last().report.failed)
            assertNull(updates.last().error)
        }
    }

    @Test fun `redirect hops count against provider request budget`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/config") json(config(server, listOf("example")).replace(
                        "\"keywords\":\"{title} {episodeCode}\"",
                        "\"keywords\":[\"{title} {episodeCode}\",\"{title} {episodeCode} 1080p\",\"{title} {episodeCode} 720p\"]"))
                    else MockResponse().setResponseCode(302).setHeader("Location", "/another-hop")
            }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = withTimeout(10_000) { adapter.searchProgressively(addon, request()).toList().last() }
            assertEquals(10, server.requestCount) // one configuration and nine physical provider GETs
            assertEquals(1, last.report.reasons[SourceSearchReason.REQUEST_BUDGET])
        }
    }

    @Test fun `native title mismatch is counted before a valid anime row is admitted`() = runBlocking {
        MockWebServer().use { server ->
            val providerConfig = parseExpressObject(config(server, listOf("example"))).apply {
                getAsJsonObject("example").add("anime", parseExpressObject(
                    """{"query":"/search?q={query}","keywords":"{title} {absoluteNumber}"}"""))
            }.toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/config") json(providerConfig) else json("""{"streams":[
                        {"title":"Other.Show.29.mkv","infoHash":"${"b".repeat(40)}"},
                        {"title":"Frieren.29.mkv","infoHash":"$HASH"}]}""")
            }
            val selected = media().copy(title = "Frieren", anime = true, absoluteNumber = 29,
                nativeAnimeIdentity = NativeAnimeTitleIdentity.create(1, "Frieren", emptyList()))
            val adapter = adapter(metadata = ExpressMetadataSource { selected })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val last = adapter.searchProgressively(addon, ExpressMediaRequest("series", "mal:1:1", 1, 1)).toList().last()
            assertEquals(listOf("Frieren.29.mkv"), last.streams.map { it.title })
            assertEquals(1, last.report.reasons[SourceSearchReason.TITLE_MISMATCH])
        }
    }

    @Test fun `same addon concurrent searches retain their own reports and sources`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, listOf("example")))
                    request.path!!.contains("s01e04") -> json(rows("Show.S01E04.mkv"))
                    else -> json("""{"streams":[{"title":"Show.Complete.Season1","infoHash":"$HASH"}]}""")
                }
            }
            val adapter = adapter(metadata = ExpressMetadataSource { request -> media().copy(episode = request.episode) })
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val a = async { adapter.searchProgressively(addon, request()).toList().last() }
            val b = async { adapter.searchProgressively(addon, request().copy(episode = 5)).toList().last() }
            assertEquals(1, a.await().streams.size)
            assertTrue(b.await().streams.isEmpty())
            assertNull(a.await().report.reasons[SourceSearchReason.SEASON_PACK_HIDDEN])
            assertEquals(1, b.await().report.reasons[SourceSearchReason.SEASON_PACK_HIDDEN])
        }
    }

    private fun adapter(client: OkHttpClient = OkHttpClient(), metadata: ExpressMetadataSource = ExpressMetadataSource { media() }) =
        ExpressAddonAdapter(ExpressHttpClient(client, true), metadata, System::currentTimeMillis)
    private fun media() = ExpressMedia("series", "Show", "tt1", 2020, 1, 4)
    private fun request() = ExpressMediaRequest("series", "tt1:1:4", 1, 4)
    private fun rows(title: String) = """{"streams":[{"title":"$title","infoHash":"$HASH"}]}"""
    private fun config(server: MockWebServer, names: List<String>, subpages: Boolean = false) = JsonObject().apply {
        add("_manifest", parseExpressObject("""{"name":"Synthetic discovery"}"""))
        names.forEach { name -> add(name, JsonObject().apply {
            addProperty("name", name); addProperty("base_url", server.url("/$name").toString())
            add("episode", parseExpressObject("""{"query":"/search?q={query}","keywords":"{title} {episodeCode}"}"""))
            add("json_format", parseExpressObject("""{"results":"streams","title":"title","url":"url","hash":"infoHash"}"""))
            if (subpages) addProperty("source_is_in_sub_page", true)
        }) }
    }.toString()
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private companion object { const val HASH = "0123456789012345678901234567890123456789" }
}
