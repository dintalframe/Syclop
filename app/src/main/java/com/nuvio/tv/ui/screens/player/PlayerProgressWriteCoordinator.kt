package com.nuvio.tv.ui.screens.player

/** Identifies captured progress; cross-catalog aliases are not guessed from an episode number. */
internal data class PlayerProgressWriteOwner(
    val profileId: Int,
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?
)

/** One controller's visits, backed by an app-lifetime authority shared with later controllers. */
internal class PlayerProgressWriteCoordinator(private val authority: PlayerProgressWriteAuthority) {
    private val controllerId = authority.newControllerId()
    private var foreground: PlayerProgressWriteAuthority.Foreground? = null
    private var preparedOwner: PlayerProgressWriteOwner? = null
    private var lastClaimedOwner: PlayerProgressWriteOwner? = null
    private val leases = mutableMapOf<PlayerProgressWriteOwner, PlayerProgressWriteAuthority.Request>()

    /** Only PlayerScreen's ON_RESUME calls this; preparation and callbacks cannot activate. */
    @Synchronized
    fun activateForeground(owner: PlayerProgressWriteOwner?) {
        foreground = authority.activateForeground(controllerId)
        // Initial preparation can precede ON_RESUME. A retained player can resume without
        // preparing again; both cases must claim using this fresh, explicit lifecycle signal.
        if (preparedOwner != null) onPlaybackOwnerChanged(owner ?: preparedOwner!!)
    }

    @Synchronized
    fun deactivateForeground() {
        foreground?.let(authority::deactivateForeground)
        foreground = null
    }

    @Synchronized
    fun onPlaybackOwnerChanged(owner: PlayerProgressWriteOwner): Boolean {
        preparedOwner = owner
        return claim(owner, newVisit = lastClaimedOwner != owner)
    }

    @Synchronized
    fun capture(owner: PlayerProgressWriteOwner, completedAtCapture: Boolean): PlayerProgressWriteAuthority.Request? {
        val lease = leases[owner] ?: return null
        return authority.capture(lease, completedAtCapture)
    }

    @Synchronized
    fun invalidate(owner: PlayerProgressWriteOwner): Boolean {
        preparedOwner = owner
        return claim(owner, newVisit = true)
    }

    private fun claim(owner: PlayerProgressWriteOwner, newVisit: Boolean): Boolean {
        val activation = foreground ?: return false
        val lease = authority.claim(activation, owner, newVisit) ?: return false
        lastClaimedOwner = owner
        leases[owner] = lease
        return true
    }

    @Synchronized
    fun mayPublish(request: PlayerProgressWriteAuthority.Request): Boolean = authority.mayPublish(foreground, request)

    suspend fun writeIfCurrent(request: PlayerProgressWriteAuthority.Request, completesVideo: Boolean, write: suspend () -> Unit) =
        authority.writeIfCurrent(request, completesVideo, write)
}
