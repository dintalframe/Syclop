package com.nuvio.tv.core.di

import com.nuvio.tv.data.simkl.OkHttpSimklV2OAuthTransport
import com.nuvio.tv.data.simkl.SimklApiConfiguration
import com.nuvio.tv.data.simkl.SimklAuthStorage
import com.nuvio.tv.data.simkl.SimklV2OAuthClient
import com.nuvio.tv.data.simkl.SimklV2TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SimklV2Module {
    @Provides @Singleton
    fun oauth(configuration: SimklApiConfiguration): SimklV2OAuthClient = SimklV2OAuthClient(
        OkHttpSimklV2OAuthTransport(configuration), configuration
    )

    @Provides @Singleton
    fun tokens(storage: SimklAuthStorage, oauth: SimklV2OAuthClient): SimklV2TokenManager =
        SimklV2TokenManager(storage, oauth)
}
