package com.nuvio.tv.core.express

import com.google.gson.JsonParser
import com.nuvio.tv.core.network.NetworkResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

class ExpressAdapterTest {
    @Test
    fun `vendor discovery honors publisher default instead of choosing another variant`() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/vendor" -> json("""{"packages":[{"manifest":"/lite"},{"manifest":"/full"}],"defaults":{"packages":["/lite"]}}""")
                    "/lite" -> json("""{"type":"express","url":"/lite-config"}""")
                    "/lite-config" -> json(config(server, name = "Publisher Lite"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val addon = (adapter(server).discover(server.url("/vendor").toString()) as NetworkResult.Success).data
            assertTrue(addon.id.startsWith(ExpressAddonAdapter.ID_PREFIX))
            assertEquals("Publisher Lite", addon.name)
            assertEquals(3, server.requestCount)
            assertEquals("/vendor", server.takeRequest().path)
            assertEquals("/lite", server.takeRequest().path)
            assertEquals("/lite-config", server.takeRequest().path)
        }
    }

    @Test
    fun `explicit manifest URL preserves requested full variant`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"type":"express","url":"/full-config"}"""))
            server.enqueue(json(config(server, "Explicit Full")))
            val addon = (adapter(server).discover(server.url("/manifest/full").toString()) as NetworkResult.Success).data
            assertEquals("Explicit Full", addon.name)
            assertEquals("/manifest/full", server.takeRequest().path)
            assertEquals("/full-config", server.takeRequest().path)
        }
    }

    @Test
    fun `downloaded executable package is rejected without fetching its script`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"type":"kosmos","url":"/never-execute.js"}"""))
            val result = adapter(server).discover(server.url("/manifest").toString())
            assertTrue(result is NetworkResult.Error)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `JSON sources preserve actual file indices trackers filenames and deduplicate per file`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(config(server)))
            server.enqueue(json("""{"streams":[{"infoHash":"$HASH","fileIdx":0,"behaviorHints":{"filename":"episode0.mkv","videoSize":123},"sources":["tracker:https://tracker.example/a","dht:ignored"]},{"infoHash":"$HASH","fileIdx":1,"behaviorHints":{"filename":"episode1.mkv"}},{"infoHash":"$HASH","fileIdx":0,"behaviorHints":{"filename":"episode0.mkv"}}]}"""))
            val adapter = adapter(server)
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withContext(Dispatchers.IO) { adapter.search(addon, ExpressMediaRequest("movie", "tt123")) } as NetworkResult.Success
            assertEquals(2, result.data.size)
            assertEquals(listOf(0, 1), result.data.map { it.fileIdx })
            assertEquals("episode0.mkv", result.data.first().behaviorHints?.filename)
            assertEquals(listOf("tracker:https://tracker.example/a"), result.data.first().sources)
            repeat(2) { assertNull(server.takeRequest().getHeader("Authorization")) }
            assertEquals(1, adapter.lastReport(addon)?.completed)
        }
    }

    @Test
    fun `unknown file index remains unknown instead of silently selecting file zero`() {
        val definition = JsonParser.parseString(jsonDefinition("https://example.org/source")).asJsonObject
        val row = ExpressParser.parse(definition, """{"streams":[{"infoHash":"$HASH","title":"Release title"}]}""").single()
        assertNull(row.fileIdx)
        assertNull(row.toStream("Example")?.fileIdx)
        assertNull(row.toStream("Example")?.behaviorHints?.filename)
    }

    @Test
    fun `metadata templates encode title characters without changing exact episode coordinates`() {
        val adapter = ExpressAddonAdapter(ExpressHttpClient(OkHttpClient(), true), ExpressMetadataSource { media() }, System::currentTimeMillis)
        val provider = JsonParser.parseString("""{"base_url":"https://example.org/prefix","episode":{"query":"/search?q={query}&season={season}&episode={episode}","keywords":"{title} {episodeCode}"}}""").asJsonObject
        val result = adapter.providerQueries(provider, media().copy(type = "series", title = "A & B/海外", season = 2, episode = 3)).single()
        assertTrue(result.contains("A%20%26%20B%2F"))
        assertTrue(result.contains("s02e03"))
        assertTrue(result.endsWith("season=2&episode=3"))
        assertTrue(result.contains("/prefix/search?"))
    }

    @Test
    fun `anime absolute episode is never guessed from season and episode`() = runTest {
        MockWebServer().use { server ->
            val provider = """{"name":"Anime example","base_url":"${server.url("/search")}","anime":{"query":"/?q={query}","keywords":"{title} {absoluteNumber}"},"json_format":{"results":"streams","hash":"infoHash"}}"""
            server.enqueue(json("""{"_manifest":{"name":"Anime example"},"example":$provider}"""))
            val adapter = adapter(server, media().copy(type = "series", anime = true, season = 2, episode = 1))
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            assertTrue(withContext(Dispatchers.IO) { adapter.search(addon, ExpressMediaRequest("anime", "tt123:2:1")) } is NetworkResult.Error)
            assertEquals(1, adapter.lastReport(addon)?.missingMetadata)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `unsupported expression is counted and never executed or requested`() = runTest {
        MockWebServer().use { server ->
            // A compatible series provider keeps this mixed package valid; the movie expression
            // still must be counted/skipped without executing or issuing a request.
            server.enqueue(json("""{"_manifest":{"name":"Mixed declarative"},"compatible":{"base_url":"${server.url("/unused")}","episode":{"query":"/?q={query}","keywords":"{title}"},"json_format":{"results":"streams","hash":"infoHash"}},"example":{"base_url":"${server.url("/search")}","movie":{"query":"/?q={query}","keywords":"{title}"},"html_parser":{"row":"doc.querySelectorAll('tr')","title":"eval('downloaded code')"}}}"""))
            val adapter = adapter(server)
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            assertTrue(withContext(Dispatchers.IO) { adapter.search(addon, ExpressMediaRequest("movie", "tt123")) } is NetworkResult.Error)
            assertEquals(1, adapter.lastReport(addon)?.unsupported)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `HTML CSS text attributes and digit match expressions are interpreted declaratively`() {
        val provider = JsonParser.parseString("""{"html_parser":{"row":"doc.querySelectorAll('tr.default')","title":"row.querySelector('td:nth-child(2) a:last-child').textContent","size":"row.querySelector('td:nth-child(3)').textContent","seeds":"row.querySelector('td:nth-child(4)').textContent.match(/\\d+/g)[0]","url":"row.querySelector('a[href^=\"magnet:?xt=urn\"]').getAttribute('href')"}}""").asJsonObject
        assertTrue(ExpressParser.supported(provider))
        val rows = ExpressParser.parse(provider, """<table><tr class="default"><td><a href="magnet:?xt=urn:btih:$HASH&amp;tr=https%3A%2F%2Ftracker.example">Magnet</a></td><td><a>Ignore</a><a>Episode.mkv</a></td><td>1.5 GiB</td><td>Seeds 12 Peers 3</td></tr></table>""")
        assertEquals("Episode.mkv", rows.single().title)
        assertEquals("12", rows.single().seeds)
        assertEquals(1610612736L, rows.single().size)
        assertEquals(HASH, rows.single().toStream("Example")?.infoHash)
    }

    @Test
    fun `RSS attribute selectors preserve source magnet`() {
        val provider = JsonParser.parseString("""{"html_parser":{"row":"doc.querySelectorAll('rss > channel > item')","title":"row.querySelector('title').textContent","url":"row.querySelector('[name=\"magneturl\"]').getAttribute('value')"}}""").asJsonObject
        val rows = ExpressParser.parse(provider, """<rss><channel><item><title>Example</title><attr name="magneturl" value="magnet:?xt=urn:btih:$HASH"/></item></channel></rss>""")
        assertEquals(HASH, rows.single().toStream("Example")?.infoHash)
    }

    @Test
    fun `parent session cancellation cancels in-flight provider calls`() = runTest {
        MockWebServer().use { server ->
            val cancelled = AtomicInteger()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.incrementAndGet() }
            }).build()
            val adapter = ExpressAddonAdapter(ExpressHttpClient(client, true), ExpressMetadataSource { media() }, System::currentTimeMillis)
            server.enqueue(json(config(server)))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            server.takeRequest()
            val job = launch(Dispatchers.Default) { adapter.search(addon, ExpressMediaRequest("movie", "tt123")) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(cancelled.get() >= 1)
        }
    }

    @Test
    fun `response size limit rejects oversized documents`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("x".repeat(100)))
            try {
                ExpressHttpClient(OkHttpClient(), true).get(server.url("/").toString(), maxBytes = 32)
                fail("Expected size rejection")
            } catch (_: ExpressException) { }
        }
    }

    @Test
    fun `provider client never runs more than three concurrent requests`() = runTest {
        MockWebServer().use { server ->
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val current = active.incrementAndGet()
                    maximum.accumulateAndGet(current, ::maxOf)
                    Thread.sleep(75)
                    active.decrementAndGet()
                    return json("{}")
                }
            }
            val http = ExpressHttpClient(OkHttpClient(), true)
            (1..8).map { async { http.get(server.url("/$it").toString()) } }.awaitAll()
            assertTrue(maximum.get() <= 3)
            assertTrue(maximum.get() >= 2)
        }
    }

    @Test
    fun `production URLs require HTTPS and reject embedded credentials`() {
        val http = ExpressHttpClient()
        for (url in listOf("http://example.org/config", "https://user:password@example.org/config")) {
            try { http.validatedUrl(url); fail("Expected URL rejection") } catch (_: ExpressException) { }
        }
    }


    @Test
    fun `one provider response failure does not cancel healthy sources`() = runTest {
        MockWebServer().use { server ->
            val bad = jsonDefinition(server.url("/broken").toString())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json("""{"_manifest":{"name":"Mixed package"},"bad":$bad,"healthy":${jsonDefinition(server.url("/backend").toString())}}""")
                    request.path?.startsWith("/broken/") == true -> MockResponse().setResponseCode(500)
                    else -> json("""{"streams":[{"infoHash":"$HASH","fileIdx":2,"behaviorHints":{"filename":"Big.Buck.Bunny.2008.mkv"}}]}""")
                }
            }
            val adapter = adapter(server, ExpressMedia("movie", "Big Buck Bunny", "tt1254207", 2008, null, null))
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withContext(Dispatchers.IO) {
                adapter.search(addon, ExpressMediaRequest("movie", "tt1254207"))
            } as NetworkResult.Success
            assertEquals(1, result.data.size)
            assertEquals(2, result.data.single().fileIdx)
            assertEquals(1, adapter.lastReport(addon)?.failed)
            assertEquals(1, adapter.lastReport(addon)?.completed)
            assertEquals(3, server.requestCount)
        }
    }

    private fun adapter(server: MockWebServer, metadata: ExpressMedia = media()) =
        ExpressAddonAdapter(ExpressHttpClient(OkHttpClient(), true), ExpressMetadataSource { metadata }, System::currentTimeMillis)

    private fun config(server: MockWebServer, name: String = "Example") =
        """{"_manifest":{"name":"$name","version":1},"example":${jsonDefinition(server.url("/backend").toString())}}"""

    private fun jsonDefinition(base: String) = """{"name":"Example","base_url":"$base","movie":{"query":"/search?q={query}","keywords":"{imdbId}"},"json_format":{"results":"streams","title":"title","hash":"infoHash","size":"behaviorHints.videoSize"}}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun media() = ExpressMedia("movie", "Verified title", "tt123", 2020, null, null)
    private companion object { const val HASH = "0123456789012345678901234567890123456789" }
}
