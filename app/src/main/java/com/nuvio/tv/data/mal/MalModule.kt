package com.nuvio.tv.data.mal

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class MalModule {
    @Binds
    @IntoSet
    abstract fun bindMalTrackingProvider(provider: MalTrackingProvider): com.nuvio.tv.core.tracking.TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindMalTrackingLibrary(provider: MalTrackingLibraryProvider): com.nuvio.tv.core.tracking.TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindMalProfileCredentials(store: MalAuthStore): ProfileScopedCredentialStore
}
