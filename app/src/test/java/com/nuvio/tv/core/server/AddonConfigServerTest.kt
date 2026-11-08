package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonConfigServerTest {

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
        val state = PageState(
            addons = listOf(AddonInfo("https://example.test/fixture", "Test add-on", null)),
            catalogs = emptyList()
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
        val write = request(NanoHTTPD.Method.POST, "/api/addons", authorization)
        withProposedUrl(write)

        server.serve(read).use { response ->
            assertEquals(NanoHTTPD.Response.Status.OK, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
            assertTrue(response.data.bufferedReader().readText().contains("https://example.test/fixture"))
        }
        server.serve(write).use { response ->
            assertEquals(NanoHTTPD.Response.Status.OK, response.status)
            assertTrue(response.data.bufferedReader().readText().contains("pending_confirmation"))
        }
        assertEquals(listOf("https://example.test"), changes.single().proposedUrls)
        newServer().serve(read).use { assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, it.status) }
    }

    private fun withProposedUrl(request: NanoHTTPD.IHTTPSession) {
        val bytes = """{"urls":["https://example.test"]}""".toByteArray()
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
