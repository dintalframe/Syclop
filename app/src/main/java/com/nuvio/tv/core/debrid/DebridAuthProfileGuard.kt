package com.nuvio.tv.core.debrid

/** Invalidates a pending account operation even when a viewer switches away and back. */
class DebridAuthProfileGuard(initialProfileId: Int, private val selectionRevision: () -> Long = { 0L }) {
    data class Ticket(val profileId: Int, val revision: Long, val selectionRevision: Long = 0L)
    private var profileId = initialProfileId
    private var revision = 0L

    @Synchronized fun observe(activeProfileId: Int) {
        if (activeProfileId != profileId) { profileId = activeProfileId; revision += 1 }
    }

    @Synchronized fun invalidate() { revision += 1L }

    @Synchronized fun capture(activeProfileId: Int): Ticket {
        observe(activeProfileId)
        return Ticket(profileId, revision, selectionRevision())
    }

    @Synchronized fun valid(ticket: Ticket, activeProfileId: Int): Boolean {
        observe(activeProfileId)
        return ticket.profileId == profileId && ticket.revision == revision && ticket.selectionRevision == selectionRevision()
    }
}
