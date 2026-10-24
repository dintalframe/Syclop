package com.nuvio.tv.core.trakt

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidTraktActionCredentials @Inject constructor(
    private val profiles: ProfileManager,
    private val auth: TraktAuthDataStore,
    private val app: TraktAppRegistrationStore
) : TraktActionCredentials {
    override fun selection() = TraktActionSelection(profiles.activeProfileId.value, profiles.activeProfileSelectionRevision)
    override fun registration() = app.current()
    override fun grantRevision(profileId: Int) = auth.grantRevision(profileId)
    override suspend fun read(profileId: Int) = auth.getCurrentState(profileId)
    override suspend fun saveRefresh(expected: TraktAuthState, revision: Long, token: TraktTokenResponseDto,
        selectionCurrent: () -> Boolean) = auth.saveRefreshedTokenIfCurrent(token, expected, revision, selectionCurrent)
}
