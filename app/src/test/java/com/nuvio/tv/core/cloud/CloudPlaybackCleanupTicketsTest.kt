package com.nuvio.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPlaybackCleanupTicketsTest {
    private val scope = CloudPlaybackCleanupScope(1, 2, 3)
    private val file = CloudLibraryFile(id = "file-1", name = "sample.mkv")
    private val item = CloudLibraryItem("realdebrid", "Real-Debrid", "item-1", CloudLibraryItemType.Torrent, file.name, files = listOf(file))
    private val ticket = CloudPlaybackCleanupTicket("cloud-session", "digest", item, file, scope)
    private val tickets = CloudPlaybackCleanupTickets()

    @Test
    fun exactSessionAndPlaybackAreRequired() {
        tickets.remember(ticket)

        assertNull(tickets.take(null, ticket.playbackDigest))
        assertNull(tickets.take(ticket.cloudSessionToken, "another-stream"))
        assertEquals(ticket, tickets.take(ticket.cloudSessionToken, ticket.playbackDigest))
        assertNull(tickets.take(ticket.cloudSessionToken, ticket.playbackDigest))
    }

    @Test
    fun reusedPlaybackUrlCannotChooseBetweenTwoDifferentCloudItems() {
        val direct = ticket.copy(cloudSessionToken = null)
        val otherItem = direct.copy(item = item.copy(id = "item-2"))
        tickets.remember(direct)
        tickets.remember(otherItem)
        assertNull(tickets.take(null, direct.playbackDigest))

        tickets.remember(otherItem)
        assertNull(tickets.take(null, otherItem.playbackDigest))
    }

    @Test
    fun successfulDeletionConsumesAllTicketsForTheItemInThisAccountScope() {
        val sameItem = ticket.copy(cloudSessionToken = null, playbackDigest = "another-stream")
        val otherScope = sameItem.copy(scope = scope.copy(accountRevision = 4), playbackDigest = "other-account")
        tickets.remember(ticket)
        tickets.remember(sameItem)
        tickets.remember(otherScope)

        assertEquals(ticket, tickets.take(ticket.cloudSessionToken, ticket.playbackDigest))
        tickets.remember(sameItem)
        assertNull(tickets.take(null, sameItem.playbackDigest))
        tickets.finish(ticket, deleted = true)
        tickets.remember(sameItem)

        assertTrue(tickets.wasDeleted(scope, item))
        assertFalse(tickets.wasDeleted(otherScope.scope, item))
        assertNull(tickets.take(null, sameItem.playbackDigest))
        assertEquals(otherScope, tickets.take(null, otherScope.playbackDigest))
    }

    @Test
    fun failedDeletionDoesNotHideTheItemAndAFreshPlaybackCanGetANewTicket() {
        tickets.remember(ticket)
        assertEquals(ticket, tickets.take(ticket.cloudSessionToken, ticket.playbackDigest))
        tickets.finish(ticket, deleted = false)

        assertFalse(tickets.wasDeleted(scope, item))
        assertNull(tickets.take(ticket.cloudSessionToken, ticket.playbackDigest))
        tickets.remember(ticket)
        assertEquals(ticket, tickets.take(ticket.cloudSessionToken, ticket.playbackDigest))
    }
}
