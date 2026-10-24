package com.nuvio.tv.core.backup

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BackupMediaStorePublicationTest {
    @Test fun `successful export writes then publishes exactly once without deleting`() {
        val calls = mutableListOf<String>()
        BackupMediaStorePublication.save({ calls += "write" }, { calls += "publish"; 1 }, { calls += "remove" })
        assertEquals(listOf("write", "publish"), calls)
    }
    @Test fun `zero updated rows fails and cleans up instead of claiming a saved file`() {
        val calls = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            BackupMediaStorePublication.save({ calls += "write" }, { calls += "publish"; 0 }, { calls += "remove" })
        }
        assertEquals(listOf("write", "publish", "remove"), calls)
    }
    @Test fun `write failure cleans pending row without publishing`() {
        val original = IOException("Synthetic write failure"); var deleted = false; var published = false
        val failure = assertThrows(IOException::class.java) {
            BackupMediaStorePublication.save({ throw original }, { published = true; 1 }, { deleted = true })
        }
        assertSame(original, failure); assertTrue(deleted); assertFalse(published)
    }
    @Test fun `publication exception attempts cleanup and retains original failure`() {
        val original = IOException("Synthetic publish failure"); var deleted = false
        val failure = assertThrows(IOException::class.java) {
            BackupMediaStorePublication.save({}, { throw original }, { deleted = true })
        }
        assertSame(original, failure); assertTrue(deleted)
    }
    @Test fun `cleanup exception does not hide the publication failure`() {
        val cleanup = IOException("Synthetic cleanup failure")
        val failure = assertThrows(IOException::class.java) {
            BackupMediaStorePublication.save({}, { 0 }, { throw cleanup })
        }
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }
    @Test fun `old MediaStore appended zip names and canonical names are discoverable`() {
        assertTrue(LocalBackupFileNames.isBackup("Syclop-2026-10-24T21-35-40.902Z.syclop"))
        assertTrue(LocalBackupFileNames.isBackup("Syclop-2026-10-24T21-35-40.902Z.syclop.zip"))
        assertEquals("application/octet-stream", LocalBackupFileNames.MIME_TYPE)
        listOf("other.zip", "Other.syclop", "Syclop-name.zip", "Syclop-name.syclop.zip.exe", "Syclop-../private.syclop")
            .forEach { assertFalse(LocalBackupFileNames.isBackup(it)) }
    }
}
