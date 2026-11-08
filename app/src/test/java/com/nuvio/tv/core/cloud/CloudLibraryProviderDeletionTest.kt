package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.AllDebridService
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CloudLibraryProviderDeletionTest {
    @Test
    fun `Real Debrid verifies the only raw file before deleting the exact item`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(realDebridInfo))
            server.enqueue(MockResponse().setResponseCode(204))
            val adapter = realDebrid(server)
            val item = item(DebridProviders.REAL_DEBRID_ID, "job", file = file("2"))
            assertTrue(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) {
                assertEquals(1, server.requestCount)
                true
            })
            val lookup = server.takeRequest()
            assertEquals("/torrents/info/job", lookup.path)
            assertEquals("Bearer $TOKEN", lookup.getHeader("Authorization"))
            val deletion = server.takeRequest()
            assertEquals("DELETE", deletion.method)
            assertEquals("/torrents/delete/job", deletion.path)
            assertEquals("Bearer $TOKEN", deletion.getHeader("Authorization"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `Real Debrid skips an unselected sibling and an expired authorization`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(realDebridInfo.replace(
                "\"selected\":1}]", "\"selected\":1},{\"id\":3,\"path\":\"/notes.txt\",\"selected\":0}]"
            )))
            server.enqueue(json(realDebridInfo))
            val adapter = realDebrid(server)
            val item = item(DebridProviders.REAL_DEBRID_ID, "job", file = file("2"))
            assertFalse(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { error("must reject the sibling") })
            assertFalse(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { false })
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `strict deletion does not retry when the service returns Retry After zero`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(realDebridInfo))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(204))
            val item = item(DebridProviders.REAL_DEBRID_ID, "job", file = file("2"))
            assertFalse(realDebrid(server).deleteCompletedFile(TOKEN, item, item.files.single()) { true })
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `AllDebrid checks the raw magnet tree and skips an unlinked sibling`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":50,"files":[{"n":"Show","e":[{"n":"clip.mkv","s":200,"l":"https://owned.invalid/clip"},{"n":"notes.txt"}]}]}]}}"""))
            server.enqueue(json("""{"status":"success","data":{"magnets":[{"id":50,"files":[{"n":"Show","e":[{"n":"clip.mkv","s":200,"l":"https://owned.invalid/clip"}]}]}]}}"""))
            server.enqueue(json("""{"status":"success","data":{"message":"deleted"}}"""))
            val adapter = allDebrid(server)
            val item = item(DebridProviders.ALL_DEBRID_ID, "50", file = file("Show/clip.mkv"))
            assertFalse(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { error("must reject the sibling") })
            assertTrue(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { true })
            repeat(2) { assertEquals("/v4/magnet/files", server.takeRequest().path) }
            val deletion = server.takeRequest()
            assertEquals("/v4/magnet/delete", deletion.path)
            assertEquals("id=50", deletion.body.readUtf8())
            assertEquals("Bearer $TOKEN", deletion.getHeader("Authorization"))
        }
    }

    @Test
    fun `TorBox verifies fresh ID scoped detail and disables the bulk control flag`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"success":true,"data":{"id":50,"name":"Show","files":[{"id":0,"short_name":"clip.mkv","size":200}]}}"""))
            server.enqueue(json("""{"success":true,"data":null}"""))
            val adapter = torbox(server)
            val item = item(DebridProviders.TORBOX_ID, "50", CloudLibraryItemType.WebDownload,
                file = file("0").copy(name = "clip.mkv"))
            assertTrue(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { true })
            assertEquals("/v1/api/webdl/mylist?id=50&bypass_cache=true", server.takeRequest().path)
            val deletion = server.takeRequest()
            assertEquals("/v1/api/webdl/controlwebdownload", deletion.path)
            assertEquals("Bearer $TOKEN", deletion.getHeader("Authorization"))
            assertEquals("""{"webdl_id":50,"operation":"delete","all":false}""", deletion.body.readUtf8())
        }
    }

    @Test
    fun `Premiumize deletes only the exact freshly verified root file`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json(premiumizeInfo))
            server.enqueue(json(premiumizeRoot))
            server.enqueue(json("""{"status":"success"}"""))
            val adapter = premiumize(server)
            val item = premiumizeItem("file:owned-file")
            assertTrue(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) {
                assertEquals(2, server.requestCount)
                true
            })
            val info = server.takeRequest()
            assertEquals("/api/item/details?id=owned-file", info.path)
            assertEquals("Bearer $TOKEN", info.getHeader("Authorization"))
            assertEquals("/api/folder/list", server.takeRequest().path)
            val deletion = server.takeRequest()
            assertEquals("POST", deletion.method)
            assertEquals("/api/item/delete", deletion.path)
            assertEquals("id=owned-file", deletion.body.readUtf8())
            assertEquals("Bearer $TOKEN", deletion.getHeader("Authorization"))
        }
    }

    @Test
    fun `Premiumize skips grouped entries and a file moved into a folder`() = runTest {
        MockWebServer().use { server ->
            val adapter = premiumize(server)
            val group = premiumizeItem("folder:Owned Pack")
            assertFalse(adapter.deleteCompletedFile(TOKEN, group, group.files.single()) { error("grouped") })
            assertEquals(0, server.requestCount)

            val item = premiumizeItem("file:owned-file")
            server.enqueue(json(premiumizeInfo))
            server.enqueue(json("""{"status":"success","content":[{"id":"pack","name":"Owned Pack","type":"folder"}]}"""))
            assertFalse(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { error("moved into folder") })
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `Premiumize skips changed metadata and revoked authorization`() = runTest {
        MockWebServer().use { server ->
            val adapter = premiumize(server)
            val item = premiumizeItem("file:owned-file")
            server.enqueue(json(premiumizeInfo.replace("200", "201")))
            assertFalse(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { error("different bytes") })
            assertEquals(1, server.requestCount)
            server.enqueue(json(premiumizeInfo))
            server.enqueue(json(premiumizeRoot))
            assertFalse(adapter.deleteCompletedFile(TOKEN, item, item.files.single()) { false })
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `pre-create inventory requires exact provider total or explicit full synchronization`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""[{"id":"existing"}]""").setHeader("X-Total-Count", "1"))
            server.enqueue(json("""[{"id":"existing"}]""").setHeader("X-Total-Count", "2"))
            server.enqueue(json("""{"status":"success","data":{"fullsync":true,"magnets":[{"id":50}]}}"""))
            server.enqueue(json("""{"status":"success","data":{"fullsync":true}}"""))
            val rd = realDebrid(server)
            assertEquals(setOf("existing"), rd.torrentIdsBeforeCreate(TOKEN))
            assertNull(rd.torrentIdsBeforeCreate(TOKEN))
            val ad = allDebrid(server)
            assertEquals(setOf("50"), ad.torrentIdsBeforeCreate(TOKEN))
            assertNull(ad.torrentIdsBeforeCreate(TOKEN))
            assertEquals(4, server.requestCount)
        }
    }

    private fun retrofit(server: MockWebServer): Retrofit = Retrofit.Builder().baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer substituted-test-token").build())
        }.build())
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
        .build()

    private fun strict(retrofit: Retrofit) = CloudLibraryDeletionTransport(OkHttpClient(), retrofit, retrofit, retrofit, retrofit)

    private fun realDebrid(server: MockWebServer): RealDebridCloudLibraryProviderApi {
        val retrofit = retrofit(server)
        return RealDebridCloudLibraryProviderApi(retrofit.create(RealDebridApi::class.java), strict(retrofit))
    }

    private fun allDebrid(server: MockWebServer): AllDebridCloudLibraryProviderApi {
        val retrofit = retrofit(server)
        return AllDebridCloudLibraryProviderApi(AllDebridService(retrofit.create(AllDebridApi::class.java)), strict(retrofit))
    }

    private fun torbox(server: MockWebServer): TorboxCloudLibraryProviderApi {
        val retrofit = retrofit(server)
        return TorboxCloudLibraryProviderApi(retrofit.create(TorboxApi::class.java), strict(retrofit))
    }

    private fun premiumize(server: MockWebServer): PremiumizeCloudLibraryProviderApi {
        val retrofit = retrofit(server)
        return PremiumizeCloudLibraryProviderApi(retrofit.create(PremiumizeApi::class.java), strict(retrofit))
    }

    private fun premiumizeItem(id: String) = item(DebridProviders.PREMIUMIZE_ID, id, CloudLibraryItemType.File,
        file = file("owned-file").copy(name = "clip.mkv"))

    private fun item(provider: String, id: String, type: CloudLibraryItemType = CloudLibraryItemType.Torrent,
        file: CloudLibraryFile) = CloudLibraryItem(provider, provider, id, type, "Show", files = listOf(file))

    private fun file(id: String) = CloudLibraryFile(id, "Show/clip.mkv", sizeBytes = 200)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TOKEN = "owned-test-token"
        const val realDebridInfo = """{"id":"job","status":"downloaded","files":[{"id":2,"path":"/Show/clip.mkv","bytes":200,"selected":1}]}"""
        const val premiumizeInfo = """{"status":"success","id":"owned-file","name":"clip.mkv","size":200}"""
        const val premiumizeRoot = """{"status":"success","content":[{"id":"owned-file","name":"clip.mkv","type":"file","size":200}]}"""
    }
}
