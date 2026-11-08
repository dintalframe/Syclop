package com.nuvio.tv.core.sync.androidtv

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCardLaunchAuthenticatorTest {
    @Test
    fun genuineCardOpensPlaybackOnlyForItsProfileAndInstallation() {
        val fixture = IntentFixture()
        var profile = 1
        val auth = authenticator({ profile }, 4)
        auth.authenticate(fixture.intent)
        assertTrue(auth.isCurrentCard(fixture.intent))
        profile = 2
        assertFalse(auth.isCurrentCard(fixture.intent))
        profile = 1
        assertFalse(authenticator({ profile }, 9).isCurrentCard(fixture.intent))
    }

    @Test
    fun forgedOrLegacyExtrasCannotStartAutomaticPlayback() {
        val fixture = IntentFixture()
        assertFalse(authenticator({ 1 }, 4).isCurrentCard(fixture.intent))
        fixture.values["syclop.launcher.signature"] = "a".repeat(64)
        fixture.values["syclop.launcher.profile"] = 1
        assertFalse(authenticator({ 1 }, 4).isCurrentCard(fixture.intent))
    }

    @Test
    fun changingAnyPlaybackOrArtworkFieldInvalidatesTheCard() {
        val fixture = IntentFixture()
        val auth = authenticator({ 1 }, 4)
        auth.authenticate(fixture.intent)
        for (key in listOf("contentId", "contentType", "videoId", "name", "poster", "backdrop", "logo", "episodeTitle", "launchMode", "season", "episode")) {
            val before = fixture.values[key]
            fixture.values[key] = if (before is Int) before + 1 else "modified"
            assertFalse("Modified $key was accepted", auth.isCurrentCard(fixture.intent))
            fixture.values[key] = before
        }
        assertTrue(auth.isCurrentCard(fixture.intent))
    }

    @Test
    fun malformedOrUnavailableKeysUseTheSafeFallback() {
        val fixture = IntentFixture()
        val unavailable = TvCardLaunchAuthenticator({ 1 }) { error("Fixture unavailable") }
        unavailable.authenticate(fixture.intent)
        assertFalse(unavailable.isCurrentCard(fixture.intent))
        fixture.values["syclop.launcher.signature"] = "oversized".repeat(1000)
        assertFalse(unavailable.isCurrentCard(fixture.intent))
    }

    private fun authenticator(profile: () -> Int, seed: Byte) = TvCardLaunchAuthenticator(profile) { payload ->
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(ByteArray(32) { seed }, "HmacSHA256"))
        }.doFinal(payload)
    }

    private class IntentFixture {
        val values = mutableMapOf<String, Any?>(
            "contentId" to "tt12345", "contentType" to "series", "videoId" to "tt12345:2:3",
            "name" to "Fixture title", "season" to 2, "episode" to 3, "launchMode" to "stream"
        )
        val intent = mockk<Intent> {
            every { action } returns Intent.ACTION_VIEW
            every { getStringExtra(any()) } answers { values[firstArg()] as? String }
            every { getIntExtra(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { putExtra(any<String>(), any<String>()) } answers {
                values[firstArg()] = secondArg<String>()
                this@mockk
            }
            every { putExtra(any<String>(), any<Int>()) } answers {
                values[firstArg()] = secondArg<Int>()
                this@mockk
            }
        }
    }
}
