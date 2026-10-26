package com.nuvio.tv.core.express

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nuvio.tv.core.anime.NativeAnimeTitleIdentity
import com.nuvio.tv.core.network.NetworkResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** Actual adapter HTTP admission: unrelated broad matches cannot consume accepted-result budgets. */
class NativeMalTitleAdmissionTest {
    @Test fun `unrelated first twenty rows cannot hide a later native MAL franchise result`() = runBlocking {
        MockWebServer().use { server ->
            val titles = List(30) { "Night.on.Earth.S01.29.$it.2160p" } + "[Group] Sousou no Frieren - 29 (S02E01).mkv"
            server.dispatcher = dispatcher(server, 1) { rows(titles) }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, request()) } as NetworkResult.Success
            assertEquals(1, result.data.size)
            assertEquals(titles.last(), result.data.single().title)
            assertEquals(1, adapter.lastReport(addon)?.sourceCount)
        }
    }

    @Test fun `hundreds of broad unrelated rows cannot fill global result cap ahead of valid provider`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(server, 5) { request ->
                if (request.path!!.startsWith("/backend4/")) rows(listOf("Sousou.no.Frieren.S02E01.mkv"))
                else rows(List(100) { "WWE.Night.on.Earth.29.Neibolt.Street.$it.S02E01.mkv" })
            }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, request()) } as NetworkResult.Success
            assertEquals(listOf("Sousou.no.Frieren.S02E01.mkv"), result.data.map { it.title })
            assertEquals(1, adapter.lastReport(addon)?.sourceCount)
        }
    }

    @Test fun `unrelated title cannot borrow matching filename and no subpage is fetched`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/config" -> json(config(server, 1, subpage = true))
                    request.path!!.startsWith("/backend") -> json("""{"streams":[{"title":"IT.Welcome.to.Derry.S02E01.29.Neibolt.Street","url":"/must-not-fetch","behaviorHints":{"filename":"Sousou.no.Frieren.S02E01.mkv"}}]}""")
                    else -> throw AssertionError("Unrelated detail page must not be requested")
                }
            }
            val adapter = adapter()
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, request()) } as NetworkResult.Success
            assertTrue(result.data.isEmpty())
            assertTrue((1..server.requestCount).map { server.takeRequest().path }.none { it == "/must-not-fetch" })
        }
    }

    @Test fun `native MAL metadata without trusted title identity stops before any provider request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(config(server, 1)))
            val adapter = adapter(media().copy(nativeAnimeIdentity = null))
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            assertTrue(withTimeout(10_000) { adapter.search(addon, request()) } is NetworkResult.Error)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `only proof bound aliases get bounded query variants without coordinate changes`() {
        val media = media()
        assertEquals(listOf("Sousou no Frieren", "Frieren: Beyond Journey’s End"), media.queryTitles())
        assertEquals(1, media.episode); assertEquals(29, media.absoluteNumber)
        assertEquals("mal:59978:1", request().videoId)
        val noStrip = media.copy(title = "Franchise: Subtitle Part 2", titleAliases = emptyList())
        assertEquals(media.queryTitles(), noStrip.queryTitles())
        val nonNative = media.copy(nativeAnimeIdentity = null)
        assertFalse(nonNative.queryTitles().contains("Sousou no Frieren"))
    }

    @Test fun `admission preserves whole declared title fields and rejects group-only identity or unrelated coordinates`() {
        val media = media()
        for (name in listOf("Night.on.Earth.S02E01.mkv", "IT.Welcome.to.Derry.29.Neibolt.Street.mkv",
            "WWE.Raw.2026.01.29.mkv", "[Sousou no Frieren] Unrelated.Show.S02E01.mkv"))
            assertFalse(name, media.acceptsNativeAnimeRow(ExpressRow(name, null, null, HASH)))
        assertTrue(media.acceptsNativeAnimeRow(ExpressRow("[Group] Sousou.no.Frieren.S02.Complete", null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow(null, null, null, HASH)))
        assertFalse(media.acceptsNativeAnimeRow(ExpressRow("Sousou.no.Frieren.S02E01", null, null, HASH, filename = "Other.Show.S02E01.mkv")))
    }

    @Test fun `non MAL metadata keeps existing provider row admission behavior`() {
        val ordinary = media().copy(anime = false, nativeAnimeIdentity = null)
        assertTrue(ordinary.acceptsNativeAnimeRow(ExpressRow("Different.Title", null, null, HASH)))
    }

    private fun adapter(selected: ExpressMedia = media()) = ExpressAddonAdapter(
        ExpressHttpClient(OkHttpClient(), true), ExpressMetadataSource { selected }, { 0L })
    private fun request() = ExpressMediaRequest("series", "mal:59978:1", 1, 1)
    private fun media() = ExpressMedia("series", "Sousou no Frieren 2nd Season", null, 2026, null, 1, 29, true,
        listOf("Frieren: Beyond Journey’s End", "Fourth Alias"),
        NativeAnimeTitleIdentity.create(59978, "Sousou no Frieren S2", listOf("Frieren: Beyond Journey’s End (2026)"))!!)
    private fun dispatcher(server: MockWebServer, providers: Int, body: (RecordedRequest) -> String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = if(request.path == "/config") json(config(server, providers)) else json(body(request))
    }
    private fun config(server: MockWebServer, providers: Int, subpage: Boolean = false) = JsonObject().apply {
        add("_manifest", JsonObject().apply { addProperty("name", "Synthetic declarative sources") })
        repeat(providers) { index -> add("provider$index", JsonObject().apply {
            addProperty("name", "Provider$index");addProperty("base_url", server.url("/backend$index").toString())
            add("anime", parseExpressObject("""{"query":"/search?q={query}","keywords":"{title} {absoluteNumber}"}"""))
            add("json_format", parseExpressObject("""{"results":"streams","title":"title","url":"url","hash":"infoHash"}"""))
            if (subpage) addProperty("source_is_in_sub_page", true)
        }) }
    }.toString()
    private fun rows(titles: List<String>) = JsonObject().apply { add("streams", JsonArray().apply {
        titles.forEachIndexed { index, title -> add(JsonObject().apply {
            addProperty("title", title);addProperty("infoHash", "%040x".format(index + 1))
        }) }
    }) }.toString()
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
    private companion object { const val HASH = "aabbccddeeff00112233445566778899aabbccdd" }
}
