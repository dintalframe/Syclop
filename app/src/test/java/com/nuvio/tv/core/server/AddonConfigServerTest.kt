package com.nuvio.tv.core.server

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonConfigServerTest {

    @Test
    fun `paired webpage submits installed ids and directs add-on installation to the TV`() {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getSharedPreferences("app_locale", 0).getString("locale_tag", null) } returns null
        val html = AddonWebPage.getHtml(context)
        assertTrue(html.contains("addonIds: addonIds"))
        assertFalse(html.contains("id=\"addonUrl\""))
        assertFalse(html.contains("function addAddon("))
    }

    @Test
    fun `a retired viewer session cannot read state or submit a change`() {
        var current = true
        var stateReads = 0
        val server = AddonConfigServer(
            context = mockk(relaxed = true),
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = {
                stateReads++
                PageState(addons = emptyList(), catalogs = emptyList())
            },
            onChangeProposed = { error("Retired session proposed a change") },
            isCurrent = { current }
        )
        val authorization = "Bearer " + server.pairingUrl("192.0.2.1").substringAfter('#')
        val read = request(NanoHTTPD.Method.GET, "/api/state", authorization)
        server.serve(read).use { assertEquals(NanoHTTPD.Response.Status.OK, it.status) }
        current = false

        server.serve(read).use { assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, it.status) }
        val write = request(NanoHTTPD.Method.POST, "/api/addons", authorization)
        withBody(write, """{"addonIds":[]}""")
        server.serve(write).use { assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, it.status) }
        assertEquals(1, stateReads)
    }

    @Test
    fun `unpaired LAN requests cannot read settings or submit changes`() {
        var stateReads = 0
        var proposedChanges = 0
        val server = AddonConfigServer(
            context = mockk(relaxed = true),
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = {
                stateReads++
                PageState(addons = emptyList(), catalogs = emptyList())
            },
            onChangeProposed = { proposedChanges++ }
        )

        val write = request(NanoHTTPD.Method.POST, "/api/addons", "Bearer invalid-session")
        withProposedUrl(write)
        listOf("/api/state", "/api/addons", "/api/collections", "/api/status/test").forEach { path ->
            server.serve(request(NanoHTTPD.Method.GET, path)).use { response ->
                assertEquals(path, NanoHTTPD.Response.Status.FORBIDDEN, response.status)
            }
        }
        server.serve(write).use { assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, it.status) }
        assertEquals(0, stateReads)
        assertEquals(0, proposedChanges)
        verify(exactly = 0) { write.parseBody(any()); write.inputStream }
    }

    @Test
    fun `pairing allows access and confirmation only in the current session`() {
        val privateUrl = "https://example.test/credential-value?key=private-query"
        val state = PageState(
            addons = listOf(
                AddonInfo(privateUrl, "credential-value", "Description contains $privateUrl"),
                AddonInfo("https://second.example/manifest", "Second add-on", null)
            ),
            catalogs = listOf(CatalogInfo("featured", "featured", "Featured", privateUrl, "movie", false))
        )
        val changes = mutableListOf<PendingAddonChange>()
        fun newServer() = AddonConfigServer(
            context = mockk(relaxed = true),
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = { state },
            onChangeProposed = { changes.add(it) }
        )
        val server = newServer()
        val token = server.pairingUrl("192.0.2.1").substringAfter('#', "")
        val authorization = "Bearer $token"
        val read = request(NanoHTTPD.Method.GET, "/api/state", authorization)
        val addons = server.serve(read).use { response ->
            assertEquals(NanoHTTPD.Response.Status.OK, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
            val body = response.data.bufferedReader().readText()
            assertFalse(body.contains("credential-value"))
            assertFalse(body.contains("private-query"))
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("addons")
        }
        server.serve(request(NanoHTTPD.Method.GET, "/api/addons", authorization)).use { response ->
            assertEquals(addons, JsonParser.parseReader(response.data.reader()))
        }
        val ids = addons.map { it.asJsonObject.get("id").asString }
        assertEquals(2, ids.distinct().size)
        assertEquals("Second add-on", addons[1].asJsonObject.get("name").asString)
        val write = request(NanoHTTPD.Method.POST, "/api/addons", authorization)
        withBody(write, """{"addonIds":["${ids[1]}","${ids[0]}"]}""")
        server.serve(write).use { response ->
            assertEquals(NanoHTTPD.Response.Status.OK, response.status)
            assertTrue(response.data.bufferedReader().readText().contains("pending_confirmation"))
        }
        assertEquals(listOf("https://second.example/manifest", privateUrl), changes.single().proposedUrls)
        for (body in listOf("""{"addonIds":["stale-id"]}""", """{"urls":["https://other.example"]}""", "{}")) {
            val invalid = request(NanoHTTPD.Method.POST, "/api/addons", authorization)
            withBody(invalid, body)
            server.serve(invalid).use { assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, it.status) }
        }
        assertEquals(1, changes.size)
        newServer().serve(read).use { assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, it.status) }
    }

    private fun withProposedUrl(request: NanoHTTPD.IHTTPSession) {
        withBody(request, """{"urls":["https://example.test"]}""")
    }

    private fun withBody(request: NanoHTTPD.IHTTPSession, body: String) {
        val bytes = body.toByteArray()
        val headers = request.headers + ("content-length" to bytes.size.toString())
        every { request.headers } returns headers
        every { request.inputStream } answers { java.io.ByteArrayInputStream(bytes) }
    }

    private fun request(method: NanoHTTPD.Method, uri: String, authorization: String? = null) =
        mockk<NanoHTTPD.IHTTPSession>(relaxed = true) {
            every { this@mockk.method } returns method
            every { this@mockk.uri } returns uri
            every { headers } returns authorization?.let { mapOf("authorization" to it) }.orEmpty()
        }

    @Test
    fun `collections only mode preserves existing addon and catalog changes`() {
        val currentState = PageState(
            addons = listOf(
                AddonInfo(
                    url = "https://primary.example",
                    name = "Primary",
                    description = null
                )
            ),
            catalogs = listOf(
                CatalogInfo(
                    key = "catalog_a",
                    disableKey = "catalog_a",
                    catalogName = "Featured",
                    addonName = "Primary",
                    type = "movie",
                    isDisabled = false
                ),
                CatalogInfo(
                    key = "collection_saved",
                    disableKey = "collection_saved",
                    catalogName = "Saved",
                    addonName = "1 folder",
                    type = "collection",
                    isDisabled = true
                )
            )
        )
        val proposedChange = PendingAddonChange(
            proposedUrls = listOf("https://other.example"),
            proposedCatalogOrderKeys = listOf("catalog_b"),
            proposedDisabledCatalogKeys = listOf("catalog_b"),
            proposedCollectionsJson = "[{\"id\":\"new\"}]",
            proposedDisabledCollectionKeys = listOf("collection_new")
        )

        val sanitized = sanitizePendingAddonChange(
            mode = AddonWebConfigMode.COLLECTIONS_ONLY,
            proposedChange = proposedChange,
            currentState = currentState
        )

        assertEquals(listOf("https://primary.example"), sanitized.proposedUrls)
        assertEquals(listOf("catalog_a", "collection_saved"), sanitized.proposedCatalogOrderKeys)
        assertEquals(listOf("collection_saved"), sanitized.proposedDisabledCatalogKeys)
        assertEquals("[{\"id\":\"new\"}]", sanitized.proposedCollectionsJson)
        assertEquals(listOf("collection_new"), sanitized.proposedDisabledCollectionKeys)
    }

    @Test
    fun `full mode keeps proposed addon and catalog changes`() {
        val proposedChange = PendingAddonChange(
            proposedUrls = listOf("https://other.example"),
            proposedCatalogOrderKeys = listOf("catalog_b"),
            proposedDisabledCatalogKeys = listOf("catalog_b"),
            proposedCollectionsJson = "[]",
            proposedDisabledCollectionKeys = listOf("collection_new")
        )

        val sanitized = sanitizePendingAddonChange(
            mode = AddonWebConfigMode.FULL,
            proposedChange = proposedChange,
            currentState = PageState(
                addons = emptyList(),
                catalogs = emptyList()
            )
        )

        assertEquals(proposedChange, sanitized)
    }

    @Test
    fun `addons only mode preserves catalog and collection state`() {
        val currentState = PageState(
            addons = listOf(
                AddonInfo(
                    url = "https://primary.example",
                    name = "Primary",
                    description = null
                )
            ),
            catalogs = listOf(
                CatalogInfo(
                    key = "catalog_a",
                    disableKey = "catalog_a",
                    catalogName = "Featured",
                    addonName = "Primary",
                    type = "movie",
                    isDisabled = true
                )
            ),
            disabledCollectionKeys = listOf("collection_saved")
        )
        val proposedChange = PendingAddonChange(
            proposedUrls = listOf("https://primary.example", "https://new.example"),
            proposedCatalogOrderKeys = listOf("catalog_b"),
            proposedDisabledCatalogKeys = emptyList(),
            proposedCollectionsJson = "[{\"id\":\"new\"}]",
            proposedDisabledCollectionKeys = listOf("collection_new")
        )

        val sanitized = sanitizePendingAddonChange(
            mode = AddonWebConfigMode.ADDONS_ONLY,
            proposedChange = proposedChange,
            currentState = currentState
        )

        assertEquals(listOf("https://primary.example", "https://new.example"), sanitized.proposedUrls)
        assertEquals(listOf("catalog_a"), sanitized.proposedCatalogOrderKeys)
        assertEquals(listOf("catalog_a"), sanitized.proposedDisabledCatalogKeys)
        assertEquals(null, sanitized.proposedCollectionsJson)
        assertEquals(listOf("collection_saved"), sanitized.proposedDisabledCollectionKeys)
    }
}
