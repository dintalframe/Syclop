package com.nuvio.tv.data.mal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** MAL-only, per-profile credentials encrypted by a non-exportable Android Keystore key. */
@Singleton
class MalAuthStore @Inject constructor(
    @ApplicationContext context: Context,
    profileDataStore: ProfileDataStore
) : ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences("syclop_mal_account", Context.MODE_PRIVATE)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(MalAccountState())
    val state: StateFlow<MalAccountState> = mutableState.asStateFlow()
    private var tokens: MalTokens? = null

    init {
        load(1)
        scope.launch {
            profileDataStore.activeProfileId.distinctUntilChanged().collect { profile ->
                if (profile != state.value.scope.profileId) load(profile)
            }
        }
    }

    fun isCurrent(expected: MalScope): Boolean = state.value.scope == expected

    fun credentials(expected: MalScope): MalTokens? = synchronized(lock) {
        if (isCurrent(expected)) tokens else null
    }

    fun configure(configuration: MalConfiguration) = synchronized(lock) {
        val current = state.value
        if (current.configuration == configuration) return@synchronized
        preferences.edit().putString("application_configuration", encrypt(buildJsonObject {
            put("client_id", configuration.clientId)
            put("redirect_uri", configuration.redirectUri)
        }.toString())).apply()
        tokens = null
        mutableState.value = current.copy(
            scope = current.scope.copy(generation = current.scope.generation + 1),
            configuration = configuration, connected = false, username = null
        )
        persist()
    }

    fun complete(expected: MalScope, received: MalTokens, username: String): Boolean = synchronized(lock) {
        if (!isCurrent(expected)) return@synchronized false
        tokens = received
        mutableState.value = state.value.copy(connected = true, username = username)
        persist()
        true
    }

    fun replaceTokens(expected: MalScope, received: MalTokens): Boolean = synchronized(lock) {
        if (!isCurrent(expected) || !state.value.connected) return@synchronized false
        tokens = received
        persist()
        true
    }

    fun automaticProgress(enabled: Boolean) = synchronized(lock) {
        mutableState.value = state.value.copy(automaticProgress = enabled)
        persist()
    }

    fun signOut(expected: MalScope = state.value.scope) = synchronized(lock) {
        if (!isCurrent(expected)) return@synchronized
        tokens = null
        mutableState.value = state.value.copy(
            scope = expected.copy(generation = expected.generation + 1), connected = false, username = null
        )
        persist()
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        preferences.edit().remove(key(profileId)).apply()
        if (state.value.scope.profileId == profileId) {
            tokens = null
            mutableState.value = MalAccountState(scope = state.value.scope.copy(generation = state.value.scope.generation + 1),
                configuration = state.value.configuration)
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        preferences.edit().clear().apply()
        tokens = null
        mutableState.value = MalAccountState(scope = state.value.scope.copy(generation = state.value.scope.generation + 1),
            configuration = shippedConfiguration())
    }

    private fun load(profileId: Int) = synchronized(lock) {
        val json = preferences.getString(key(profileId), null)?.let {
            runCatching { Json.parseToJsonElement(decrypt(it)).jsonObject }.getOrNull()
        }
        val application = preferences.getString("application_configuration", null)?.let {
            runCatching { Json.parseToJsonElement(decrypt(it)).jsonObject }.getOrNull()
        }
        fun string(name: String) = json?.get(name)?.jsonPrimitive?.contentOrNull.orEmpty()
        val configuration = MalConfiguration(
            application?.get("client_id")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: string("client_id").takeIf(String::isNotBlank) ?: shippedConfiguration().clientId,
            application?.get("redirect_uri")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: string("redirect_uri").takeIf(String::isNotBlank) ?: shippedConfiguration().redirectUri
        )
        val registrationMatches = string("client_id").isBlank() || string("client_id") == configuration.clientId
        tokens = string("access_token").takeIf { registrationMatches && it.isNotBlank() }?.let { access ->
            MalTokens(access, string("refresh_token"), json?.get("expires_at")?.jsonPrimitive?.longOrNull ?: 0)
        }
        mutableState.value = MalAccountState(
            scope = MalScope(profileId, state.value.scope.generation + 1),
            configuration = configuration,
            connected = tokens != null,
            username = string("username").takeIf { tokens != null && it.isNotBlank() },
            automaticProgress = string("automatic_progress") != "false"
        )
    }

    private fun persist() {
        val current = state.value
        val value = buildJsonObject {
            put("client_id", current.configuration.clientId)
            put("redirect_uri", current.configuration.redirectUri)
            put("username", current.username.orEmpty())
            put("automatic_progress", current.automaticProgress.toString())
            tokens?.let {
                put("access_token", it.accessToken)
                put("refresh_token", it.refreshToken)
                put("expires_at", it.expiresAtMs)
            }
        }.toString()
        // Failing closed is preferable to silently saving a token in plaintext.
        preferences.edit().putString(key(current.scope.profileId), encrypt(value)).apply()
    }

    private fun shippedConfiguration() = MalConfiguration(BuildConfig.MAL_CLIENT_ID.trim(), BuildConfig.MAL_REDIRECT_URI.trim())

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return encode(cipher.iv) + "." + encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(value: String): String {
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

    private fun key(profile: Int) = "account.p$profile"
    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private companion object { const val KEY_ALIAS = "syclop.myanimelist.credentials.v1" }
}
