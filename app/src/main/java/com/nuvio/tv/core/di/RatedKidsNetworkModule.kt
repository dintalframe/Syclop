package com.nuvio.tv.core.di

import com.nuvio.tv.data.catalog.RatedKidsHttpClient
import com.nuvio.tv.data.remote.api.TraktApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/** Keeps public Kids response limits and credential suppression off every other Trakt client. */
@Module
@InstallIn(SingletonComponent::class)
object RatedKidsNetworkModule {
    @Provides
    @Singleton
    @Named("ratedKidsTrakt")
    fun provideRatedKidsApi(@Named("trakt") retrofit: Retrofit,
        @Named("trakt") client: OkHttpClient): TraktApi = retrofit.newBuilder()
        .baseUrl("https://api.trakt.tv/")
        .client(RatedKidsHttpClient.create(client))
        .build().create(TraktApi::class.java)
}
