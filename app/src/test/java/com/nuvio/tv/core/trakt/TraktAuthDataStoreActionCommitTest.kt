package com.nuvio.tv.core.trakt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TraktAuthDataStoreActionCommitTest {
    @Test fun `successful refresh replaces only the captured profile with same grant revision`() = runTest {
        val h = Fixture(); h.signIn()
        val expected = h.auth.getCurrentState(1); val revision = h.auth.grantRevision(1)
        assertTrue(h.auth.saveRefreshedTokenIfCurrent(h.token("new"), expected, revision) { true })
        assertEquals("new-access", h.auth.getCurrentState(1).accessToken)
        assertEquals(revision, h.auth.grantRevision(1))
        assertFalse(h.auth.getCurrentState(2).isAuthenticated)
    }
    @Test fun `profile change at DataStore suspension rejects refresh commit`() = runTest {
        val h = Fixture(); h.signIn()
        val expected = h.auth.getCurrentState(1); val revision = h.auth.grantRevision(1)
        var current = true; h.one.beforeUpdate = { current = false }
        assertFalse(h.auth.saveRefreshedTokenIfCurrent(h.token("new"), expected, revision) { current })
        assertEquals("old-access", h.auth.getCurrentState(1).accessToken)
    }
    @Test fun `new login cannot be overwritten by previous grant refresh`() = runTest {
        val h = Fixture(); h.signIn(); val expected = h.auth.getCurrentState(1); val revision = h.auth.grantRevision(1)
        h.auth.saveToken(h.token("other"), 1, "test-public-client")
        assertFalse(h.auth.saveRefreshedTokenIfCurrent(h.token("late"), expected, revision) { true })
        assertEquals("other-access", h.auth.getCurrentState(1).accessToken)
    }
    @Test fun `pending logout immediately makes grant revision unavailable until durable clear`() = runTest {
        val h = Fixture(); h.signIn(); val before = h.auth.grantRevision(1)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.one.beforeUpdate = { entered.complete(Unit); release.await() }
        val clear = launch { h.auth.clearAuthForProfile(1) }; entered.await()
        assertEquals(-1L, h.auth.grantRevision(1))
        release.complete(Unit); clear.join()
        assertTrue(h.auth.grantRevision(1) > before)
        assertFalse(h.auth.getCurrentState(1).isAuthenticated)
    }
    @Test fun `cancellation in transaction cannot publish replacement tokens`() = runTest {
        val h = Fixture(); h.signIn(); val expected = h.auth.getCurrentState(1); val revision = h.auth.grantRevision(1)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val thisJob = coroutineContext.job
            h.one.beforeUpdate = { thisJob.cancel() }
            h.auth.saveRefreshedTokenIfCurrent(h.token("late"), expected, revision) { true }
        }
        job.join(); h.one.beforeUpdate = null
        assertTrue(job.isCancelled); assertEquals("old-access", h.auth.getCurrentState(1).accessToken)
    }
    private class Fixture {
        val one = MemoryStore(); val two = MemoryStore()
        private val active = MutableStateFlow(1)
        private val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, any()) } returns one; every { get(2, any()) } returns two
        }
        private val cipher = mockk<DebridCredentialCipher> {
            every { encrypt(any(), any(), any()) } answers { "enc:" + firstArg<String>() }
            every { decrypt(any(), any(), any()) } answers { firstArg<String>().removePrefix("enc:") }
            every { isEncrypted(any()) } answers { firstArg<String>().startsWith("enc:") }
        }
        val auth = TraktAuthDataStore(factory, profiles, cipher)
        suspend fun signIn() { auth.saveToken(token("old"),1,"test-public-client") }
        fun token(prefix: String) = TraktTokenResponseDto("$prefix-access","Bearer",604800,"$prefix-refresh",createdAt=1000)
    }
    private class MemoryStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val values = MutableStateFlow(emptyPreferences())
        var beforeUpdate: (suspend () -> Unit)? = null
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            beforeUpdate?.invoke()
            transform(values.value).also { values.value = it }
        }
    }
}
