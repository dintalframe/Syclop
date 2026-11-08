package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.trakt.TraktManualWriteInterceptor
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.api.TorboxApi
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class CloudLibraryDeletionTransport @Inject constructor(
    @Named("debridAuth") client: OkHttpClient,
    @Named("realdebrid") realDebridRetrofit: Retrofit,
    @Named("alldebrid") allDebridRetrofit: Retrofit,
    @Named("torbox") torboxRetrofit: Retrofit,
    @Named("premiumize") premiumizeRetrofit: Retrofit
) {
    // Use the captured credential and prevent both transport retries and HTTP follow-ups.
    private val strictClient = client.newBuilder()
        .cache(null)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(TraktManualWriteInterceptor())
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Cache-Control", "no-cache, no-store").build())
        }
        .build()

    internal val realDebrid = realDebridRetrofit.newBuilder().client(strictClient).build().create(RealDebridApi::class.java)
    internal val allDebrid = allDebridRetrofit.newBuilder().client(strictClient).build().create(AllDebridApi::class.java)
    internal val torbox = torboxRetrofit.newBuilder().client(strictClient).build().create(TorboxApi::class.java)
    internal val premiumize = premiumizeRetrofit.newBuilder().client(strictClient).build().create(PremiumizeApi::class.java)
}

internal fun CloudLibraryItem.matchesSinglePlayableFile(provider: String, file: CloudLibraryFile): Boolean =
    providerId == provider && id.isNotBlank() && files.singleOrNull() == file && file.playable &&
        !file.id.isNullOrBlank() && file.name.isNotBlank() && (file.sizeBytes ?: 0) > 0
