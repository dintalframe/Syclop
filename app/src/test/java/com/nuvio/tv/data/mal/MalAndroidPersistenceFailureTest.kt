package com.nuvio.tv.data.mal

import android.content.SharedPreferences
import io.mockk.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MalAndroidPersistenceFailureTest {
    @Test fun `false commit cannot expose the unpersisted SharedPreferences memory value`() {
        val h = Fixture(); h.commit = false
        assertThrows(IOException::class.java) { h.persistence.write(mapOf("account.p1" to "new-test-token")) }
        assertEquals("encrypted:new-test-token", h.memory["account.p1"]) // Matches Android commit-to-memory-before-disk behavior.
        assertFalse(h.persistence.available)
        assertThrows(IOException::class.java) { h.persistence.read("account.p1") }
        assertThrows(IOException::class.java) { h.persistence.write(mapOf("account.p2" to "another-test-token")) }
        verify(exactly = 1) { h.editor.commit() }
    }
    @Test fun `throwing commit also fails closed without leaking persistence details`() {
        val h = Fixture(); every { h.editor.commit() } throws IllegalStateException("private-test-detail")
        val failure = assertThrows(IOException::class.java) { h.persistence.write(mapOf("account.p1" to "test-token")) }
        assertFalse(h.persistence.available); assertNull(failure.cause)
        assertFalse(failure.toString().contains("private-test-detail"))
    }
    @Test fun `encryption failure changes no preference and does not poison the intact store`() {
        val h = Fixture(); every { h.cipher.encrypt(any()) } throws IOException("synthetic cipher failure")
        assertThrows(IOException::class.java) { h.persistence.write(mapOf("account.p1" to "test-token")) }
        assertTrue(h.persistence.available); assertEquals("old-test-token", h.persistence.read("account.p1"))
        verify(exactly = 0) { h.preferences.edit() }
    }
    @Test fun `successful encrypted commit remains readable with unchanged payload contract`() {
        val h = Fixture(); h.persistence.write(mapOf("account.p1" to "new-test-token"))
        assertTrue(h.persistence.available); assertEquals("new-test-token", h.persistence.read("account.p1"))
        assertEquals("encrypted:new-test-token", h.memory["account.p1"])
    }
    @Test fun `failed clear cannot be read as successful durable account deletion`() {
        val h = Fixture(); h.commit = false
        assertThrows(IOException::class.java) { h.persistence.clear() }
        assertFalse(h.persistence.available)
        assertThrows(IOException::class.java) { h.persistence.read("account.p1") }
    }
    @Test fun `failed deletion disables access to the old in-memory credential`() = kotlinx.coroutines.test.runTest {
        val memory = MemoryMalAccountPersistence()
        var usable = true
        var fail = false
        val persistence = object : MalAccountPersistence by memory {
            override val available get() = usable
            override fun write(values: Map<String, String?>) {
                if (fail) { usable = false; throw IOException("synthetic storage failure") }
                memory.write(values)
            }
        }
        val store = MalAuthStore(persistence, kotlinx.coroutines.flow.MutableStateFlow(1), backgroundScope,
            malTestConfiguration, diagnose = { _, _ -> })
        val scope = store.state.value.scope
        assertTrue(store.complete(scope, malTestTokens, "Viewer"))
        val completedScope = store.state.value.scope
        fail = true
        assertThrows(IOException::class.java) { store.signOut(completedScope) }
        assertTrue(store.isCurrent(completedScope)); assertFalse(store.isCurrent(scope))
        assertFalse(store.storageAvailable); assertNull(store.credentials(completedScope))
    }
    private class Fixture {
        val memory = mutableMapOf("account.p1" to "encrypted:old-test-token")
        val preferences = mockk<SharedPreferences>(); val editor = mockk<SharedPreferences.Editor>(); val cipher = mockk<MalAccountCipher>()
        var commit = true
        val persistence = AndroidMalAccountPersistence(preferences, cipher)
        init {
            every { cipher.encrypt(any()) } answers { "encrypted:" + firstArg<String>() }
            every { cipher.decrypt(any()) } answers { firstArg<String>().removePrefix("encrypted:") }
            every { preferences.getString(any(), any()) } answers { memory[firstArg<String>()] }
            every { preferences.edit() } returns editor
            every { editor.putString(any(), any()) } answers { memory[firstArg<String>()] = secondArg<String>(); editor }
            every { editor.remove(any()) } answers { memory.remove(firstArg<String>()); editor }
            every { editor.clear() } answers { memory.clear(); editor }
            every { editor.commit() } answers { commit }
        }
    }
}
