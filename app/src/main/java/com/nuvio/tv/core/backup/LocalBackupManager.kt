package com.nuvio.tv.core.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.Preferences
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class LocalBackupFile(val name: String, val uri: Uri)

@Singleton
class LocalBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileDataStore,
    private val factory: ProfileDataStoreFactory
) {
    private val lock = Mutex()
    private val localAuthorities = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.providers.media.documents")
    fun fileName() = "Syclop-${Instant.now().toString().replace(':', '-')}.syclop"
    fun canUndo() = BackupRestoreBootstrap.canUndo(context)
    fun pending() = BackupRestoreBootstrap.pending(context)
    fun notice() = BackupRestoreBootstrap.notice(context)

    suspend fun export(destination: Uri? = null): String = lock.withLock { withContext(Dispatchers.IO) {
        val archive = snapshot()
        val bytes = PortableBackupArchive.encode(archive)
        if (destination != null) {
            requireLocalDocument(destination)
            context.contentResolver.openFileDescriptor(destination, "rwt").use { descriptor ->
                requireNotNull(descriptor) { "The local document could not be opened." }
                FileOutputStream(descriptor.fileDescriptor).use { it.write(bytes); it.fd.sync() }
            }
            "Backup saved to the selected local document."
        } else saveOnTv(bytes)
    } }
    suspend fun inspect(uri: Uri): PortableBackup = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" || uri.scheme == "file")
        if (uri.scheme == "file") {
            val directory = ownDirectory().canonicalFile
            val file = File(requireNotNull(uri.path)).canonicalFile
            require(file.parentFile == directory && file.name.endsWith(".syclop"))
            file.inputStream().use(PortableBackupArchive::decode)
        } else {
            val ownMedia = uri.authority == "media" && localFiles().any { it.uri == uri }
            require(ownMedia || uri.authority in localAuthorities) { "Choose a file on this TV or attached storage. Cloud document providers are not used for local backups." }
            context.contentResolver.openInputStream(uri).use { PortableBackupArchive.decode(requireNotNull(it)) }
        }
    }
    suspend fun queue(backup: PortableBackup?) = lock.withLock { withContext(Dispatchers.IO) { BackupRestoreBootstrap.queue(context, backup) } }
    suspend fun cancel() = lock.withLock { withContext(Dispatchers.IO) { BackupRestoreBootstrap.cancelPending(context) } }

    suspend fun localFiles(): List<LocalBackupFile> = withContext(Dispatchers.IO) {
        val fallbackFiles = ownDirectory().listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".syclop") }
            .sortedByDescending(File::lastModified).map { LocalBackupFile(it.name, Uri.fromFile(it)) }
        if (Build.VERSION.SDK_INT < 29) fallbackFiles
        else try {
            val result = mutableListOf<LocalBackupFile>()
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?", arrayOf("Download/SyclopBackups/"), "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { cursor ->
                while (cursor.moveToNext() && result.size < 100) {
                    val name = cursor.getString(1)
                    if (name.startsWith("Syclop-") && name.endsWith(".syclop")) result += LocalBackupFile(name,
                        android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
                }
            }
            result + fallbackFiles
        } catch (_: Exception) { fallbackFiles }
    }
    private suspend fun snapshot(): PortableBackup {
        val viewers = profiles.profilesList.first()
        require(viewers.size in 1..6)
        val portableProfiles = viewers.map { PortableProfile(it.id, it.name, it.avatarColorHex, it.avatarId?.takeIf(PortableBackupPolicy::safeText), it.usesPrimaryAddons, false) }
        val stores = mutableListOf<PortableStore>()
        val globalValues = mapOf(
            "remember_last_profile_enabled" to PortableValue("boolean", JsonPrimitive(profiles.rememberLastProfileEnabled.first())),
            "confirm_exit_enabled" to PortableValue("boolean", JsonPrimitive(profiles.confirmExitEnabled.first())),
            "startup_splash_enabled" to PortableValue("boolean", JsonPrimitive(profiles.startupSplashEnabled.first())))
        stores += PortableStore(1, "profile_settings", globalValues)
        for (profile in viewers) for (feature in PortableBackupPolicy.features - "profile_settings") {
            val filename = (if (profile.id == 1) feature else "${feature}_p${profile.id}") + ".preferences_pb"
            val exists = File(context.filesDir, "datastore/$filename").exists()
            if (!exists) {
                if (feature == "addon_preferences") stores += PortableStore(profile.id, feature, mapOf("installed_addon_urls_ordered" to PortableValue("string", JsonPrimitive("[\"https://v3-cinemeta.strem.io\",\"https://opensubtitles-v3.strem.io\"]"))))
                continue
            }
            val preferences = factory.get(profile.id, feature).data.first()
            val values = preferences.asMap().mapNotNull { (key, value) -> portableValue(value)?.let { key.name to it } }.toMap()
            val store = PortableBackupPolicy.portable(profile.id, feature, values)
            if (store.values.isNotEmpty()) stores += store
        }
        require(profiles.profilesList.first() == viewers) { "Profiles changed during export. Try again." }
        return PortableBackup(Instant.now().toString(), BuildConfig.VERSION_NAME, portableProfiles, stores)
    }
    private fun portableValue(value: Any): PortableValue? = when (value) {
        is String -> PortableValue("string", JsonPrimitive(value))
        is Boolean -> PortableValue("boolean", JsonPrimitive(value))
        is Int -> PortableValue("int", JsonPrimitive(value))
        is Long -> PortableValue("long", JsonPrimitive(value))
        is Float -> PortableValue("float", JsonPrimitive(value))
        is Double -> PortableValue("double", JsonPrimitive(value))
        is Set<*> -> if (value.all { it is String }) PortableValue("string_set", JsonArray().apply { value.sortedBy { it.toString() }.forEach { add(it as String) } }) else null
        else -> null
    }
    private fun requireLocalDocument(uri: Uri) {
        require(uri.scheme == "content" && uri.authority in localAuthorities) {
            "Choose a destination on this TV or attached storage. Cloud document providers are not used for local backups."
        }
    }
    private fun ownDirectory() = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) { "Local Downloads storage is unavailable." }, "SyclopBackups").apply { check(exists() || mkdirs()) }
    private fun saveOnTv(bytes: ByteArray): String {
        val name = fileName()
        if (Build.VERSION.SDK_INT < 29) {
            BackupRestoreBootstrap.atomicWrite(File(ownDirectory(), name), bytes)
            return "Saved to app Downloads/SyclopBackups/$name. Copy this file elsewhere before uninstalling Syclop."
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/SyclopBackups/"); put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        if (uri == null) {
            BackupRestoreBootstrap.atomicWrite(File(ownDirectory(), name), bytes)
            return "Public Downloads is unavailable. Saved to app Downloads/SyclopBackups/$name. Copy this file elsewhere before uninstalling Syclop."
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "w").use { descriptor ->
                requireNotNull(descriptor); FileOutputStream(descriptor.fileDescriptor).use { it.write(bytes); it.fd.sync() }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return "Saved to Downloads/SyclopBackups/$name."
        } catch (failure: Exception) { context.contentResolver.delete(uri, null, null); throw failure }
    }
}
