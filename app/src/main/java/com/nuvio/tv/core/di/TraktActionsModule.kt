package com.nuvio.tv.core.di

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TraktActionsApi
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TraktActionsModule {
    @Provides @Singleton
    fun api(moshi: Moshi): TraktActionsApi {
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor(com.nuvio.tv.core.trakt.TraktManualWriteInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                check(original.url.isHttps && original.url.host in setOf("api.trakt.tv", "auth.trakt.tv")) {
                    "Unexpected Trakt endpoint."
                }
                chain.proceed(original.newBuilder().header("User-Agent", "Syclop/${BuildConfig.VERSION_NAME}")
                    .header("trakt-api-version", "2").header("Accept", "application/json").build())
            }.build()
        return Retrofit.Builder().baseUrl("https://api.trakt.tv/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(TraktActionsApi::class.java)
    }
}
