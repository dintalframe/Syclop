package com.nuvio.tv.core.backup

import org.junit.Assert.*
import org.junit.Test

class BackupProfileLockPolicyTest {
    @Test fun `a locked nonactive profile prevents an all-profile export`() {
        var historyRead = false
        val outcome = runCatching {
            BackupProfileLockPolicy.requireUnlocked("1:false,2:true,3:false")
            historyRead = true
        }
        assertTrue(outcome.isFailure)
        assertFalse(historyRead)
    }
    @Test fun `unlocked profiles and installations without lock settings allow backup`() {
        for (value in listOf(null, "", "1:false", "1:false,2:false,6:false")) {
            BackupProfileLockPolicy.requireUnlocked(value)
        }
    }
    @Test fun `malformed duplicate or out-of-range lock records fail closed`() {
        for (value in listOf("1", "1:FALSE", "1:false,1:false", "7:false", "0:false", "1:unknown", "1:false:true")) {
            assertTrue(value, runCatching { BackupProfileLockPolicy.requireUnlocked(value) }.isFailure)
        }
    }
}
