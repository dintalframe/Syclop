package com.nuvio.tv.core.providers

import com.google.gson.JsonParser
import com.nuvio.tv.core.express.ExpressException
import com.nuvio.tv.core.express.ExpressHttpClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderInstallOptionsTest {
    private val options=ProviderInstallOptions(ExpressHttpClient(OkHttpClient()))
    @Test fun `vendor presents every explicit variant and indicates publisher default without selecting`() {
        val root=JsonParser.parseString("""{"packages":[{"name":"Lite","manifest":"/lite"},{"name":"Full","manifest":"/full"}],"defaults":{"packages":["/lite"]}}""").asJsonObject
        val result=options.parseVendor(root,"https://provider.example/vendor")
        assertEquals(listOf("Lite","Full"),result.map { it.name })
        assertEquals(listOf(true,false),result.map { it.isDefault })
        assertEquals("https://provider.example/full",result[1].manifestUrl)
    }
    @Test fun `Stremio scheme normalizes without altering case-sensitive configuration path or query`() {
        assertEquals("https://p.example/PrivatePath/manifest.json?config=CaseSensitive",ProviderInstallUrls.normalize("stremio://p.example/PrivatePath/manifest.json?config=CaseSensitive"))
    }
    @Test fun `embedded credential nonHTTPS and file URLs are refused`() {
        listOf("https://key@p.example/manifest","http://p.example","file:///x","https://p.example:1234","https://p.example/#token").forEach { assertNull(ProviderInstallUrls.normalize(it)) }
    }
    @Test(expected=ExpressException::class) fun `vendor reference cannot escape to downloaded local code URL`() {
        options.parseVendor(JsonParser.parseString("""{"packages":[{"manifest":"file:///x.js"}]}""").asJsonObject,"https://p.example/vendor")
    }
    @Test(expected=ExpressException::class) fun `vendor list size is bounded`() {
        options.parseVendor(JsonParser.parseString("{\"packages\":["+List(31){"{\"manifest\":\"/m$it\"}"}.joinToString()+"]}").asJsonObject,"https://p.example/vendor")
    }
    @Test fun `actual Magnify vendor descriptions identify Full Lite Fast without header guesses or selection`()=runBlocking {
        val requested=java.util.concurrent.CopyOnWriteArrayList<String>()
        val vendor="""{"name":"Magnify Vendor","packages":[{"manifest":"https://magnify.pages.dev/manifest/full","description":"Full provider package with safe metadata"},{"manifest":"https://magnify.pages.dev/manifest/lite","description":"Lite provider package with safe metadata"},{"manifest":"https://magnify.pages.dev/manifest/fast","description":"Fast provider package with safe metadata"}],"defaults":{"packages":["https://magnify.pages.dev/manifest/lite"]}}"""
        val result=fixture(requested) { vendor }.inspect("https://magnify.pages.dev/vendor")
        assertEquals(listOf("Full provider package with safe metadata","Lite provider package with safe metadata","Fast provider package with safe metadata"),result.map { it.name })
        assertEquals(listOf(false,true,false),result.map { it.isDefault })
        assertEquals(listOf("https://magnify.pages.dev/manifest/full","https://magnify.pages.dev/manifest/lite","https://magnify.pages.dev/manifest/fast"),result.map { it.manifestUrl })
        assertEquals(listOf("https://magnify.pages.dev/vendor"),requested)
    }
    @Test fun `URL-only entries use publisher manifest names and never follow executable or package references`()=runBlocking {
        val requested=java.util.concurrent.CopyOnWriteArrayList<String>()
        val result=fixture(requested) { url -> when {
            url.endsWith("/vendor") -> """{"packages":[{"manifest":"/one"},{"manifest":"/two"}]}"""
            url.endsWith("/one") -> """{"name":"Publisher Collection","type":"express","url":"/never-fetch-config"}"""
            url.endsWith("/two") -> """{"_manifest":{"name":"Publisher Alternative"},"url":"/never-fetch-code.js"}"""
            else -> error("Unexpected fixture request")
        } }.inspect("https://p.example/vendor")
        assertEquals(listOf("Publisher Collection","Publisher Alternative"),result.map { it.name })
        assertEquals(listOf("https://p.example/one","https://p.example/two"),result.map { it.manifestUrl })
        assertEquals(3,requested.size);assertTrue(requested.none { it.contains("never-fetch") })
        assertTrue(result.none { it.isDefault })
    }
    @Test fun `publisher entry name takes priority over description without redundant metadata requests`()=runBlocking {
        val requested=java.util.concurrent.CopyOnWriteArrayList<String>()
        val result=fixture(requested) { """{"packages":[{"name":"Publisher Choice","description":"Another label","manifest":"/one"}]}""" }
            .inspect("https://p.example/vendor")
        assertEquals("Publisher Choice",result.single().name);assertEquals(1,requested.size)
    }
    @Test fun `failed or malformed manifest name leaves a clear fallback without removing other choices`()=runBlocking {
        val requested=java.util.concurrent.CopyOnWriteArrayList<String>()
        val result=fixture(requested) { url -> when {
            url.endsWith("/vendor") -> """{"packages":[{"manifest":"/one"},{"manifest":"/two"}],"defaults":{"packages":["/two"]}}"""
            url.endsWith("/one") -> "not-json"
            else -> """{"name":"Available Package"}"""
        } }.inspect("https://p.example/vendor")
        assertEquals(listOf("Package 1 (name unavailable)","Available Package"),result.map { it.name })
        assertEquals(listOf(false,true),result.map { it.isDefault });assertEquals(2,result.size)
    }
    private fun fixture(requested:MutableList<String>, response:(String)->String):ProviderInstallOptions {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();val url=request.url.toString();requested.add(url)
            assertNull(request.header("Authorization"));assertNull(request.header("Cookie"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response(url).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ProviderInstallOptions(ExpressHttpClient(client))
    }

}
