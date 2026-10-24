package com.nuvio.tv.core.backup

/** An all-profile archive must not disclose a locked viewer's history or bypass their PIN on restore. */
internal object BackupProfileLockPolicy {
    fun requireUnlocked(encoded: String?) {
        val records = encoded.orEmpty().split(',').filter(String::isNotBlank).map { entry ->
            val fields = entry.split(':')
            require(fields.size == 2) { "The local profile lock state is invalid." }
            val id = fields[0].toIntOrNull()
            val enabled = fields[1].toBooleanStrictOrNull()
            require(id != null && id in 1..6 && enabled != null) { "The local profile lock state is invalid." }
            id to enabled
        }
        require(records.map { it.first }.toSet().size == records.size) { "The local profile lock state is invalid." }
        require(records.none { it.second }) {
            "Turn off profile PIN locks in Syclop before creating or restoring a backup. Backups never disclose or bypass a locked profile."
        }
    }
}
