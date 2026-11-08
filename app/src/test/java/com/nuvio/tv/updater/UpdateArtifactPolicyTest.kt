package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateArtifactPolicyTest {
    @Test
    fun `selects only a compatible signed release asset with a digest`() {
        val arm = asset("syclop-arm64-release.apk")
        val universal = asset("syclop-release.apk")
        val x86 = asset("syclop-x86-release.apk")
        val supported = listOf("arm64-v8a", "armeabi-v7a")

        assertEquals(arm, UpdateArtifactPolicy.select(listOf(universal, x86, arm), supported, TAG))
        assertEquals(universal, UpdateArtifactPolicy.select(listOf(x86, universal), supported, TAG))
        assertNull(UpdateArtifactPolicy.select(listOf(x86, asset("NuvioTV.apk")), supported, TAG))
        assertNull(UpdateArtifactPolicy.select(listOf(arm.copy(digest = null)), supported, TAG))
        assertNull(UpdateArtifactPolicy.select(listOf(arm.copy(size = Long.MAX_VALUE)), supported, TAG))
        assertNull(UpdateArtifactPolicy.select(listOf(arm.copy(browserDownloadUrl = "https://example.com/${arm.name}")), supported, TAG))
        assertNull(UpdateArtifactPolicy.select(listOf(arm), supported, "v0.3.0"))
        assertTrue(UpdateArtifactPolicy.matchesAbi(arm.name, setOf("arm64-v8a"), supported))
        assertFalse(UpdateArtifactPolicy.matchesAbi(arm.name, setOf("x86"), supported))
        assertFalse(UpdateArtifactPolicy.matchesAbi(arm.name, setOf("arm64-v8a", "armeabi-v7a"), supported))
        assertFalse(UpdateArtifactPolicy.matchesAbi(universal.name, setOf("arm64-v8a"), supported))
        assertTrue(UpdateArtifactPolicy.matchesAbi(universal.name, setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), supported))
    }

    @Test
    fun `only approved HTTPS download redirects and matching version are accepted`() {
        assertTrue(UpdateArtifactPolicy.isTrustedDownloadOrigin("https://release-assets.githubusercontent.com/path?token=temporary"))
        assertFalse(UpdateArtifactPolicy.isTrustedDownloadOrigin("http://github.com/path"))
        assertFalse(UpdateArtifactPolicy.isTrustedDownloadOrigin("https://github.com.example.com/path"))
        assertFalse(UpdateArtifactPolicy.isTrustedDownloadOrigin("https://name@github.com/path"))
        assertTrue(UpdateArtifactPolicy.isNewerVersion(TAG, 2, "0.2.0", 1))
        assertFalse(UpdateArtifactPolicy.isNewerVersion(TAG, 1, "0.2.0", 1))
        assertFalse(UpdateArtifactPolicy.isNewerVersion(TAG, 2, "0.1.0", 1))
    }

    private fun asset(name: String) = GitHubAssetDto(
        name = name,
        browserDownloadUrl = "https://github.com/dintalframe/Syclop/releases/download/$TAG/$name",
        size = 123_456,
        digest = "sha256:${"ab".repeat(32)}"
    )

    private companion object {
        const val TAG = "v0.2.0"
    }
}
