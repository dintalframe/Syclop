package com.nuvio.tv.core.express

import com.nuvio.tv.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ExpressLastValidConfigurationTest {
    private class Memory:ExpressConfigurationStore {
        val values=mutableMapOf<String,String>()
        override fun read(url:String)=values[url]
        override fun write(url:String,json:String){values[url]=json}
    }
    private fun adapter(store:Memory)=ExpressAddonAdapter(ExpressHttpClient(OkHttpClient(),true),ExpressMetadataSource { error("unused") },{1000},store)
    private fun config(base:String,version:Int)="""{"_manifest":{"name":"Fixture","version":$version},"source":{"base_url":"$base","movie":{"query":"q/{query}","keywords":"{imdbId}"},"json_format":{"results":"streams","title":"title","hash":"infoHash"}}}"""
    private fun json(value:String)=MockResponse().setHeader("Content-Type","application/json").setBody(value)

    @Test fun `failed forced update preserves last valid configuration across process recreation`()=runTest {
        MockWebServer().use { server ->
            val store=Memory();val adapter=adapter(store);val url=server.url("/package").toString()
            server.enqueue(json(config(server.url("/search").toString(),1)))
            val original=(adapter.discover(url) as NetworkResult.Success).data
            val oldSnapshot=store.values.toMap()
            server.enqueue(json("""{"type":"kosmos","url":"/remote.js"}"""))
            assertTrue(adapter.discover(url,forceRefresh=true) is NetworkResult.Error)
            assertEquals(oldSnapshot,store.values)
            assertEquals(original,(adapter(store).discover(url) as NetworkResult.Success).data)
            assertEquals(2,server.requestCount)
            repeat(2) { val request=server.takeRequest();assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie")) }
        }
    }
    @Test fun `forced valid update changes data but keeps stable provider identity`()=runTest {
        MockWebServer().use { server ->
            val store=Memory();val adapter=adapter(store);val url=server.url("/package").toString()
            server.enqueue(json(config(server.url("/search").toString(),1)))
            val before=(adapter.discover(url) as NetworkResult.Success).data
            server.enqueue(json(config(server.url("/search").toString(),2)))
            val after=(adapter.discover(url,true) as NetworkResult.Success).data
            assertEquals(before.id,after.id);assertEquals(before.baseUrl,after.baseUrl);assertEquals("2",after.version)
        }
    }
    @Test fun `unsupported-only replacement cannot discard last usable provider snapshot`()=runTest {
        MockWebServer().use { server ->
            val store=Memory();val adapter=adapter(store);val url=server.url("/package").toString()
            server.enqueue(json(config(server.url("/search").toString(),1)))
            adapter.discover(url);val before=store.values.toMap()
            server.enqueue(json("""{"source":{"base_url":"${server.url("/search")}","html_parser":{"row":"eval('unsupported')"}}}"""))
            assertTrue(adapter.discover(url,true) is NetworkResult.Error)
            assertEquals(before,store.values)
        }
    }
    @Test fun `corrupt unusable saved configuration can recover from a valid network package`()=runTest {
        MockWebServer().use { server ->
            val store=Memory();val url=server.url("/package").toString()
            store.values[url]="""{"packages":[{"name":"Old","version":"1","providers":{"unsupported":{"base_url":"${server.url("/search")}","html_parser":{"row":"eval('unsupported')"}}}}]}"""
            server.enqueue(json(config(server.url("/search").toString(),2)))
            val result=(adapter(store).discover(url) as NetworkResult.Success).data
            assertEquals("2",result.version);assertEquals(1,server.requestCount)
        }
    }
    @Test fun `malformed media query cannot replace the persisted usable configuration`()=runTest {
        MockWebServer().use { server ->
            val store=Memory();val adapter=adapter(store);val url=server.url("/package").toString()
            server.enqueue(json(config(server.url("/search").toString(),1)))
            val original=(adapter.discover(url) as NetworkResult.Success).data
            val saved=store.values.toMap()
            for (scope in listOf("""{"query":{},"keywords":"{imdbId}"}""", """{"query":"q/{query}","keywords":[{}]}""", """[]""")) {
                server.enqueue(json("""{"source":{"base_url":"${server.url("/search")}","movie":$scope,"json_format":{"results":"streams","hash":"infoHash"}}}"""))
                assertTrue(adapter.discover(url,true) is NetworkResult.Error)
                assertEquals(saved,store.values)
            }
            assertEquals(original,(adapter(store).discover(url) as NetworkResult.Success).data)
        }
    }
    @Test fun `discovery appends manifest path without corrupting query configuration`() {
        assertEquals("https://p.example/Case/manifest.json?config=KeepCase%2F",
            expressDiscoveryUrls("https://p.example/Case?config=KeepCase%2F")[1])
    }
    @Test(expected=ExpressException::class) fun `configuration parser refuses excessive JSON nesting before parsing`() {
        validateExpressJsonBounds("[".repeat(49)+"0"+"]".repeat(49))
    }
    @Test fun `braces in quoted strings do not count as nesting`() { validateExpressJsonBounds("{\"text\":\""+"{".repeat(100)+"\"}") }
}
