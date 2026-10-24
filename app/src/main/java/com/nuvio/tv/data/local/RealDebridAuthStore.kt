package com.nuvio.tv.data.local

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.ProfileRealDebridOAuth
import com.nuvio.tv.core.debrid.RealDebridOAuthState
import javax.inject.Inject
import javax.inject.Singleton

interface RealDebridAuthStateStore {
    val activeProfileId: Int
    suspend fun read(profileId: Int): ProfileRealDebridOAuth?
    suspend fun replace(profileId: Int, expectedToken: String, state: RealDebridOAuthState): Boolean
    suspend fun ownerOfToken(token: String): Int?
}

@Singleton
class RealDebridAuthStore @Inject constructor(private val settings: DebridSettingsDataStore) : RealDebridAuthStateStore {
    override val activeProfileId: Int get() = settings.activeProfileId.value
    override suspend fun read(profileId: Int): ProfileRealDebridOAuth? =
        settings.realDebridOAuth(profileId)?.let { ProfileRealDebridOAuth(profileId, it) }

    override suspend fun ownerOfToken(token: String): Int? {
        // Prefer the current viewer when viewers deliberately use the same account/key.
        val profiles = (listOf(activeProfileId) + settings.localProfileIds).distinct()
        return profiles.firstOrNull { settings.credentialForProfile(it, DebridProviders.REAL_DEBRID_ID) == token }
    }

    override suspend fun replace(profileId: Int, expectedToken: String, state: RealDebridOAuthState): Boolean =
        settings.setProviderApiKeyForProfile(profileId, DebridProviders.REAL_DEBRID_ID,
            state.accessToken, expectedToken, state)
}
