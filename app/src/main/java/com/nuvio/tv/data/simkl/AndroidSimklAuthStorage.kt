package com.nuvio.tv.data.simkl

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import com.nuvio.tv.data.local.ProfileDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** AUTH V1 credentials are deliberately not imported: Simkl requires a new V2 grant. */
@Singleton
class AndroidSimklAuthStorage private constructor(
    private val store: SimklV2AuthStore,
    profileDataStore: ProfileDataStore
) : SimklAuthStorage by store, ProfileScopedCredentialStore {
    override fun removeProfile(profileId: Int) = store.removeProfile(profileId)
    override fun clearAllProfiles() = store.clearAllProfiles()
    @Inject constructor(@ApplicationContext context: Context, profileDataStore: ProfileDataStore) :
        this(SimklV2AuthStore(AndroidSimklV2Persistence(context), initialProfileId = 0), profileDataStore)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            profileDataStore.activeProfileId.distinctUntilChanged().collect(store::selectProfile)
        }
    }
}

private class AndroidSimklV2Persistence(context: Context) : SimklV2AuthPersistence {
    private val preferences = ProtectedProfilePreferences(
        context, "syclop_simkl_auth_v2", "com.dintal.syclop.simkl.credentials.v2"
    )
    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}
