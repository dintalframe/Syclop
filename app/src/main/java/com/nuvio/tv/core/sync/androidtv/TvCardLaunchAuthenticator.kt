package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nuvio.tv.MainActivity
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvCardLaunchAuthenticator internal constructor(
    private val activeProfileId: () -> Int,
    private val sign: (ByteArray) -> ByteArray
) {
    @Inject constructor(profiles: ProfileManager) : this({ profiles.activeProfileId.value }, ::signWithKeystore)

    fun createIntent(context: Context, progress: WatchProgress): Intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        putExtra("contentId", progress.contentId)
        putExtra("contentType", progress.contentType)
        putExtra("videoId", progress.videoId)
        putExtra("name", progress.name)
        putExtra("poster", progress.poster)
        putExtra("backdrop", progress.backdrop)
        putExtra("logo", progress.logo)
        progress.season?.let { putExtra("season", it) }
        progress.episode?.let { putExtra("episode", it) }
        putExtra("episodeTitle", progress.episodeTitle)
        putExtra("launchMode", "stream")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        authenticate(this)
    }

    internal fun authenticate(intent: Intent) {
        intent.putExtra(PROFILE, activeProfileId())
        // A missing or unavailable Keystore leaves this card eligible for the safe Details fallback.
        runCatching { payload(intent)?.let(sign)?.toHex() }.getOrNull()?.let { intent.putExtra(SIGNATURE, it) }
    }

    fun isCurrentCard(intent: Intent): Boolean = runCatching {
        if (intent.getIntExtra(PROFILE, -1) != activeProfileId()) return false
        val tag = intent.getStringExtra(SIGNATURE)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return false
        val expected = payload(intent)?.let(sign)?.toHex() ?: return false
        MessageDigest.isEqual(tag.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))
    }.getOrDefault(false)

    fun revoke() = revokeIssuedCards()

    private fun payload(intent: Intent): ByteArray? {
        if (intent.action != Intent.ACTION_VIEW || intent.getStringExtra("launchMode") != "stream") return null
        val profile = intent.getIntExtra(PROFILE, -1).takeIf { it in 1..6 } ?: return null
        if (intent.getStringExtra("contentId").isNullOrBlank() || intent.getStringExtra("videoId").isNullOrBlank() ||
            intent.getStringExtra("name").isNullOrBlank() || intent.getStringExtra("contentType") !in setOf("movie", "series")) return null
        val values = TEXT_FIELDS.map { intent.getStringExtra(it) }
        if (values.any { it != null && (it.length > 4096 || it.any(Char::isISOControl)) }) return null
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("Syclop launcher card v1")
                output.writeInt(profile)
                values.forEach { value ->
                    output.writeBoolean(value != null)
                    if (value != null) output.writeUTF(value)
                }
                output.writeInt(intent.getIntExtra("season", -1))
                output.writeInt(intent.getIntExtra("episode", -1))
            }
        }.toByteArray()
    }

    companion object {
        private const val PROFILE = "syclop.launcher.profile"
        private const val SIGNATURE = "syclop.launcher.signature"
        private const val KEY = "syclop_tv_card_launch_v1"
        private val keyLock = Any()
        private val TEXT_FIELDS = listOf("contentId", "contentType", "videoId", "name", "poster", "backdrop", "logo", "episodeTitle", "launchMode")

        internal fun revokeIssuedCards() = synchronized(keyLock) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY)
        }

        private fun signWithKeystore(payload: ByteArray): ByteArray = synchronized(keyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey(KEY, null) as? SecretKey ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore"
            ).run {
                init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKey()
            }
            Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(payload)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
