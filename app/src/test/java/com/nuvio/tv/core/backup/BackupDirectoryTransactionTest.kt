package com.nuvio.tv.core.backup

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BackupDirectoryTransactionTest {
    private class SimulatedDeath : Error()
    private fun fixture(block: (File, Map<String, File>, File, Map<String, File?>) -> Unit) {
        val base = Files.createTempDirectory("syclop-restore").toFile()
        try {
            val targets = listOf("datastore", "shared_prefs", "cw_enrichment").associateWith { File(base, "app/$it").apply { mkdirs() } }
            File(targets.getValue("datastore"), "profile_settings.preferences_pb").writeText("Bob")
            File(targets.getValue("datastore"), "debrid_settings.preferences_pb.bak").writeText("encrypted-Bob-token")
            File(targets.getValue("shared_prefs"), "syclop_mal_account.xml").writeText("encrypted-Bob-MAL")
            val candidates = targets.keys.associateWith { name -> File(base, "candidate/$name").apply { mkdirs() } as File? }
            File(candidates.getValue("datastore")!!, "profile_settings.preferences_pb").writeText("Alice")
            File(candidates.getValue("shared_prefs")!!, "syclop_trakt_app_registration.xml").writeText("public-client-id")
            block(base, targets, File(base, "private-recovery"), candidates)
        } finally { base.deleteRecursively() }
    }
    @Test fun `foreign profile IDs never inherit old service credentials or shadows`() = fixture { _, targets, root, candidates ->
        val transaction = BackupDirectoryTransaction(targets, root, {})
        assertEquals(BackupDirectoryTransaction.Outcome.APPLIED, transaction.commit(candidates))
        assertEquals("Alice", File(targets.getValue("datastore"), "profile_settings.preferences_pb").readText())
        assertFalse(File(targets.getValue("datastore"), "debrid_settings.preferences_pb.bak").exists())
        assertFalse(File(targets.getValue("shared_prefs"), "syclop_mal_account.xml").exists())
        assertEquals("public-client-id", File(targets.getValue("shared_prefs"), "syclop_trakt_app_registration.xml").readText())
        assertTrue(transaction.canUndo())
        assertEquals(BackupDirectoryTransaction.Outcome.APPLIED, transaction.undo())
        assertEquals("Bob", File(targets.getValue("datastore"), "profile_settings.preferences_pb").readText())
        assertEquals("encrypted-Bob-token", File(targets.getValue("datastore"), "debrid_settings.preferences_pb.bak").readText())
        assertEquals("encrypted-Bob-MAL", File(targets.getValue("shared_prefs"), "syclop_mal_account.xml").readText())
    }
    @Test fun `failure after each directory swap rolls back original data and credentials`() {
        repeat(3) { failingIndex -> fixture { _, targets, root, candidates ->
            val transaction = BackupDirectoryTransaction(targets, root, {}, { if (it == failingIndex) error("disk error") })
            assertEquals(BackupDirectoryTransaction.Outcome.ROLLED_BACK, transaction.commit(candidates))
            assertEquals("Bob", File(targets.getValue("datastore"), "profile_settings.preferences_pb").readText())
            assertEquals("encrypted-Bob-MAL", File(targets.getValue("shared_prefs"), "syclop_mal_account.xml").readText())
            assertFalse(transaction.canUndo())
        } }
    }
    @Test fun `process death after each swap recovers before stores can open`() {
        repeat(3) { deathIndex -> fixture { _, targets, root, candidates ->
            val transaction = BackupDirectoryTransaction(targets, root, {}, { if (it == deathIndex) throw SimulatedDeath() })
            assertTrue(runCatching { transaction.commit(candidates) }.exceptionOrNull() is SimulatedDeath)
            assertEquals(BackupDirectoryTransaction.Outcome.ROLLED_BACK, BackupDirectoryTransaction(targets, root, {}).recoverOnColdStart())
            assertEquals("Bob", File(targets.getValue("datastore"), "profile_settings.preferences_pb").readText())
            assertEquals("encrypted-Bob-token", File(targets.getValue("datastore"), "debrid_settings.preferences_pb.bak").readText())
            assertEquals("encrypted-Bob-MAL", File(targets.getValue("shared_prefs"), "syclop_mal_account.xml").readText())
            assertEquals(BackupDirectoryTransaction.Outcome.NONE, BackupDirectoryTransaction(targets, root, {}).recoverOnColdStart())
        } }
    }
    @Test fun `oversized original state is rejected before a reversible restore is advertised`() = fixture { _, targets, root, candidates ->
        RandomAccessFile(File(targets.getValue("datastore"), "large.preferences_pb"), "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        val transaction = BackupDirectoryTransaction(targets, root, {})
        assertTrue(runCatching { transaction.commit(candidates) }.isFailure)
        assertEquals("Bob", File(targets.getValue("datastore"), "profile_settings.preferences_pb").readText())
        assertFalse(transaction.canUndo())
        assertEquals(BackupDirectoryTransaction.Outcome.NONE, transaction.recoverOnColdStart())
    }
    @Test fun `too many original entries are rejected before swapping any directory`() = fixture { _, targets, root, candidates ->
        repeat(1025) { File(targets.getValue("shared_prefs"), "test$it.xml").writeText("x") }
        val transaction = BackupDirectoryTransaction(targets, root, {})
        assertTrue(runCatching { transaction.commit(candidates) }.isFailure)
        assertEquals("Bob", File(targets.getValue("datastore"), "profile_settings.preferences_pb").readText())
        assertFalse(transaction.canUndo())
    }
    @Test fun `orphan preparation is pruned while the last Undo snapshot is retained`() = fixture { _, targets, root, candidates ->
        val transaction = BackupDirectoryTransaction(targets, root, {})
        transaction.commit(candidates)
        val orphan = File(root, "transactions/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        File(orphan, "new").mkdir()
        assertEquals(BackupDirectoryTransaction.Outcome.NONE, transaction.recoverOnColdStart())
        assertFalse(orphan.exists())
        assertTrue(transaction.canUndo())
    }
    @Test fun `nested and symlink candidate files cannot enter app stores`() = fixture { base, targets, root, candidates ->
        val source = candidates.getValue("datastore")!!
        File(source, "nested").mkdir()
        assertTrue(runCatching { BackupDirectoryTransaction(targets, root, {}).commit(candidates) }.isFailure)
        File(source, "nested").delete()
        val outside = File(base, "outside").apply { writeText("private") }
        Files.createSymbolicLink(File(source, "evil.preferences_pb").toPath(), outside.toPath())
        assertTrue(runCatching { BackupDirectoryTransaction(targets, root, {}).commit(candidates) }.isFailure)
        assertEquals("Bob", File(targets.getValue("datastore"), "profile_settings.preferences_pb").readText())
    }
}
