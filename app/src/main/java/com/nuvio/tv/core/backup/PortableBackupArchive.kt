package com.nuvio.tv.core.backup

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.*
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PortableProfile(val id: Int, val name: String, val avatarColorHex: String,
    val avatarId: String? = null, val usesPrimaryAddons: Boolean = false, val usesPrimaryPlugins: Boolean = false)
data class PortableValue(val type: String, val value: JsonElement)
data class PortableStore(val profileId: Int, val feature: String, val values: Map<String, PortableValue>)
data class PortableBackup(val createdAt: String, val sourceVersion: String,
    val profiles: List<PortableProfile>, val stores: List<PortableStore>)

/** No archive entry is ever extracted into a filesystem path. Hashes detect corruption, not authorship. */
object PortableBackupArchive {
    const val PACKAGE = "com.dintal.syclop"
    const val SCHEMA = 1
    const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
    private val gson = Gson()

    fun encode(backup: PortableBackup): ByteArray {
        validate(backup)
        val payload = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Backup data is too large." }
        val manifest = JsonObject().apply {
            addProperty("format", "syclop-portable-backup")
            addProperty("package", PACKAGE)
            addProperty("schema", SCHEMA)
            addProperty("payloadBytes", payload.size)
            addProperty("sha256", sha256(payload))
        }.toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, bytes) in listOf("manifest.json" to manifest, "payload.json" to payload)) {
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray().also { require(it.size <= MAX_ARCHIVE_BYTES) { "Backup archive is too large." } }
    }

    fun decode(input: InputStream): PortableBackup {
        val bytes = input.readBounded(MAX_ARCHIVE_BYTES)
        // Our format is a single-disk, two-entry ZIP with no comment. Reject truncated central records.
        require(bytes.size >= 22) { "This is not a complete Syclop backup." }
        val end = ByteBuffer.wrap(bytes, bytes.size - 22, 22).order(ByteOrder.LITTLE_ENDIAN)
        require(end.int == 0x06054b50 && end.short.toInt() == 0 && end.short.toInt() == 0 &&
            end.short.toInt() == 2 && end.short.toInt() == 2) { "The backup archive is incomplete or unsupported." }
        val centralSize = end.int.toLong() and 0xffffffffL
        val centralOffset = end.int.toLong() and 0xffffffffL
        require(end.short.toInt() == 0 && centralOffset + centralSize == bytes.size - 22L) {
            "The backup archive directory is invalid."
        }
        val directory = validateDirectory(bytes, centralOffset.toInt(), centralSize.toInt())
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in setOf("manifest.json", "payload.json") && entry.name !in entries) {
                    "Unexpected, duplicate or unsafe archive entry."
                }
                entries[entry.name] = zip.readBounded(if (entry.name == "manifest.json") 16_384 else MAX_PAYLOAD_BYTES)
                zip.closeEntry() // Also validates ZIP CRC.
                val expected = directory.getValue(entry.name)
                require(entry.crc == expected.crc && entry.compressedSize == expected.compressed && entries.getValue(entry.name).size.toLong() == expected.size) {
                    "The archive directory does not match its data."
                }
            }
        }
        require(entries.keys == setOf("manifest.json", "payload.json")) { "The backup is missing required entries." }
        val manifest = strictJson(entries.getValue("manifest.json")).asJsonObject
        require(manifest.string("format") == "syclop-portable-backup" && manifest.string("package") == PACKAGE) {
            "This backup belongs to another application."
        }
        require(manifest.integer("schema") == SCHEMA) { "This backup schema is not supported by this Syclop build." }
        val payload = entries.getValue("payload.json")
        require(manifest.integer("payloadBytes") == payload.size && manifest.string("sha256") == sha256(payload)) {
            "Backup integrity check failed. No data was changed."
        }
        val root = strictJson(payload).asJsonObject
        val profiles = root.getAsJsonArray("profiles").map { element ->
            val p = element.asJsonObject
            PortableProfile(p.integer("id"), p.string("name"), p.string("avatarColorHex"),
                p.get("avatarId")?.takeUnless(JsonElement::isJsonNull)?.asString,
                p.get("usesPrimaryAddons")?.asBoolean ?: false, p.get("usesPrimaryPlugins")?.asBoolean ?: false)
        }
        val stores = root.getAsJsonArray("stores").map { element ->
            val s = element.asJsonObject
            PortableStore(s.integer("profileId"), s.string("feature"), s.getAsJsonObject("values").entrySet().associate { (name, cell) ->
                val c = cell.asJsonObject
                name to PortableValue(c.string("type"), requireNotNull(c.get("value")))
            })
        }
        return PortableBackup(root.string("createdAt"), root.string("sourceVersion"), profiles, stores).also(::validate)
    }

    fun validate(backup: PortableBackup) {
        require(backup.createdAt.length in 1..80 && backup.sourceVersion.length in 1..80)
        require(backup.profiles.size in 1..6 && backup.profiles.map { it.id }.toSet().size == backup.profiles.size)
        require(backup.profiles.any { it.id == 1 }) { "A primary profile is required." }
        backup.profiles.forEach { profile ->
            require(profile.id in 1..6 && profile.name.length in 1..120 && profile.name.none(Char::isISOControl))
            require(profile.avatarColorHex.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))
            require(profile.avatarId == null || profile.avatarId.length <= 128 && PortableBackupPolicy.safeText(profile.avatarId))
        }
        require(backup.stores.size <= 180 && backup.stores.map { it.profileId to it.feature }.toSet().size == backup.stores.size)
        backup.stores.forEach { store ->
            require(backup.profiles.any { it.id == store.profileId }) { "A backup store has no matching profile." }
            require(store.feature in PortableBackupPolicy.features && store.values.size <= 20_000) { "Unsupported backup data store." }
            store.values.forEach { (key, value) ->
                require(key.length in 1..512 && PortableBackupPolicy.sanitize(store.feature, key, value) == value) {
                    "The backup contains unsupported or credential-bearing data."
                }
            }
        }
    }

    private data class DirectoryEntry(val crc: Long, val compressed: Long, val size: Long)
    private fun validateDirectory(bytes: ByteArray, start: Int, size: Int): Map<String, DirectoryEntry> {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun short(at: Int) = data.getShort(at).toInt() and 0xffff
        fun uint(at: Int) = data.getInt(at).toLong() and 0xffffffffL
        val result = linkedMapOf<String, DirectoryEntry>()
        var cursor = start
        repeat(2) {
            require(cursor >= 0 && cursor.toLong() + 46 <= start.toLong() + size && data.getInt(cursor) == 0x02014b50) {
                "Invalid central-directory record."
            }
            val flags = short(cursor + 8)
            val method = short(cursor + 10)
            val compressed = uint(cursor + 20)
            val uncompressed = uint(cursor + 24)
            val nameSize = short(cursor + 28)
            val extraSize = short(cursor + 30)
            val commentSize = short(cursor + 32)
            val recordEnd = cursor.toLong() + 46 + nameSize + extraSize + commentSize
            require(flags and 1 == 0 && method in setOf(0, 8) && short(cursor + 34) == 0 &&
                compressed <= MAX_ARCHIVE_BYTES && uncompressed <= MAX_PAYLOAD_BYTES && recordEnd <= start.toLong() + size)
            val name = bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize).toString(Charsets.UTF_8)
            require(name in setOf("manifest.json", "payload.json") && name !in result)
            val local = uint(cursor + 42)
            require(local + 30 <= start && data.getInt(local.toInt()) == 0x04034b50)
            val offset = local.toInt()
            require(short(offset + 6) == flags && short(offset + 8) == method)
            val localNameSize = short(offset + 26)
            val localExtraSize = short(offset + 28)
            val localEnd = local + 30 + localNameSize + localExtraSize
            require(localEnd + compressed <= start && localNameSize == nameSize &&
                bytes.copyOfRange(offset + 30, offset + 30 + localNameSize).contentEquals(bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize)))
            result[name] = DirectoryEntry(uint(cursor + 16), compressed, uncompressed)
            cursor = recordEnd.toInt()
        }
        require(cursor == start + size) { "Unexpected central-directory data." }
        return result
    }

    internal fun strictJson(bytes: ByteArray): JsonElement {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        var nodes = 0
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            fun read(depth: Int): JsonElement {
                require(depth <= 24 && ++nodes <= 250_000) { "Backup JSON is too complex." }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(name.length <= 512 && !has(name)) { "Duplicate or oversized JSON key." }
                            add(name, read(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                        reader.beginArray()
                        while (reader.hasNext()) { require(size() < 50_000); add(read(depth + 1)) }
                        reader.endArray()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 8_388_608) })
                    JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString().also { require(it.length <= 128) }))
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> error("Invalid backup JSON.")
                }
            }
            val value = read(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected trailing JSON data." }
            return value
        }
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    internal fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Backup data exceeds its size limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun JsonObject.string(name: String): String = requireNotNull(get(name)).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
    }
    private fun JsonObject.integer(name: String): Int = requireNotNull(get(name)).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber); it.asBigDecimal.intValueExact()
    }
}
