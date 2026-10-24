package com.nuvio.tv.core.network

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.AuthState
import io.ktor.client.plugins.api.createClientPlugin
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** The reserved endpoint is only a constructor value; neither transport may contact it. */
internal const val SYCLOP_DISABLED_CLOUD_URL = "https://cloud-disabled.syclop.invalid"
internal const val SYCLOP_DISABLED_CLOUD_KEY = "syclop-local-only"

internal class LocalCloudDisabledException : IOException("Cloud accounts are disabled in this local Syclop build")

internal fun startupAuthState(localOnly: Boolean): AuthState =
    if (localOnly) AuthState.SignedOut else AuthState.Loading

internal fun isDisabledCloudHost(host: String): Boolean {
    val value = host.lowercase().trimEnd('.')
    return value == "nuvio.tv" || value.endsWith(".nuvio.tv") ||
        value == "cloud-disabled.syclop.invalid"
}

/** Supabase is retained for DI compatibility, but every request stops before its network engine. */
internal val SyclopCloudGuard = createClientPlugin("SyclopCloudGuard") {
    onRequest { _, _ -> throw LocalCloudDisabledException() }
}

/** Fixed backend clients cannot accidentally fall back to upstream or loopback services. */
internal class SyclopCloudGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (BuildConfig.LOCAL_ONLY_BUILD && isDisabledCloudHost(chain.request().url.host)) {
            throw LocalCloudDisabledException()
        }
        return chain.proceed(chain.request())
    }
}
