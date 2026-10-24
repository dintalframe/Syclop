package com.nuvio.tv.core.backup

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Directory swaps run before DataStore/SharedPreferences open; private original credentials are part of rollback. */
class BackupDirectoryTransaction(
    private val targets: Map<String, File>,
    private val root: File,
    private val syncDirectory: (File) -> Unit,
    private val afterSwap: (Int) -> Unit = {}
) {
    enum class Outcome { NONE, APPLIED, ROLLED_BACK }
    private data class Journal(val id: String, val status: String, val original: Map<String, Boolean>)
    private val gson = Gson()
    private val active get() = File(root, "active.json")
    private val last get() = File(root, "last.json")
    private val transactions get() = File(root, "transactions")

    init { require(targets.keys == setOf("datastore", "shared_prefs", "cw_enrichment")) }

    fun canUndo(): Boolean = runCatching { lastId()?.let { readJournal(it).status == "COMPLETED" } == true }.getOrDefault(false)

    fun recoverOnColdStart(): Outcome {
        if (!active.exists()) {
            // Candidate copies abandoned before APPLYING are not user data; retain only the last Undo snapshot.
            val keep = runCatching { lastId() }.getOrNull()
            if (transactions.exists()) transactions.listFiles()?.filter { validId(it.name) && it.name != keep }?.forEach { it.deleteRecursively() }
            return Outcome.NONE
        }
        val id = readId(active)
        val journal = readJournal(id)
        return when (journal.status) {
            "APPLYING" -> {
                rollback(journal)
                writeJournal(journal.copy(status = "ROLLED_BACK"))
                check(active.delete())
                syncDirectory(root)
                Outcome.ROLLED_BACK
            }
            "COMPLETED" -> { runCatching { finalizeCompleted(journal) }; Outcome.APPLIED }
            "ROLLED_BACK" -> { active.delete(); syncDirectory(root); Outcome.ROLLED_BACK }
            else -> error("Unrecognized private restore journal. Start was stopped to protect local data.")
        }
    }

    /** Candidate data is internal, not an extracted archive. The archive only supplies validated portable values. */
    fun commit(candidate: Map<String, File?>): Outcome {
        require(candidate.keys == targets.keys)
        require(!active.exists()) { "An interrupted restore must recover before another restore can begin." }
        root.mkdirs(); transactions.mkdirs()
        val id = UUID.randomUUID().toString()
        val transaction = directory(id).apply { check(mkdir()) }
        val staged = File(transaction, "new").apply { check(mkdir()) }
        File(transaction, "old").mkdir()
        var totalBytes = 0L
        try {
            candidate.forEach { (name, source) ->
                if (source != null) {
                    val destination = File(staged, name).apply { check(mkdir()) }
                    totalBytes += copyFlatDirectory(source, destination)
                    require(totalBytes <= 128L * 1024 * 1024) { "The local recovery snapshot is too large." }
                }
            }
            var originalBytes = 0L
            targets.values.filter(File::exists).forEach { directory ->
                originalBytes += validateFlatDirectory(directory)
                require(originalBytes <= 128L * 1024 * 1024) { "The existing local state is too large for a reversible restore." }
            }
            val original = targets.mapValues { (_, directory) ->
                require(!directory.exists() || directory.isDirectory)
                directory.exists()
            }
            val applying = Journal(id, "APPLYING", original)
            writeJournal(applying)
            writeId(active, id)
            try {
                targets.entries.forEachIndexed { index, (name, destination) ->
                    destination.parentFile!!.mkdirs()
                    if (destination.exists()) moveDirectory(destination, File(transaction, "old/$name"))
                    File(staged, name).takeIf(File::exists)?.let { moveDirectory(it, destination) }
                    afterSwap(index)
                }
                writeJournal(applying.copy(status = "COMPLETED"))
            } catch (failure: Exception) {
                rollback(applying) // Failure here propagates; never expose a mixed application state.
                writeJournal(applying.copy(status = "ROLLED_BACK"))
                check(active.delete())
                syncDirectory(root)
                return Outcome.ROLLED_BACK
            }
            runCatching { finalizeCompleted(applying.copy(status = "COMPLETED")) }
            return Outcome.APPLIED
        } finally {
            // After process death/Error, the active journal retains everything required for next-start recovery.
            if (!active.exists() && runCatching { lastId() != id }.getOrDefault(false)) transaction.deleteRecursively()
        }
    }

    fun undo(): Outcome {
        val id = requireNotNull(lastId()) { "No private restore snapshot is available." }
        val journal = readJournal(id)
        require(journal.status == "COMPLETED")
        return commit(targets.mapValues { (name, _) ->
            if (journal.original.getValue(name)) File(directory(id), "old/$name") else null
        })
    }

    private fun rollback(journal: Journal) {
        targets.forEach { (name, target) ->
            val original = File(directory(journal.id), "old/$name")
            if (original.exists()) {
                check(!target.exists() || target.deleteRecursively())
                syncDirectory(target.parentFile!!)
                moveDirectory(original, target)
            } else if (!journal.original.getValue(name)) {
                check(!target.exists() || target.deleteRecursively())
                syncDirectory(target.parentFile!!)
            } else {
                check(target.isDirectory) { "An original restore directory is missing; automatic start was stopped." }
            }
        }
    }
    private fun finalizeCompleted(journal: Journal) {
        writeId(last, journal.id)
        check(!active.exists() || active.delete())
        syncDirectory(root)
        transactions.listFiles()?.filter { it.name != journal.id && validId(it.name) }?.forEach { it.deleteRecursively() }
        syncDirectory(transactions)
    }
    private fun moveDirectory(source: File, destination: File) {
        destination.parentFile!!.mkdirs()
        check(!destination.exists() && source.renameTo(destination)) { "Could not swap a private restore directory." }
        syncDirectory(source.parentFile!!)
        syncDirectory(destination.parentFile!!)
    }
    private fun copyFlatDirectory(source: File, destination: File): Long {
        require(source.isDirectory)
        validateFlatDirectory(source)
        var bytes = 0L
        val entries = source.listFiles().orEmpty()
        require(entries.size <= 1024)
        entries.forEach { file ->
            require(file.isFile && file.name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) &&
                file.canonicalFile.parentFile == source.canonicalFile && file.canonicalFile.name == file.name) {
                "Unsupported local recovery snapshot entry."
            }
            require(file.length() <= 32L * 1024 * 1024)
            bytes += file.length()
            require(bytes <= 128L * 1024 * 1024)
            FileOutputStream(File(destination, file.name)).use { output ->
                file.inputStream().use { it.copyTo(output) }; output.fd.sync()
            }
        }
        syncDirectory(destination)
        return bytes
    }
    private fun validateFlatDirectory(source: File): Long {
        require(source.isDirectory && source.canonicalFile.name == source.name && source.canonicalFile.parentFile == source.parentFile!!.canonicalFile) { "Unsupported private snapshot directory." }
        val entries = requireNotNull(source.listFiles())
        require(entries.size <= 1024) { "Too many private snapshot entries for reversible restore." }
        var bytes = 0L
        entries.forEach { file ->
            require(file.isFile && file.name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) &&
                file.canonicalFile.parentFile == source.canonicalFile && file.canonicalFile.name == file.name)
            require(file.length() <= 32L * 1024 * 1024) { "An existing local file is too large for reversible restore." }
            bytes += file.length()
            require(bytes <= 128L * 1024 * 1024)
        }
        return bytes
    }
    private fun directory(id: String): File { require(validId(id)); return File(transactions, id) }
    private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    private fun lastId() = if (last.exists()) readId(last) else null
    private fun readId(file: File): String {
        require(file.length() <= 1024)
        val id = PortableBackupArchive.strictJson(file.readBytes()).asJsonObject["id"].asString
        require(validId(id)); return id
    }
    private fun writeId(file: File, id: String) = atomicWrite(file, gson.toJson(mapOf("id" to id)).toByteArray())
    private fun readJournal(id: String): Journal {
        val file = File(directory(id), "journal.json")
        require(file.length() <= 8192)
        val json = PortableBackupArchive.strictJson(file.readBytes()).asJsonObject
        require(json["package"].asString == PortableBackupArchive.PACKAGE && json["schema"].asInt == 1 && json["id"].asString == id)
        val original = json.getAsJsonObject("original").entrySet().associate { it.key to it.value.asBoolean }
        require(original.keys == targets.keys)
        return Journal(id, json["status"].asString, original)
    }
    private fun writeJournal(journal: Journal) {
        val json = gson.toJsonTree(journal).asJsonObject.apply { addProperty("package", PortableBackupArchive.PACKAGE); addProperty("schema", 1) }
        atomicWrite(File(directory(journal.id), "journal.json"), json.toString().toByteArray())
    }
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(target))
            syncDirectory(target.parentFile!!)
        } finally { temporary.delete() }
    }
}
