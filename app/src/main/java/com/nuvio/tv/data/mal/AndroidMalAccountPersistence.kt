package com.nuvio.tv.data.mal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface MalAccountCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

/** Retains existing MAL preference names/cipher format so private backup Undo stays compatible. */
internal class AndroidMalAccountPersistence internal constructor(
    private val preferences: android.content.SharedPreferences,
    private val cipher: MalAccountCipher
) : MalAccountPersistence {
    constructor(context: Context) : this(context.getSharedPreferences("syclop_mal_account", Context.MODE_PRIVATE), AndroidMalAccountCipher())
    private val lock = Any()
    private var failedCommit = false
    override val available: Boolean get() = synchronized(lock) { !failedCommit }
    private fun requireAvailable() { if (failedCommit) throw IOException("Restart Syclop before retrying protected MyAnimeList storage.") }
    override fun read(key: String): String? = synchronized(lock) {
        requireAvailable()
        preferences.getString(key, null)?.let(cipher::decrypt)
    }
    override fun write(values: Map<String, String?>) = synchronized(lock) {
        requireAvailable()
        // Encryption failure has not touched preferences and does not poison an intact store.
        val encrypted = values.mapValues { (_, value) -> value?.let(cipher::encrypt) }
        val editor = preferences.edit()
        encrypted.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        commit(editor)
    }
    override fun clear() = synchronized(lock) {
        requireAvailable()
        commit(preferences.edit().clear())
    }
    private fun commit(editor: android.content.SharedPreferences.Editor) {
        // Android commits memory before attempting disk. A false/throwing commit can leave an unpersisted
        // token in that map; fail closed for this process rather than reading it as a durable account.
        val committed = try { editor.commit() } catch (_: Exception) { false }
        if (!committed) {
            failedCommit = true
            throw IOException("Protected MyAnimeList storage failed. Restart Syclop and try again.")
        }
    }
}

private class AndroidMalAccountCipher : MalAccountCipher {
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return encode(cipher.iv) + "." + encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(value: String): String {
        val pieces = value.split('.', limit = 2)
        require(pieces.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(pieces[0])))
        return cipher.doFinal(decode(pieces[1])).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private companion object { const val KEY_ALIAS = "syclop.myanimelist.credentials.v1" }
}
