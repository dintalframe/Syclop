package com.nuvio.tv.core.backup

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Validates a queued archive again at cold start. Invalid queues never prevent intact old stores opening. */
class BackupRestoreQueue(private val root: File, private val syncDirectory: (File) -> Unit) {
    sealed class Request {
        data object None : Request()
        data object Undo : Request()
        data object Rejected : Request()
        data class Restore(val backup: PortableBackup) : Request()
    }
    private val action get() = File(root, "pending.json")
    private val archive get() = File(root, "pending.zip")
    fun isPending() = action.exists()
    fun readOrReject(): Request {
        if (!action.exists()) return Request.None
        return try {
            require(action.length() <= 1024)
            val json = PortableBackupArchive.strictJson(action.readBytes()).asJsonObject
            require(json.keySet() == setOf("action"))
            when (json["action"].asString) {
                "restore" -> Request.Restore(archive.inputStream().use(PortableBackupArchive::decode))
                "undo" -> Request.Undo
                else -> error("Unsupported queued restore.")
            }
        } catch (_: Exception) {
            // The archive is never extracted. Rejecting a bad request leaves all current app stores untouched.
            action.renameTo(File(root, "rejected-${UUID.randomUUID()}.json"))
            if (action.exists()) action.delete()
            archive.delete()
            syncDirectory(root)
            Request.Rejected
        }
    }
    fun queue(backup: PortableBackup?) {
        require(!isPending()) { "Cancel the queued restore first." }
        root.mkdirs()
        if (backup != null) atomicWrite(archive, PortableBackupArchive.encode(backup))
        atomicWrite(action, "{\"action\":\"${if (backup == null) "undo" else "restore"}\"}".toByteArray())
    }
    fun consume() { check(action.delete()); syncDirectory(root) }
    fun cancel() {
        check(!action.exists() || action.delete())
        check(!archive.exists() || archive.delete())
        root.mkdirs(); syncDirectory(root)
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temp = File(root, ".${file.name}.${UUID.randomUUID()}.tmp")
        try { FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }; check(temp.renameTo(file)); syncDirectory(root) }
        finally { temp.delete() }
    }
}
