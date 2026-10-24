package com.nuvio.tv.core.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BackupRestoreQueueTest {
    private fun fixture(block: (File, BackupRestoreQueue) -> Unit) {
        val root = Files.createTempDirectory("syclop-queue").toFile()
        try { block(root, BackupRestoreQueue(root, {})) } finally { root.deleteRecursively() }
    }
    @Test fun `corrupted queued archive is rejected and next start opens original state`() = fixture { root, queue ->
        File(root, "original-account").writeText("encrypted original token")
        File(root, "pending.json").writeText("{\"action\":\"restore\"}")
        File(root, "pending.zip").writeText("truncated")
        assertEquals(BackupRestoreQueue.Request.Rejected, queue.readOrReject())
        assertEquals(BackupRestoreQueue.Request.None, queue.readOrReject())
        assertEquals("encrypted original token", File(root, "original-account").readText())
    }
    @Test fun `oversized and unknown queued actions are rejected without boot loop`() = fixture { root, queue ->
        File(root, "pending.json").writeText("x".repeat(2048))
        assertEquals(BackupRestoreQueue.Request.Rejected, queue.readOrReject())
        File(root, "pending.json").writeText("{\"action\":\"erase\"}")
        assertEquals(BackupRestoreQueue.Request.Rejected, queue.readOrReject())
        assertFalse(queue.isPending())
    }
    @Test fun `cancelling queued Undo changes no current stores`() = fixture { root, queue ->
        File(root, "original-account").writeText("encrypted token")
        queue.queue(null)
        assertEquals(BackupRestoreQueue.Request.Undo, queue.readOrReject())
        queue.cancel()
        assertEquals(BackupRestoreQueue.Request.None, queue.readOrReject())
        assertEquals("encrypted token", File(root, "original-account").readText())
    }
}
