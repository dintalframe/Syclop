package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.MemoryPlaybackProgressLeaseCheckpoint
import com.nuvio.tv.core.player.PlaybackProgressLeaseCheckpoint
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerProgressWriteAuthorityExternalTest {
    private val owner = PlayerProgressWriteOwner(1, "tt-fixture", "movie", "tt-fixture", null, null)

    @Test fun `already entered external completion commits before a newer internal rewatch`() = runTest {
        val authority = PlayerProgressWriteAuthority()
        val external = authority.acceptExternal(authority.prepareExternal(owner))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val old = launch {
            authority.writeIfCurrent(external, true) {
                entered.complete(Unit)
                release.await()
                committed += "external-completion"
            }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        val (internal, lease) = internalLease(authority, owner)
        val newer = launch { internal.writeIfCurrent(lease, false) { committed += "internal-rewatch" } }
        runCurrent()
        try { assertTrue(committed.isEmpty()) } finally { release.complete(Unit) }
        old.join(); newer.join()
        assertEquals(listOf("external-completion", "internal-rewatch"), committed)
    }

    @Test fun `queued external completion cannot replace a newer internal session`() = runTest {
        val authority = PlayerProgressWriteAuthority()
        val external = authority.acceptExternal(authority.prepareExternal(owner))
        val (internal, lease) = internalLease(authority, owner)
        val committed = mutableListOf<String>()
        internal.writeIfCurrent(lease, false) { committed += "internal" }
        authority.writeIfCurrent(external, true) { committed += "external" }
        assertEquals(listOf("internal"), committed)
    }

    @Test fun `outgoing external video and profile saves remain independent`() = runTest {
        for (other in listOf(owner.copy(videoId = "another-video"), owner.copy(profileId = 2))) {
            val authority = PlayerProgressWriteAuthority()
            val external = authority.acceptExternal(authority.prepareExternal(owner))
            val (internal, lease) = internalLease(authority, other)
            val committed = mutableListOf<String>()
            internal.writeIfCurrent(lease, false) { committed += "other" }
            authority.writeIfCurrent(external, true) { committed += "external" }
            assertEquals(listOf("other", "external"), committed)
        }
    }

    @Test fun `unaccepted external reservation leaves the internal player authoritative`() = runTest {
        val authority = PlayerProgressWriteAuthority()
        val (internal, lease) = internalLease(authority, owner)
        val prepared = authority.prepareExternal(owner)
        assertTrue(authority.isCurrent(lease))
        var committed = false
        internal.writeIfCurrent(lease, false) { committed = true }
        assertTrue(committed)
        authority.acceptExternal(prepared)
        assertFalse(authority.isCurrent(lease))
    }

    @Test fun `mere lifecycle return from external playback does not reclaim internal ownership`() = runTest {
        val authority = PlayerProgressWriteAuthority()
        val (internal, old) = internalLease(authority, owner)
        val external = authority.acceptExternal(authority.prepareExternal(owner))
        internal.suspendForExternalHandoff()
        internal.activateForeground(owner)
        assertTrue(authority.isCurrent(external))
        assertNull(internal.capture(owner, false))
        assertFalse(authority.isCurrent(old))
        assertTrue(internal.onPlaybackOwnerChanged(owner))
        assertFalse(authority.isCurrent(external))
        assertNotNull(internal.capture(owner, false))
    }

    @Test fun `process recovery accepts the exact persisted external nonce`() = runTest {
        val checkpoint = MemoryPlaybackProgressLeaseCheckpoint()
        val first = PlayerProgressWriteAuthority(checkpoint)
        val handoff = first.prepareExternal(owner)
        first.checkpointIfCurrent(first.acceptExternal(handoff))
        val recovered = PlayerProgressWriteAuthority(checkpoint)
        val lease = recovered.recoverExternal(handoff)
        assertNotNull(lease)
        var committed = false
        recovered.writeIfCurrent(lease!!, false) { committed = true }
        assertTrue(committed)
    }

    @Test fun `accepted handoff recovers from unchanged prepared baseline before nonce checkpoint`() = runTest {
        val checkpoint = MemoryPlaybackProgressLeaseCheckpoint()
        val first = PlayerProgressWriteAuthority(checkpoint)
        val handoff = first.prepareExternal(owner)
        first.acceptExternal(handoff)
        val recovered = PlayerProgressWriteAuthority(checkpoint)
        assertNotNull(recovered.recoverExternal(handoff))
    }

    @Test fun `process recovery rejects old external metadata after a durable internal rewatch`() = runTest {
        val checkpoint = MemoryPlaybackProgressLeaseCheckpoint()
        val first = PlayerProgressWriteAuthority(checkpoint)
        val handoff = first.prepareExternal(owner)
        first.checkpointIfCurrent(first.acceptExternal(handoff))
        val (internal, lease) = internalLease(first, owner)
        internal.writeIfCurrent(lease, false) { }
        assertNull(PlayerProgressWriteAuthority(checkpoint).recoverExternal(handoff))
    }

    @Test fun `recovery cannot reclaim over a newer in process owner even before its first save`() = runTest {
        val checkpoint = MemoryPlaybackProgressLeaseCheckpoint()
        val first = PlayerProgressWriteAuthority(checkpoint)
        val handoff = first.prepareExternal(owner)
        first.checkpointIfCurrent(first.acceptExternal(handoff))
        val recovered = PlayerProgressWriteAuthority(checkpoint)
        internalLease(recovered, owner)
        assertNull(recovered.recoverExternal(handoff))
    }

    @Test fun `old suspended checkpoint cannot land after the newer internal checkpoint`() = runTest {
        val checkpoint = SuspendedCheckpoint()
        val authority = PlayerProgressWriteAuthority(checkpoint)
        val external = authority.acceptExternal(authority.prepareExternal(owner))
        checkpoint.blockedNonce = external.nonce
        val committed = mutableListOf<String>()
        val old = launch { authority.writeIfCurrent(external, false) { committed += "old" } }
        runCurrent()
        assertTrue(checkpoint.entered.isCompleted)
        val (internal, lease) = internalLease(authority, owner)
        val newer = launch { internal.writeIfCurrent(lease, false) { committed += "new" } }
        runCurrent()
        try { assertTrue(committed.isEmpty()) } finally { checkpoint.release.complete(Unit) }
        old.join(); newer.join()
        assertEquals(listOf("new"), committed)
        assertEquals(lease.nonce, checkpoint.values.values.single())
    }

    @Test fun `failed checkpoint cannot permit a repository mutation`() = runTest {
        val checkpoint = object : PlaybackProgressLeaseCheckpoint {
            override suspend fun read(ownerKey: String): String? = "baseline"
            override suspend fun write(ownerKey: String, nonce: String) { throw IOException("fixture") }
        }
        val authority = PlayerProgressWriteAuthority(checkpoint)
        val (internal, lease) = internalLease(authority, owner)
        var committed = false
        try {
            internal.writeIfCurrent(lease, false) { committed = true }
            fail("checkpoint failure must propagate")
        } catch (_: IOException) { }
        assertFalse(committed)
    }

    private fun internalLease(authority: PlayerProgressWriteAuthority, owner: PlayerProgressWriteOwner): Pair<PlayerProgressWriteCoordinator, PlayerProgressWriteAuthority.Request> {
        val coordinator = PlayerProgressWriteCoordinator(authority)
        coordinator.activateForeground(owner)
        assertTrue(coordinator.onPlaybackOwnerChanged(owner))
        return coordinator to checkNotNull(coordinator.capture(owner, false))
    }

    private class SuspendedCheckpoint : PlaybackProgressLeaseCheckpoint {
        val values = mutableMapOf<String, String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockedNonce: String? = null
        override suspend fun read(ownerKey: String): String? = values[ownerKey]
        override suspend fun write(ownerKey: String, nonce: String) {
            if (nonce == blockedNonce) { entered.complete(Unit); release.await() }
            values[ownerKey] = nonce
        }
    }
}
