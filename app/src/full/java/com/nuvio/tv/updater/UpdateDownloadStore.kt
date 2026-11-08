package com.nuvio.tv.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps a verified release APK in app-private storage until the user installs it. */
@Singleton
class UpdateDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) {
    // Files storage instead of cache, which the system may clear before the update is installed.
    private val updatesDir: File get() = File(context.filesDir, "updates")

    private val downloadMutex = Mutex()

    fun destinationFor(update: AppUpdate): File {
        val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(updatesDir, safeName)
    }

    /** Verifies cached bytes again before they can be passed to Android's installer. */
    private suspend fun downloadedFileFor(update: AppUpdate): File? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        val file = destinationFor(update)
        return file.takeIf { tag == update.tag && path == it.absolutePath && isVerifiedUpdate(update, it) }
    }

    suspend fun download(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<File> = downloadMutex.withLock {
        downloadedFileFor(update)?.let { return@withLock Result.success(it) }
        val destination = destinationFor(update)
        val result = apkDownloader.download(update, destination, onProgress)
        val file = result.getOrNull() ?: return@withLock result
        if (!isVerifiedUpdate(update, file)) {
            file.delete()
            return@withLock Result.failure(IllegalStateException("Update APK verification failed"))
        }
        updatesDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        updatePreferences.setDownloadedUpdate(update.tag, file.absolutePath)
        result
    }

    /** Drops the downloaded APK once it is installed, outdated, or no longer wanted. */
    private suspend fun clear() = downloadMutex.withLock {
        updatesDir.deleteRecursively()
        updatePreferences.setDownloadedUpdate(null, null)
    }

    suspend fun clearIfInstalled() {
        val (tag, _) = updatePreferences.downloadedUpdate.first() ?: return
        if (!VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) clear()
    }

    @Suppress("DEPRECATION")
    private fun isVerifiedUpdate(update: AppUpdate, file: File): Boolean = runCatching {
        if (!file.isFile || file.length() != update.assetSizeBytes) return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (digest.digest().hexSha256() != update.assetSha256) return@runCatching false

        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: return@runCatching false
        if (archive.packageName != installed.packageName || archive.packageName != BuildConfig.APPLICATION_ID) return@runCatching false
        if ((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) return@runCatching false
        if (!UpdateArtifactPolicy.isNewerVersion(update.tag, archive.versionCodeLong(), archive.versionName, installed.versionCodeLong())) {
            return@runCatching false
        }
        val installedSigners = installed.apkSigners()
        if (installedSigners.isEmpty() || archive.apkSigners() != installedSigners) return@runCatching false

        val packagedAbis = ZipFile(file).use { apk ->
            apk.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.removePrefix("lib/").substringBefore('/') }
                .toSet()
        }
        UpdateArtifactPolicy.matchesAbi(update.assetName, packagedAbis, supportedUpdateAbis())
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun PackageInfo.apkSigners() =
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty().toSet()

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeLong(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
}
