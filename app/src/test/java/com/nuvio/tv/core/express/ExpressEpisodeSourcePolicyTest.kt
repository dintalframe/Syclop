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

class ExpressEpisodeSourcePolicyTest {
    @Test fun `episode search never requests season scope and packs cannot consume row budget`() = runBlocking {
        MockWebServer().use { server ->
            val titles = List(30) { "The.Big.Bang.Theory.S01-S12.1080p.GROUP$it" } +
                listOf("The.Big.Bang.Theory.S01E04.1080p.mkv", "Unknown release")
            server.dispatcher = dispatcher(server, rows(titles))
            val adapter = adapter(media())
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, ExpressMediaRequest("series", "tt1:1:4", 1, 4)) } as NetworkResult.Success
            assertEquals(titles.takeLast(2), result.data.map { it.title })
            assertEquals(2, adapter.lastReport(addon)?.sourceCount)
            val paths = (1..server.requestCount).map { server.takeRequest().path }
            assertEquals(2, paths.size)
            assertTrue(paths.last()!!.startsWith("/backend/episode?"))
            assertTrue(paths.none { it!!.contains("/season?") })
        }
    }

    @Test fun `season pack only response is empty and no pack subpage is fetched`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(server, """{"streams":[{"title":"Show.Season1Complete","url":"/must-not-fetch"}]}""", subpage = true)
            val adapter = adapter(media())
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, ExpressMediaRequest("series", "tt1:1:4", 1, 4)) } as NetworkResult.Success
            assertTrue(result.data.isEmpty())
            assertEquals(0, adapter.lastReport(addon)?.sourceCount)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `native MAL anime keeps absolute query scope and filters batch before row budget`() = runBlocking {
        MockWebServer().use { server ->
            val titles = List(30) { "[Group$it] Sousou no Frieren [01-12] [Batch]" } +
                "[Group] Sousou no Frieren - 29 (S02E01).mkv"
            server.dispatcher = dispatcher(server, rows(titles))
            val selected = ExpressMedia("series", "Sousou no Frieren 2nd Season", null, 2026,
                null, 1, 29, true, nativeAnimeIdentity = NativeAnimeTitleIdentity.create(59978,
                    "Sousou no Frieren 2nd Season", listOf("Sousou no Frieren"))!!)
            val adapter = adapter(selected)
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, ExpressMediaRequest("series", "mal:59978:1", 1, 1)) } as NetworkResult.Success
            assertEquals(listOf(titles.last()), result.data.map { it.title })
            assertEquals(1, selected.episode)
            assertEquals(29, selected.absoluteNumber)
            val paths = (1..server.requestCount).map { server.takeRequest().path }
            assertTrue(paths.drop(1).all { it!!.startsWith("/backend/anime?") && it.contains("29") })
            assertTrue(paths.none { it!!.contains("s01e01", true) || it.contains("/season?") })
        }
    }

    @Test fun `anime movie collection is unchanged by episode only policy`() = runBlocking {
        MockWebServer().use { server ->
            val title = "Anime.Complete.Series.Collection"
            server.dispatcher = dispatcher(server, rows(listOf(title)))
            val adapter = adapter(media().copy(type = "movie", episode = null, season = null, anime = true))
            val addon = (adapter.discover(server.url("/config").toString()) as NetworkResult.Success).data
            val result = withTimeout(10_000) { adapter.search(addon, ExpressMediaRequest("movie", "tt1")) } as NetworkResult.Success
            assertEquals(listOf(title), result.data.map { it.title })
            assertTrue((1..server.requestCount).map { server.takeRequest().path }.last()!!.startsWith("/backend/movie?"))
        }
    }

    private fun media() = ExpressMedia("series", "The Big Bang Theory", "tt1", 2007, 1, 4)
    private fun adapter(selected: ExpressMedia) = ExpressAddonAdapter(ExpressHttpClient(OkHttpClient(), true),
        ExpressMetadataSource { selected }, { 0L })
    private fun dispatcher(server: MockWebServer, body: String, subpage: Boolean = false) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/config" -> json(config(server, subpage))
            request.path!!.startsWith("/backend/") -> json(body)
            else -> throw AssertionError("Pack detail page or unexpected provider scope was requested")
        }
    }
    private fun config(server: MockWebServer, subpage: Boolean) = JsonObject().apply {
        add("_manifest", parseExpressObject("""{"name":"Synthetic episode sources"}"""))
        add("provider", JsonObject().apply {
            addProperty("name", "Synthetic"); addProperty("base_url", server.url("/backend").toString())
            add("episode", parseExpressObject("""{"query":"/episode?q={query}","keywords":"{title} {episodeCode}"}"""))
            add("season", parseExpressObject("""{"query":"/season?q={query}","keywords":"{title} {seasonCode}"}"""))
            add("anime", parseExpressObject("""{"query":"/anime?q={query}","keywords":"{title} {absoluteNumber}"}"""))
            add("movie", parseExpressObject("""{"query":"/movie?q={query}","keywords":"{title}"}"""))
            add("json_format", parseExpressObject("""{"results":"streams","title":"title","hash":"infoHash","url":"url"}"""))
            if (subpage) addProperty("source_is_in_sub_page", true)
        })
    }.toString()
    private fun rows(titles: List<String>) = JsonObject().apply { add("streams", JsonArray().apply {
        titles.forEachIndexed { index, title -> add(JsonObject().apply {
            addProperty("title", title); addProperty("infoHash", "%040x".format(index + 1))
        }) }
    }) }.toString()
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
