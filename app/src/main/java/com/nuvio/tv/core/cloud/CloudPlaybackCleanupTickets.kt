package com.nuvio.tv.core.cloud

internal data class CloudPlaybackCleanupScope(
    val profileId: Int,
    val selectionRevision: Long,
    val accountRevision: Long
)

internal data class CloudPlaybackCleanupTicket(
    val cloudSessionToken: String?,
    val playbackDigest: String,
    val item: CloudLibraryItem,
    val file: CloudLibraryFile,
    val scope: CloudPlaybackCleanupScope
)

/** Cleanup authority is process-local and is never restored with saved playback state. */
internal class CloudPlaybackCleanupTickets {
    // A null entry marks a playback URL that could identify more than one download.
    private val tickets = LinkedHashMap<Pair<String?, String>, CloudPlaybackCleanupTicket?>()
    private val inFlight = mutableSetOf<Pair<CloudPlaybackCleanupScope, String>>()
    private val deletedItems = linkedSetOf<Pair<CloudPlaybackCleanupScope, String>>()

    @Synchronized
    fun remember(ticket: CloudPlaybackCleanupTicket) {
        val itemKey = ticket.scope to ticket.item.stableKey
        if (itemKey in inFlight || itemKey in deletedItems) return
        val playbackKey = ticket.cloudSessionToken to ticket.playbackDigest
        if (tickets.containsKey(playbackKey)) {
            val previous = tickets[playbackKey] ?: return
            if (previous.scope != ticket.scope || previous.item.stableKey != ticket.item.stableKey ||
                previous.file != ticket.file
            ) tickets[playbackKey] = null
        } else {
            tickets[playbackKey] = ticket
        }
        while (tickets.size > 32) tickets.remove(tickets.keys.first())
    }

    @Synchronized
    fun take(token: String?, playbackDigest: String): CloudPlaybackCleanupTicket? {
        val playbackKey = token to playbackDigest
        val ticket = tickets[playbackKey] ?: return null
        tickets.remove(playbackKey)
        if (!inFlight.add(ticket.scope to ticket.item.stableKey)) return null
        discardItemTickets(ticket)
        return ticket
    }

    @Synchronized
    fun finish(ticket: CloudPlaybackCleanupTicket, deleted: Boolean) {
        inFlight.remove(ticket.scope to ticket.item.stableKey)
        if (deleted) {
            discardItemTickets(ticket)
            deletedItems.add(ticket.scope to ticket.item.stableKey)
            while (deletedItems.size > 64) deletedItems.remove(deletedItems.first())
        }
    }

    @Synchronized
    fun wasDeleted(scope: CloudPlaybackCleanupScope, item: CloudLibraryItem): Boolean =
        scope to item.stableKey in deletedItems

    private fun discardItemTickets(ticket: CloudPlaybackCleanupTicket) {
        tickets.values.removeAll { it != null && it.scope == ticket.scope && it.item.stableKey == ticket.item.stableKey }
    }
}
