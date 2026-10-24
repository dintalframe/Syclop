package com.nuvio.tv.core.backup

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PortableBackupArchiveTest {
    @Test fun `profiles settings library watched history and progress round trip`() {
        val backup = sample()
        val restored = PortableBackupArchive.decode(ByteArrayInputStream(PortableBackupArchive.encode(backup)))
        assertEquals(backup, restored)
        assertEquals("Alice", restored.profiles.single().name)
        assertTrue(restored.stores.first { it.feature == "watched_items_preferences" }.values["watched_items"]!!.value.toString().contains("Frieren"))
    }

    @Test fun `credential stores signed URLs and private playback diagnostics never export`() {
        assertNull(PortableBackupPolicy.sanitize("trakt_auth_store", "access_token", string("secret")))
        assertNull(PortableBackupPolicy.sanitize("debrid_settings", "real_debrid_api_key", string("secret")))
        assertNull(PortableBackupPolicy.sanitize("player_settings", "last_playback_diagnostics_json", string("secret")))
        assertNull(PortableBackupPolicy.sanitize("track_preference", "sub_addon_url|tt123", string("https://addon/user-token")))
        val raw = """{"id":"tt123","type":"series","name":"Frieren","posterShape":"POSTER","genres":[],"poster":"https://image/?api_key=private","addonBaseUrl":"https://addon/private-token"}"""
        val sanitized = PortableBackupPolicy.sanitize("library_preferences", "library_items", PortableValue("string_set", JsonArray().apply { add(raw) }))!!
        assertFalse(sanitized.value.toString().contains("https"))
        assertFalse(sanitized.value.toString().contains("private"))
        assertTrue(sanitized.value.toString().contains("Frieren"))
        val archive = PortableBackupArchive.encode(sample().copy(stores = listOf(PortableStore(1, "library_preferences", mapOf("library_items" to sanitized)))))
        assertEquals(1, PortableBackupArchive.decode(ByteArrayInputStream(archive)).stores.size)
    }

    @Test fun `modified payload with valid ZIP CRC fails SHA integrity before restore`() {
        val entries = unpack(PortableBackupArchive.encode(sample())).toMutableMap()
        entries["payload.json"] = entries.getValue("payload.json").toString(Charsets.UTF_8).replace("Alice", "Other").toByteArray()
        rejects(pack(entries))
    }

    @Test fun `foreign package and unsupported schema are rejected`() {
        val entries = unpack(PortableBackupArchive.encode(sample())).toMutableMap()
        val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        entries["manifest.json"] = manifest.replace(PortableBackupArchive.PACKAGE, "com.other.application").toByteArray()
        rejects(pack(entries))
        entries["manifest.json"] = manifest.replace("\"schema\":1", "\"schema\":99").toByteArray()
        rejects(pack(entries))
    }

    @Test fun `absolute and traversal archive members are rejected without extraction`() {
        for (name in listOf("../payload.json", "/payload.json", "profiles/../../payload.json", "payload.json/")) {
            val entries = unpack(PortableBackupArchive.encode(sample())).toMutableMap()
            val payload = entries.remove("payload.json")!!
            entries[name] = payload
            rejects(pack(entries))
        }
    }

    @Test fun `truncated and oversized archives are rejected`() {
        val archive = PortableBackupArchive.encode(sample())
        rejects(archive.dropLast(10).toByteArray())
        rejects(ByteArray(PortableBackupArchive.MAX_ARCHIVE_BYTES + 1))
    }

    @Test fun `unknown secret keys and malformed typed preferences cannot enter a backup`() {
        val unsafe = PortableStore(1, "theme_settings", mapOf("access_token" to string("private")))
        assertTrue(runCatching { PortableBackupArchive.encode(sample().copy(stores = listOf(unsafe))) }.isFailure)
        val wrongType = PortableStore(1, "theme_settings", mapOf("amoled_mode" to PortableValue("boolean", JsonPrimitive("false"))))
        assertTrue(runCatching { PortableBackupArchive.encode(sample().copy(stores = listOf(wrongType))) }.isFailure)
    }

    @Test fun `duplicate JSON keys excessive nesting and invalid UTF8 are rejected`() {
        assertTrue(runCatching { PortableBackupArchive.strictJson("{\"a\":1,\"a\":2}".toByteArray()) }.isFailure)
        assertTrue(runCatching { PortableBackupArchive.strictJson(("[".repeat(30) + "0" + "]".repeat(30)).toByteArray()) }.isFailure)
        assertTrue(runCatching { PortableBackupArchive.strictJson(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
    }

    @Test fun `correctly hashed wrong-type known preference is rejected before restore`() {
        val entries = unpack(PortableBackupArchive.encode(sample())).toMutableMap()
        val payload = JsonParser.parseString(entries.getValue("payload.json").toString(Charsets.UTF_8)).asJsonObject
        val cell = payload.getAsJsonArray("stores")[0].asJsonObject.getAsJsonObject("values").getAsJsonObject("selected_theme")
        cell.addProperty("type", "boolean")
        cell.addProperty("value", false)
        val bytes = payload.toString().toByteArray()
        val manifest = JsonParser.parseString(entries.getValue("manifest.json").toString(Charsets.UTF_8)).asJsonObject
        manifest.addProperty("payloadBytes", bytes.size)
        manifest.addProperty("sha256", PortableBackupArchive.sha256(bytes))
        entries["manifest.json"] = manifest.toString().toByteArray()
        entries["payload.json"] = bytes
        rejects(pack(entries))
    }

    @Test fun `central-directory records must match the local ZIP data`() {
        val archive = PortableBackupArchive.encode(sample())
        val offset = java.nio.ByteBuffer.wrap(archive).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(archive.size - 6)
        archive[offset] = 0
        rejects(archive)
    }

    @Test fun `oversized season and episode cannot wrap imported watched or progress IDs`() {
        val watched = """{"contentId":"tt123","contentType":"series","title":"Frieren","season":2147483648,"episode":4294967297,"watchedAt":1234}"""
        val result = PortableBackupPolicy.sanitize("watched_items_preferences", "watched_items", PortableValue("string_set", JsonArray().apply { add(watched) }))!!
        assertFalse(result.value.toString().contains("2147483648"))
        assertFalse(result.value.toString().contains("4294967297"))
        val progress = """{"tt123:2:1":{"contentId":"tt123","contentType":"series","name":"Frieren","videoId":"tt123:2:1","season":2147483648,"episode":4294967297,"lastWatched":1234}}"""
        val cell = PortableBackupPolicy.sanitize("watch_progress_recent_preferences", "watch_progress_map", string(progress))!!
        assertFalse(cell.value.toString().contains("2147483648"))
        val missingTime = progress.replace(",\"lastWatched\":1234", "")
        assertEquals("{}", PortableBackupPolicy.sanitize("watch_progress_recent_preferences", "watch_progress_map", string(missingTime))!!.value.asString)
    }

    @Test fun `only exact public provider manifest URLs survive portable export`() {
        val urls = listOf("https://magnify.pages.dev/manifest/full", "https://v3-cinemeta.strem.io/manifest.json", "https://opensubtitles-v3.strem.io",
            "https://magnify.pages.dev/manifest/full?token=secret", "https://magnify.pages.dev:443/manifest/full", "https://user@v3-cinemeta.strem.io",
            "https://v3-cinemeta.strem.io/private", "https://v3-cinemeta.strem.io.evil/manifest.json", "https://opensubtitles-v3.strem.io#secret")
        val sanitized = PortableBackupPolicy.sanitize("addon_preferences", "installed_addon_urls_ordered", string(Gson().toJson(urls)))!!
        val result = JsonParser.parseString(sanitized.value.asString).asJsonArray.map { it.asString }
        assertEquals(listOf("https://magnify.pages.dev/manifest/full", "https://v3-cinemeta.strem.io", "https://opensubtitles-v3.strem.io"), result)
        assertEquals(sanitized, PortableBackupPolicy.sanitize("addon_preferences", "installed_addon_urls_ordered", sanitized))
        assertNull(PortableBackupPolicy.sanitize("debrid_settings", "real_debrid_oauth_state", string("encryptedsecret")))
    }

    private fun sample(): PortableBackup {
        val library = """{"id":"tt123","type":"series","name":"Frieren","posterShape":"POSTER","genres":[],"addedAt":1233}"""
        val watched = """{"contentId":"tt123","contentType":"series","title":"Frieren","season":2,"episode":1,"watchedAt":1234}"""
        val progress = """{"tt123:2:2":{"contentId":"tt123","contentType":"series","name":"Frieren","videoId":"tt123:2:2","season":2,"episode":2,"position":123456,"duration":1440000,"lastWatched":1235,"source":"local"}}"""
        return PortableBackup("2026-10-24T12:00:00Z", "0.1.0", listOf(PortableProfile(1, "Alice", "#123456")), listOf(
            PortableStore(1, "theme_settings", mapOf("selected_theme" to string("WHITE"))),
            PortableStore(1, "library_preferences", mapOf("library_items" to PortableValue("string_set", JsonArray().apply { add(library) }))),
            PortableStore(1, "watched_items_preferences", mapOf("watched_items" to PortableValue("string_set", JsonArray().apply { add(watched) }))),
            PortableStore(1, "watch_progress_recent_preferences", mapOf("watch_progress_map" to string(progress)))
        ))
    }
    private fun string(value: String) = PortableValue("string", JsonPrimitive(value))
    private fun rejects(bytes: ByteArray) = assertTrue(runCatching { PortableBackupArchive.decode(ByteArrayInputStream(bytes)) }.isFailure)
    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes(); zip.closeEntry() }
        }
        return result
    }
    private fun pack(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
}
