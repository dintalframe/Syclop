package com.nuvio.tv.core.backup

import java.io.IOException

/** Publishing is part of the save transaction: a still-pending row is not a successful export. */
internal object BackupMediaStorePublication {
    fun save(write: () -> Unit, publish: () -> Int, remove: () -> Unit) {
        try {
            write()
            if (publish() != 1) throw IOException("The local backup could not be published.")
        } catch (failure: Exception) {
            try { remove() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}

/** Octet-stream preserves our custom extension; application/zip adds .zip on Android 11. */
internal object LocalBackupFileNames {
    const val MIME_TYPE = "application/octet-stream"
    fun isBackup(name: String): Boolean = name.startsWith("Syclop-") &&
        (name.endsWith(".syclop") || name.endsWith(".syclop.zip")) &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }
}
