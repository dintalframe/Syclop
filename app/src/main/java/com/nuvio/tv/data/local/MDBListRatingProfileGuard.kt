package com.nuvio.tv.data.local

data class MDBListRatingCredentialScope(val profileId: Int, val revision: Long)

/** A pending validation is invalid even after an observed A → B → A profile transition. */
class MDBListRatingProfileGuard(initialProfileId: Int) {
    private var profileId = initialProfileId
    private var revision = 0L
    @Synchronized fun observe(activeProfileId: Int) {
        if (activeProfileId != profileId) { profileId = activeProfileId; revision += 1 }
    }
    @Synchronized fun capture(activeProfileId: Int): MDBListRatingCredentialScope {
        observe(activeProfileId)
        return MDBListRatingCredentialScope(profileId, revision)
    }
    @Synchronized fun valid(scope: MDBListRatingCredentialScope, activeProfileId: Int): Boolean {
        observe(activeProfileId)
        return scope.profileId == profileId && scope.revision == revision
    }
}
