package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(
    okHttpClient: OkHttpClient
) {
    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun download(
        update: AppUpdate,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        val partial = File(destinationFile.parentFile, "${destinationFile.name}.part")
        return try {
            runCatching {
                require(update.assetSizeBytes in 1L..UpdateArtifactPolicy.MAX_APK_BYTES)
                destinationFile.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                openDownload(update.assetUrl).use { response ->
                    check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                    val body = response.body ?: error("Empty download body")
                    check(body.contentLength() in setOf(-1L, update.assetSizeBytes)) { "Update size mismatch" }
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                downloaded += read
                                check(downloaded <= update.assetSizeBytes) { "Update size mismatch" }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                onProgress(downloaded, update.assetSizeBytes)
                            }
                        }
                    }
                }
                check(downloaded == update.assetSizeBytes && digest.digest().hexSha256() == update.assetSha256) {
                    "Update checksum mismatch"
                }
                check(partial.renameTo(destinationFile)) { "Could not save update" }
                destinationFile
            }.onFailure { if (it is CancellationException) throw it }
        } finally {
            partial.delete()
        }
    }

    private fun openDownload(initialUrl: String): Response {
        var url = initialUrl
        repeat(5) {
            check(UpdateArtifactPolicy.isTrustedDownloadOrigin(url)) { "Untrusted update download" }
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isRedirect) return response
            url = response.use {
                val redirect = it.header("Location") ?: error("Missing update redirect")
                request.url.resolve(redirect)?.toString() ?: error("Invalid update redirect")
            }
        }
        error("Too many update redirects")
    }
}

internal fun ByteArray.hexSha256(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
