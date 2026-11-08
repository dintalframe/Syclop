package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nuvio.tv.core.streams.StreamBadgeSettings
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class LocalConfigServerSecurityTest {
    @Test
    fun `unpaired callers cannot read or change any phone editor`() {
        var reads = 0
        var writes = 0
        for (editor in editors({ reads++ }, { writes++ })) {
            for (path in editor.paths) {
                for (method in listOf(NanoHTTPD.Method.GET, NanoHTTPD.Method.POST)) {
                    val request = request(method, path)
                    editor.server.serve(request).use { response ->
                        assertEquals(path, NanoHTTPD.Response.Status.FORBIDDEN, response.status)
                    }
                    verify(exactly = 0) { request.inputStream; request.parseBody(any()) }
                }
            }
        }
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `pairing permits current editor reads and expires on reopen`() {
        for (editor in editors()) {
            val authorization = authorization(editor)
            val request = request(NanoHTTPD.Method.GET, editor.paths.first(), authorization)
            editor.server.serve(request).use { response ->
                assertEquals(NanoHTTPD.Response.Status.OK, response.status)
                assertEquals("no-store", response.getHeader("Cache-Control"))
                assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
            }
            val reopened = editors().single { it.server.javaClass == editor.server.javaClass }
            reopened.server.serve(request).use { response ->
                assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
            }
        }
    }

    @Test
    fun `oversized authenticated posts stop before body allocation or callbacks`() {
        var reads = 0
        var writes = 0
        for (editor in editors({ reads++ }, { writes++ })) {
            val request = request(NanoHTTPD.Method.POST, editor.paths.first(), authorization(editor),
                contentLength = Long.MAX_VALUE.toString())
            editor.server.serve(request).use { response ->
                assertEquals(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, response.status)
            }
            verify(exactly = 0) { request.inputStream; request.parseBody(any()) }
        }
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `negative malformed and truncated request lengths cannot save settings`() {
        var writes = 0
        for (length in listOf("-1", "not-a-number", "10")) {
            for (editor in editors(onWrite = { writes++ })) {
                val request = request(NanoHTTPD.Method.POST, editor.paths.first(), authorization(editor),
                    body = "{}", contentLength = length)
                editor.server.serve(request).use { response ->
                    assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
                }
            }
        }
        assertEquals(0, writes)
    }

    @Test
    fun `deeply nested json is rejected before settings change`() {
        var writes = 0
        val body = "{\"nested\":".repeat(50) + "0" + "}".repeat(50)
        for (editor in editors(onWrite = { writes++ })) {
            val request = request(NanoHTTPD.Method.POST, editor.paths.first(), authorization(editor), body)
            editor.server.serve(request).use { response ->
                assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
            }
        }
        assertEquals(0, writes)
    }

    @Test
    fun `poster patterns round trip json escaping and unicode`() {
        val expected = "https://poster.example/{id}?label=é&text=\"quoted\"&separator=\\n"
        var saved = ""
        val server = CustomPosterConfigServer({ saved }, { saved = it })
        val authorization = "Bearer " + server.pairingUrl("192.0.2.1").substringAfter('#')
        val body = Gson().toJson(mapOf("pattern" to expected))
        server.serve(request(NanoHTTPD.Method.POST, "/api/pattern", authorization, body)).use {
            assertEquals(NanoHTTPD.Response.Status.OK, it.status)
        }
        assertEquals(expected, saved)
        server.serve(request(NanoHTTPD.Method.GET, "/api/pattern", authorization)).use { response ->
            val json = JsonParser.parseString(response.data.bufferedReader().readText()).asJsonObject
            assertEquals(expected, json["pattern"].asString)
        }
    }

    private data class Editor(
        val server: NanoHTTPD,
        val url: (String) -> String,
        val paths: List<String>
    )

    private fun editors(onRead: () -> Unit = {}, onWrite: () -> Unit = {}): List<Editor> {
        val poster = CustomPosterConfigServer({ onRead(); "saved-pattern" }, { onWrite() })
        val badges = StreamBadgeConfigServer({ onRead(); StreamBadgeSettings() }, { onWrite() })
        val debrid = DebridFormatterConfigServer({ onRead(); DebridFormatterSettings("name", "description") }, { onWrite() })
        val repositories = RepositoryConfigServer(mockk(relaxed = true), { onRead(); emptyList() }, { onWrite() })
        val addons = AddonConfigServer(mockk(relaxed = true), AddonWebConfigMode.FULL,
            { onRead(); PageState(emptyList(), emptyList()) }, { onWrite() })
        return listOf(
            Editor(poster, poster::pairingUrl, listOf("/api/pattern", "/api/clear")),
            Editor(badges, badges::pairingUrl, listOf("/api/settings", "/api/badges/import", "/api/badges/active", "/api/badges/delete")),
            Editor(debrid, debrid::pairingUrl, listOf("/api/settings")),
            Editor(repositories, repositories::pairingUrl, listOf("/api/repositories", "/api/status/test")),
            Editor(addons, addons::pairingUrl, listOf("/api/addons", "/api/state", "/api/collections"))
        )
    }

    private fun authorization(editor: Editor): String = "Bearer " + editor.url("192.0.2.1").substringAfter('#')

    private fun request(
        method: NanoHTTPD.Method,
        path: String,
        authorization: String? = null,
        body: String = "",
        contentLength: String = body.toByteArray().size.toString()
    ): NanoHTTPD.IHTTPSession = mockk(relaxed = true) {
        every { uri } returns path
        every { this@mockk.method } returns method
        every { headers } returns mapOf("authorization" to authorization.orEmpty(), "content-length" to contentLength)
        every { inputStream } answers { ByteArrayInputStream(body.toByteArray()) }
    }
}
