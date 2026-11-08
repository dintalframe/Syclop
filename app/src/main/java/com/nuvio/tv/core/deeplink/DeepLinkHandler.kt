package com.nuvio.tv.core.deeplink

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.providers.ProviderInstallUrls
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class AddonLinkInstallRequest private constructor(
    val manifestUrl: String,
    val host: String,
    val profileId: Int,
    val selectionRevision: Long
) {
    companion object {
        fun create(url: String, profileId: Int, selectionRevision: Long): AddonLinkInstallRequest? {
            val normalized = ProviderInstallUrls.normalize(url) ?: return null
            return AddonLinkInstallRequest(normalized, normalized.toHttpUrl().host, profileId, selectionRevision)
        }
    }
}

class DeepLinkHandler @Inject constructor(
    private val addonRepository: AddonRepositoryImpl,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    internal suspend fun installAddon(request: AddonLinkInstallRequest): DeepLinkInstallResult? {
        val coroutine = currentCoroutineContext()
        val isCurrent = {
            coroutine.isActive && profileManager.activeProfileId.value == request.profileId &&
                profileManager.activeProfileSelectionRevision == request.selectionRevision
        }
        if (!isCurrent()) return null
        val installed = try {
            val result = withTimeoutOrNull(30_000L) { addonRepository.fetchAddon(request.manifestUrl) }
            result is NetworkResult.Success && isCurrent() && addonRepository.addAddonIfCurrent(
                request.manifestUrl, request.profileId, request.selectionRevision, isCurrent
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!isCurrent()) return null
        return if (installed) DeepLinkInstallResult.Success(context.getString(R.string.addon_install_success, request.host))
        else DeepLinkInstallResult.Error(context.getString(R.string.provider_install_failed))
    }
}

sealed interface DeepLinkInstallResult {
    val message: String

    data class Success(
        override val message: String
    ) : DeepLinkInstallResult

    data class Error(
        override val message: String
    ) : DeepLinkInstallResult
}
