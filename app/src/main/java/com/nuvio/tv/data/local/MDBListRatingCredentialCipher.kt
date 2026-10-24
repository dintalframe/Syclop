package com.nuvio.tv.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Same AES-GCM/profile AAD pattern as ProtectedProfilePreferences; ratings use their own alias. */
@Singleton
class MDBListRatingCredentialCipher internal constructor(private val keyProvider: () -> SecretKey) {
    @Inject constructor() : this(::androidKeystoreKey)
    private val lock = Any()

    fun isEncrypted(value: String) = value.startsWith(PREFIX)

    fun encrypt(value: String, profileId: Int): String = synchronized(lock) {
        require(profileId in 1..6)
        if (value.isBlank()) return@synchronized ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(aad(profileId))
        PREFIX + cipher.iv.toByteString().base64() + ":" + cipher.doFinal(value.toByteArray(Charsets.UTF_8)).toByteString().base64()
    }

    fun decrypt(value: String, profileId: Int): String = synchronized(lock) {
        if (!isEncrypted(value)) return@synchronized value // Only the store's legacy migration reads this form.
        try {
            val parts = value.removePrefix(PREFIX).split(':', limit = 2)
            require(profileId in 1..6 && parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128,
                requireNotNull(parts[0].decodeBase64()).toByteArray()))
            cipher.updateAAD(aad(profileId))
            cipher.doFinal(requireNotNull(parts[1].decodeBase64()).toByteArray()).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            "" // Wrong installation/profile, damaged ciphertext or unavailable Keystore never becomes a key.
        }
    }

    private fun aad(profileId: Int) = "$KEY_ALIAS:$profileId:ratings".toByteArray(Charsets.UTF_8)

    private companion object {
        const val PREFIX = "enc:v1:"
        const val KEY_ALIAS = "syclop_mdblist_rating_credentials_v1"
        fun androidKeystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
    }
}
