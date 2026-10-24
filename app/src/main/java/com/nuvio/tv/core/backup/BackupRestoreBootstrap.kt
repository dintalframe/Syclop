package com.nuvio.tv.core.backup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Xml
import androidx.datastore.preferences.core.*
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Runs before super.attachBaseContext: no DataStore or SharedPreferences instance may be open. */
object BackupRestoreBootstrap {
    fun root(context: Context) = File(context.noBackupFilesDir, "syclop_restore").apply { mkdirs() }
    private fun targets(context: Context) = linkedMapOf(
        "datastore" to File(context.filesDir, "datastore"),
        "shared_prefs" to File(context.applicationInfo.dataDir, "shared_prefs"),
        "cw_enrichment" to File(context.filesDir, "cw_enrichment")
    )
    fun transaction(context: Context) = BackupDirectoryTransaction(targets(context), File(root(context), "recovery"), ::syncDirectory)
    fun canUndo(context: Context) = transaction(context).canUndo()
    private fun queue(context: Context) = BackupRestoreQueue(root(context), ::syncDirectory)
    fun pending(context: Context) = queue(context).isPending()
    fun notice(context: Context): String? = File(root(context), "notice.txt").takeIf { it.isFile && it.length() < 4096 }?.readText()

    fun applyBeforeInitialization(context: Context) {
        if (context.packageName != PortableBackupArchive.PACKAGE) return
        val transaction = transaction(context)
        // An active mixed-state recovery must succeed before the application is allowed to initialize.
        val recovery = transaction.recoverOnColdStart()
        if (recovery != BackupDirectoryTransaction.Outcome.NONE) {
            cancelPending(context)
            writeNotice(context, if (recovery == BackupDirectoryTransaction.Outcome.ROLLED_BACK)
                "An interrupted restore was rolled back. Your previous local data and accounts were recovered."
                else "Local restore completed. Reconnect your accounts and private providers; Undo remains available on this installation.")
        }
        prunePreparation(context)
        val actionFile = File(root(context), "pending.json")
        if (!actionFile.exists()) {
            File(root(context), "pending.zip").delete() // Death between consuming a request and APPLYING safely cancels it.
            return
        }
        var candidate: File? = null
        try {
            val request = queue(context).readOrReject()
            if (request == BackupRestoreQueue.Request.Rejected) {
                writeNotice(context, "The queued restore was rejected before local data changed. Choose a valid Syclop backup and try again.")
                return
            }
            require(request != BackupRestoreQueue.Request.None)
            val action = if (request == BackupRestoreQueue.Request.Undo) "undo" else "restore"
            requireNoProfileLocks(context)
            val archive = (request as? BackupRestoreQueue.Request.Restore)?.backup
            candidate = archive?.let { prepare(context, it) }
            // Consuming a request before APPLYING means a death here safely cancels it, never applies it twice.
            queue(context).consume()
            val result = if (action == "undo") transaction.undo() else transaction.commit(
                targets(context).mapValues { (name, _) -> File(requireNotNull(candidate), name) })
            writeNotice(context, when {
                result == BackupDirectoryTransaction.Outcome.ROLLED_BACK -> "Restore failed and was rolled back. Previous local data and accounts remain."
                action == "undo" -> "Previous local data and account connections were restored from the private Undo snapshot."
                else -> "Local restore completed. Reconnect all accounts and private providers. Public app registrations were retained; local Library and progress are selected. Undo is available on this installation."
            })
        } catch (failure: Exception) {
            // Only pre-apply errors are recoverable here. An active journal means rollback needs attention.
            if (File(root(context), "recovery/active.json").exists()) throw failure
            val quarantine = File(root(context), "rejected-${UUID.randomUUID()}.json")
            if (actionFile.exists()) actionFile.renameTo(quarantine)
            writeNotice(context, "The queued restore was rejected before local data changed. Choose a valid Syclop backup and try again.")
        } finally {
            candidate?.deleteRecursively()
            if (!actionFile.exists()) File(root(context), "pending.zip").delete()
        }
    }

    fun queue(context: Context, backup: PortableBackup? = null) {
        require(!pending(context)) { "Cancel the queued restore first." }
        requireNoProfileLocks(context)
        if (backup == null) require(canUndo(context)) { "No private Undo snapshot is available." }
        queue(context).queue(backup)
    }
    fun cancelPending(context: Context) {
        queue(context).cancel()
    }
    private fun requireNoProfileLocks(context: Context) {
        val file = File(context.filesDir, "datastore/profile_lock_state.preferences_pb")
        val preferences = if (file.exists()) readPreferences(file) else emptyPreferences()
        val value = preferences[stringPreferencesKey("pin_enabled_map")].orEmpty()
        require(!value.split(',').any { it.substringAfter(':', "").equals("true", ignoreCase = true) }) {
            "Turn off profile PIN locks in Syclop before restoring. Backup files never remove or bypass a PIN lock."
        }
    }
    private fun prepare(context: Context, backup: PortableBackup): File {
        val candidate = File(root(context), "prepare-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val datastore = File(candidate, "datastore").apply { check(mkdir()) }
            val shared = File(candidate, "shared_prefs").apply { check(mkdir()) }
            File(candidate, "cw_enrichment").mkdir()
            // Preserve device-only settings, not user accounts, private endpoints, locks, or old profile data.
            val existing = File(context.filesDir, "datastore")
            setOf("app_onboarding", "debug_settings", "device_local_player_prefs", "sentry_settings", "torrent_settings", "tv_channel_prefs").forEach { name ->
                File(existing, "$name.preferences_pb").takeIf(File::isFile)?.let { file ->
                    require(file.length() <= 32L * 1024 * 1024)
                    file.copyTo(File(datastore, file.name))
                }
            }
            backup.stores.filter { it.feature != "profile_settings" }.forEach { store ->
                val values = store.values.toMutableMap()
                if (store.feature == "trakt_settings") {
                    values["watch_progress_source"] = PortableValue("string", JsonPrimitive("NUVIO_SYNC"))
                    values["library_source_mode"] = PortableValue("string", JsonPrimitive("LOCAL"))
                    values.remove("remembered_watch_progress_source"); values.remove("remembered_library_source_mode")
                }
                writePreferences(File(datastore, storeName(store.profileId, store.feature)), values)
            }
            // Every imported profile starts in local mode even when its archive has no tracking settings store.
            backup.profiles.filter { profile -> backup.stores.none { it.profileId == profile.id && it.feature == "trakt_settings" } }.forEach { profile ->
                writePreferences(File(datastore, storeName(profile.id, "trakt_settings")), mapOf(
                    "watch_progress_source" to PortableValue("string", JsonPrimitive("NUVIO_SYNC")),
                    "library_source_mode" to PortableValue("string", JsonPrimitive("LOCAL"))))
            }
            val profileSettings = backup.stores.firstOrNull { it.feature == "profile_settings" && it.profileId == 1 }?.values.orEmpty().toMutableMap()
            profileSettings["profiles_json"] = PortableValue("string", JsonPrimitive(Gson().toJson(backup.profiles)))
            profileSettings["active_profile_id"] = PortableValue("int", JsonPrimitive(1))
            profileSettings["profile_has_ever_selected"] = PortableValue("boolean", JsonPrimitive(false))
            writePreferences(File(datastore, "profile_settings.preferences_pb"), profileSettings)
            val oldShared = File(context.applicationInfo.dataDir, "shared_prefs")
            setOf("app_locale.xml", "image_performance.xml", "syclop_trakt_app_registration.xml").forEach { name ->
                File(oldShared, name).takeIf(File::isFile)?.let { file ->
                    require(file.length() <= 1024 * 1024); file.copyTo(File(shared, name))
                }
            }
            // MAL puts public app configuration and encrypted accounts in one XML. Keep the registration only.
            File(oldShared, "syclop_mal_account.xml").takeIf(File::isFile)?.let { original ->
                publicMalConfiguration(original)?.let { publicValue ->
                    val output = File(shared, original.name)
                    val serializer = Xml.newSerializer()
                    FileOutputStream(output).use { stream ->
                        serializer.setOutput(stream, "UTF-8"); serializer.startDocument("UTF-8", true)
                        serializer.startTag(null, "map"); serializer.startTag(null, "string"); serializer.attribute(null, "name", "application_configuration")
                        serializer.text(publicValue); serializer.endTag(null, "string"); serializer.endTag(null, "map"); serializer.endDocument(); serializer.flush(); stream.fd.sync()
                    }
                }
            }
            return candidate
        } catch (failure: Exception) { candidate.deleteRecursively(); throw failure }
    }
    private fun publicMalConfiguration(file: File): String? {
        require(file.length() <= 4L * 1024 * 1024)
        file.inputStream().use { input ->
            val parser = Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); parser.setInput(input, "UTF-8")
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string" && parser.getAttributeValue(null, "name") == "application_configuration")
                    return parser.nextText().also { require(it.length <= 16_384) }
                parser.next()
            }
        }
        return null
    }
    private fun prunePreparation(context: Context) {
        root(context).listFiles()?.filter { it.name.startsWith("prepare-") || it.name.startsWith("rejected-") }?.forEach { it.deleteRecursively() }
    }
    private fun storeName(profileId: Int, feature: String) = (if (profileId == 1) feature else "${feature}_p$profileId") + ".preferences_pb"
    fun readPreferences(file: File): Preferences = runBlocking {
        require(file.length() <= 32L * 1024 * 1024)
        PreferencesSerializer.readFrom(Buffer().apply { file.inputStream().use { readFrom(it) } })
    }
    private fun writePreferences(file: File, values: Map<String, PortableValue>) {
        val preferences = mutablePreferencesOf()
        values.forEach { (key, cell) -> when (cell.type) {
            "boolean" -> preferences[booleanPreferencesKey(key)] = cell.value.asBoolean
            "int" -> preferences[intPreferencesKey(key)] = cell.value.asInt
            "long" -> preferences[longPreferencesKey(key)] = cell.value.asLong
            "float" -> preferences[floatPreferencesKey(key)] = cell.value.asFloat
            "double" -> preferences[doublePreferencesKey(key)] = cell.value.asDouble
            "string" -> preferences[stringPreferencesKey(key)] = cell.value.asString
            "string_set" -> preferences[stringSetPreferencesKey(key)] = cell.value.asJsonArray.map { it.asString }.toSet()
            else -> error("Unsupported portable value.")
        } }
        val buffer = Buffer(); runBlocking { PreferencesSerializer.writeTo(preferences, buffer) }
        atomicWrite(file, buffer.readByteArray())
    }
    private fun writeNotice(context: Context, text: String) = atomicWrite(File(root(context), "notice.txt"), text.toByteArray())
    fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try { FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }; check(temporary.renameTo(target)); syncDirectory(target.parentFile!!) }
        finally { temporary.delete() }
    }
    fun syncDirectory(directory: File) {
        require(directory.isDirectory)
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}
