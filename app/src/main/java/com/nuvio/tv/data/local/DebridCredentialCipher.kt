package com.nuvio.tv.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Credentials remain profile scoped; backups contain ciphertext, never the keystore key. */
@Singleton
class DebridCredentialCipher internal constructor(private val keyProvider: () -> SecretKey) {
    @Inject constructor() : this(::androidKeystoreSecretKey)

    private val lock = Any()

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(value: String, profileId: Int, providerId: String): String = synchronized(lock) {
        if (value.isBlank()) return@synchronized ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(aad(profileId, providerId))
        PREFIX + encode(cipher.iv) + ":" + encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(value: String, profileId: Int, providerId: String): String = synchronized(lock) {
        // Read legacy preferences only so the DataStore can immediately migrate them.
        if (!isEncrypted(value)) return@synchronized value
        try {
            val parts = value.removePrefix(PREFIX).split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD(aad(profileId, providerId))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // A restored backup cannot decrypt another installation's credentials.
            ""
        }
    }

    private fun aad(profileId: Int, providerId: String) =
        "$KEY_ALIAS:$profileId:$providerId".toByteArray(Charsets.UTF_8)

    private fun encode(value: ByteArray) = value.toByteString().base64()
    private fun decode(value: String) = requireNotNull(value.decodeBase64()).toByteArray()

    private companion object {
        const val PREFIX = "enc:v1:"
        const val KEY_ALIAS = "syclop_debrid_credentials_v1"

        fun androidKeystoreSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        }
    }
}
