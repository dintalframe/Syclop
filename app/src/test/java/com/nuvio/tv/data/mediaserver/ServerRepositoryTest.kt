package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {
    private val persistence = MemoryServerPersistence()
    private val repository = ServerRepository(
        persistence = persistence,
        providers = emptyList(),
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun connection(id: String, ref: String) = ServerConnection(
        id = id,
        providerId = "jellyfin",
        name = "Home",
        address = "http://192.168.1.10:8096",
        remoteServerId = "s1",
        remoteUserId = "u1",
        userName = "viewer",
        credentialRef = ref,
        localHttpApproved = true
    )

    @Test
    fun oldStoredLocalConnectionsRequireApprovalAfterUpgrade() {
        repository.store(connection("c1", "k1").copy(localHttpApproved = false), "token-one")
        persistence.values[1] = persistence.values.getValue(1).replace(",\"localHttpApproved\":false", "")
        val upgraded = ServerRepository(persistence, emptyList(), CoroutineScope(Dispatchers.Unconfined))
        assertNull(upgraded.session("c1"))
        upgraded.approveLocalHttp("c1", "http://192.168.1.10:8096", "k1")
        assertEquals("token-one", upgraded.session("c1")?.token)
    }

    @Test
    fun legacyLocalHttpCredentialsStayStoredUntilTheirConnectionIsApproved() {
        val legacy = connection("c1", "k1").copy(localHttpApproved = false)
        repository.store(legacy, "token-one")
        assertNull(repository.session("c1"))
        assertEquals(ServerFailure.INSECURE_CONNECTION, repository.uiState.value.failures["c1"])
        repository.approveLocalHttp("c1", "http://other.local", "k1")
        assertNull(repository.session("c1"))
        repository.approveLocalHttp("c1", legacy.address, "stale-credential")
        assertNull(repository.session("c1"))
        repository.approveLocalHttp("c1", legacy.address, "k1")
        assertEquals("token-one", repository.session("c1")?.token)
    }

    @Test
    fun privateDnsHomelabConnectionsKeepTheirTokenWhenApproved() {
        val saved = connection("c1", "k1").copy(address = "http://jellyfin.home.arpa:8096", localHttpApproved = false)
        repository.store(saved, "token-one")
        assertNull(repository.session("c1"))
        repository.approveLocalHttp("c1", saved.address, "k1")
        assertEquals("token-one", repository.session("c1")?.token)
    }

    @Test
    fun approvingPublicHttpNeverReleasesItsSavedToken() {
        val public = connection("c1", "k1").copy(address = "http://8.8.8.8", localHttpApproved = false)
        repository.store(public, "token-one")
        repository.approveLocalHttp("c1", public.address, "k1")
        assertNull(repository.session("c1"))
    }

    @Test
    fun keepsConnectionsAndTokensPerProfile() {
        repository.store(connection("c1", "k1"), "token-one")
        assertEquals("token-one", repository.session("c1")?.token)

        repository.selectProfile(2)
        assertNull(repository.connection("c1"))
        repository.store(connection("c2", "k2"), "token-two")

        repository.selectProfile(1)
        assertEquals(listOf("c1"), repository.uiState.value.connections.map { it.id })
        assertEquals("token-one", repository.session("c1")?.token)
        assertFalse(persistence.values.getValue(1).contains("token-two"))
    }

    @Test
    fun removingAConnectionDropsItsToken() {
        repository.store(connection("c1", "k1"), "token-one")
        repository.remove("c1")

        assertNull(repository.session("c1"))
        assertFalse(persistence.values.getValue(1).contains("token-one"))
    }

    @Test
    fun replacingACredentialKeepsOnlyTheNewToken() {
        repository.store(connection("c1", "k1"), "old")
        repository.store(connection("c1", "k2"), "new", replacedCredential = "k1")

        assertEquals("new", repository.session("c1")?.token)
        assertFalse(persistence.values.getValue(1).contains("old"))
    }

    @Test
    fun profileRemovalAndAccountResetClearStoredServers() {
        repository.store(connection("c1", "k1"), "token-one")
        repository.selectProfile(2)
        repository.store(connection("c2", "k2"), "token-two")

        repository.removeProfile(1)
        assertFalse(persistence.values.containsKey(1))
        assertTrue(persistence.values.containsKey(2))

        repository.clearAllProfiles()
        assertTrue(persistence.values.isEmpty())
        assertTrue(repository.uiState.value.connections.isEmpty())
    }

    @Test
    fun disabledServersHaveNoSession() {
        repository.store(connection("c1", "k1"), "token-one")
        repository.setEnabled("c1", false)

        assertNull(repository.session("c1"))
        assertTrue(repository.enabledConnections().isEmpty())
    }

    @Test
    fun callsReportMissingAndDisabledServers() = runTest {
        repository.store(connection("c1", "k1"), "token-one")
        repository.setEnabled("c1", false)

        val missing = runCatching { repository.call("nope") { _, _ -> 1 } }.exceptionOrNull() as ServerException
        val disabled = runCatching { repository.call("c1") { _, _ -> 1 } }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.NOT_FOUND, missing.failure)
        assertEquals(ServerFailure.UNREACHABLE, disabled.failure)
    }
}
