package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import java.net.URI

internal object UpdateArtifactPolicy {
    const val OWNER = "dintalframe"
    const val REPOSITORY = "Syclop"
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private const val UNIVERSAL_APK = "syclop-release.apk"
    private val splitNames = mapOf(
        "arm64-v8a" to "syclop-arm64-release.apk",
        "armeabi-v7a" to "syclop-armeabi-v7a-release.apk",
        "x86" to "syclop-x86-release.apk",
        "x86_64" to "syclop-x86_64-release.apk"
    )
    private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val sha256 = Regex("sha256:([0-9a-f]{64})")

    fun select(assets: List<GitHubAssetDto>, supportedAbis: List<String>, tag: String): GitHubAssetDto? {
        if (!tag.startsWith("v") || VersionUtils.parse(tag) == null || supportedAbis.none(splitNames::containsKey)) return null
        val names = supportedAbis.mapNotNull(splitNames::get) + UNIVERSAL_APK
        return names.firstNotNullOfOrNull { name ->
            assets.singleOrNull { it.name == name }?.takeIf { asset ->
                asset.size?.let { it in 1L..MAX_APK_BYTES } == true && digest(asset) != null &&
                    asset.browserDownloadUrl == "https://github.com/$OWNER/$REPOSITORY/releases/download/$tag/$name"
            }
        }
    }

    fun digest(asset: GitHubAssetDto): String? = sha256.matchEntire(asset.digest.orEmpty())?.groupValues?.get(1)

    fun isTrustedDownloadOrigin(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in downloadHosts && uri.port in setOf(-1, 443) &&
            uri.rawUserInfo == null && uri.rawFragment == null
    }.getOrDefault(false)

    fun matchesAbi(assetName: String, packagedAbis: Set<String>, supportedAbis: List<String>): Boolean =
        if (assetName == UNIVERSAL_APK) packagedAbis == splitNames.keys && packagedAbis.any(supportedAbis::contains)
        else packagedAbis.size == 1 && packagedAbis.any { it in supportedAbis && splitNames[it] == assetName }

    fun isNewerVersion(tag: String, candidateCode: Long, archiveVersion: String?, installedCode: Long): Boolean =
        candidateCode > installedCode && archiveVersion == tag.removePrefix("v")
}
