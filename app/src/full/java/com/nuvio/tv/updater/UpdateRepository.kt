package com.nuvio.tv.updater

import android.os.Build
import android.os.Process
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible APK release found for ${channel.storedValue} channel")

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi
) {

    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> {
        if (!SelfUpdatePolicy.enabled) return Result.failure(IllegalStateException("Self-updates are disabled in Syclop."))
        return runCatching {
            val owner = UpdateArtifactPolicy.OWNER
            val repo = UpdateArtifactPolicy.REPOSITORY

            val releases = when (channel) {
                UpdateChannel.STABLE -> {
                    val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                    if (response.code() == 404) throw NoEligibleUpdateException(channel)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    listOf(response.body() ?: error("Empty GitHub release response"))
                }
                UpdateChannel.BETA -> {
                    val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    response.body() ?: error("Empty GitHub release response")
                }
            }
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases, channel)
                .firstNotNullOfOrNull { release ->
                    val tag = release.tagName ?: return@firstNotNullOfOrNull null
                    val asset = UpdateArtifactPolicy.select(release.assets, supportedUpdateAbis(), tag)
                    asset?.let { release to it }
                }
                ?: throw NoEligibleUpdateException(channel)
            val (dto, asset) = releaseWithAsset

            val tag = requireNotNull(dto.tagName)

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = requireNotNull(asset.size),
                assetSha256 = requireNotNull(UpdateArtifactPolicy.digest(asset))
            )
        }.onFailure { if (it is CancellationException) throw it }
    }
}

internal fun supportedUpdateAbis(): List<String> =
    (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).orEmpty().toList()
