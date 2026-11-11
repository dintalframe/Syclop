package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.backup.BackupProfileLockPolicy
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProfilePinTest {
    private val prefs = TestPreferencesStore()
    private var now = 10_000L
    private fun store() = ProfileLockStateDataStore(prefs, { id, pin ->
        MessageDigest.getInstance("SHA-256").digest("fixture:$id:$pin".toByteArray()).decodeToString()
    }, { now })

    @Test fun `local lock persists and verifies only the correct profile and PIN`() = runTest {
        assertTrue(store().setLocalPin(2, "1947", null))
        val reopened = store()
        assertEquals(mapOf(2 to true), reopened.pinEnabled.first())
        assertFalse(reopened.verifyLocalPin(2, "1948").unlocked)
        assertFalse(reopened.verifyLocalPin(1, "1947").unlocked)
        assertTrue(reopened.verifyLocalPin(2, "١٩٤٧").unlocked)
        assertFalse(prefs.value.asMap().values.any { it.toString().contains("1947") })
        assertThrows(IllegalArgumentException::class.java) {
            BackupProfileLockPolicy.requireUnlocked(prefs.value[stringPreferencesKey("pin_enabled_map")])
        }
    }

    @Test fun `failed attempts update atomically and throttle survives reopening`() = runTest {
        val first = store()
        assertTrue(first.setLocalPin(2, "8264", null))
        assertTrue(first.setLocalPin(3, "1234", null))
        val attempts = (1..5).map { async { first.verifyLocalPin(2, "9999") } }.awaitAll()
        assertTrue(attempts.none { it.unlocked })
        assertEquals(1, attempts.count { it.retryAfterSeconds == 30 })
        val reopened = store()
        assertEquals(30, reopened.verifyLocalPin(2, "8264").retryAfterSeconds)
        assertTrue(reopened.verifyLocalPin(3, "1234").unlocked)
        now += 29_001L
        assertEquals(1, reopened.verifyLocalPin(2, "8264").retryAfterSeconds)
        now += 999L
        assertTrue(reopened.verifyLocalPin(2, "8264").unlocked)
        assertEquals(0, reopened.verifyLocalPin(2, "9999").retryAfterSeconds)
    }

    @Test fun `changing removing and reusing a profile cannot inherit or bypass a PIN`() = runTest {
        val store = store()
        assertTrue(store.setLocalPin(2, "0000", null))
        assertFalse(store.setLocalPin(2, "2222", null))
        assertFalse(store.clearLocalPin(2, "2222"))
        assertTrue(store.setLocalPin(2, "2222", "0000"))
        assertFalse(store.verifyLocalPin(2, "0000").unlocked)
        assertTrue(store.clearLocalPin(2, "2222"))
        assertEquals(emptyMap<Int, Boolean>(), store.pinEnabled.first())
        assertTrue(store.setLocalPin(2, "3333", null))
        store.removeProfile(2)
        assertEquals(emptyMap<Int, Boolean>(), store.pinEnabled.first())
        assertFalse(store.verifyLocalPin(2, "3333").unlocked)
        assertTrue(store.setLocalPin(2, "4444", null))
        assertFalse(store.verifyLocalPin(2, "3333").unlocked)
    }

    @Test fun `a legacy enabled row with no device verifier stays locked`() = runTest {
        val store = store()
        store.setPinEnabled(2, true)
        assertFalse(store.verifyLocalPin(2, "0000").unlocked)
        assertFalse(store.setLocalPin(2, "0000", null))
        assertFalse(store.clearLocalPin(2, "0000"))
        assertEquals(mapOf(2 to true), store.pinEnabled.first())
    }
}
