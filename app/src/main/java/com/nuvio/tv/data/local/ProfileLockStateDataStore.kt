package com.nuvio.tv.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileLockStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_lock_state",
    // Losing the local lock store must never open a protected profile.
    corruptionHandler = if (BuildConfig.LOCAL_ONLY_BUILD) null else ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class ProfileLockStateDataStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val signPin: (Int, String) -> String,
    private val nowMillis: () -> Long
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.profileLockStateDataStore, ::signLocalPin, System::currentTimeMillis
    )

    private val pinEnabledMapKey = stringPreferencesKey("pin_enabled_map")

    val pinEnabled: Flow<Map<Int, Boolean>> = dataStore.data.map { prefs ->
        decode(prefs[pinEnabledMapKey])
    }

    suspend fun replaceAll(states: Map<Int, Boolean>) {
        dataStore.edit { prefs ->
            prefs[pinEnabledMapKey] = encode(states)
        }
    }

    suspend fun setPinEnabled(profileId: Int, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = decode(prefs[pinEnabledMapKey]).toMutableMap()
            current[profileId] = enabled
            prefs[pinEnabledMapKey] = encode(current)
        }
    }

    internal suspend fun setLocalPin(profileId: Int, pin: String, currentPin: String?): Boolean {
        require(profileId in 1..ProfileManager.MAX_PROFILES)
        val normalized = requireNotNull(normalizePin(pin))
        var saved = false
        dataStore.edit { prefs ->
            val states = decode(prefs[pinEnabledMapKey])
            if (states[profileId] == true &&
                (currentPin == null || !verifyIn(prefs, profileId, normalizePin(currentPin)).unlocked)
            ) return@edit
            prefs[verifierKey(profileId)] = signPin(profileId, normalized)
            prefs[pinEnabledMapKey] = encode(states + (profileId to true))
            prefs.clearAttempts(profileId)
            saved = true
        }
        return saved
    }

    internal suspend fun verifyLocalPin(profileId: Int, pin: String): SupabaseProfilePinVerifyResult {
        require(profileId in 1..ProfileManager.MAX_PROFILES)
        var result = SupabaseProfilePinVerifyResult()
        dataStore.edit { prefs -> result = verifyIn(prefs, profileId, normalizePin(pin)) }
        return result
    }

    internal suspend fun clearLocalPin(profileId: Int, currentPin: String?): Boolean {
        require(profileId in 1..ProfileManager.MAX_PROFILES)
        var cleared = false
        dataStore.edit { prefs ->
            if (currentPin != null && verifyIn(prefs, profileId, normalizePin(currentPin)).unlocked) {
                prefs.removeLock(profileId)
                cleared = true
            }
        }
        return cleared
    }

    internal suspend fun removeProfile(profileId: Int) {
        dataStore.edit { it.removeLock(profileId) }
    }

    private fun verifyIn(prefs: MutablePreferences, profileId: Int, pin: String?): SupabaseProfilePinVerifyResult {
        if (decode(prefs[pinEnabledMapKey])[profileId] != true) return SupabaseProfilePinVerifyResult()
        val verifier = prefs[verifierKey(profileId)] ?: return SupabaseProfilePinVerifyResult()
        val now = nowMillis()
        val retryKey = retryAtKey(profileId)
        val remaining = ((prefs[retryKey] ?: now) - now).coerceAtMost(PIN_COOLDOWN_MILLIS)
        if (remaining > 0) {
            // Keep the wait bounded even if the system wall clock was moved backwards.
            if (prefs[retryKey] != now + remaining) prefs[retryKey] = now + remaining
            return SupabaseProfilePinVerifyResult(retryAfterSeconds = ((remaining + 999L) / 1_000L).toInt())
        }
        prefs.remove(retryKey)
        if (pin != null && MessageDigest.isEqual(verifier.toByteArray(), signPin(profileId, pin).toByteArray())) {
            prefs.clearAttempts(profileId)
            return SupabaseProfilePinVerifyResult(unlocked = true)
        }
        val attemptsKey = attemptsKey(profileId)
        val attempts = (prefs[attemptsKey] ?: 0) + 1
        if (attempts < PIN_ATTEMPT_LIMIT) {
            prefs[attemptsKey] = attempts
            return SupabaseProfilePinVerifyResult()
        }
        prefs.remove(attemptsKey)
        prefs[retryKey] = now + PIN_COOLDOWN_MILLIS
        return SupabaseProfilePinVerifyResult(retryAfterSeconds = (PIN_COOLDOWN_MILLIS / 1_000L).toInt())
    }

    private fun MutablePreferences.removeLock(profileId: Int) {
        this[pinEnabledMapKey] = encode(decode(this[pinEnabledMapKey]) - profileId)
        remove(verifierKey(profileId))
        clearAttempts(profileId)
    }

    private fun MutablePreferences.clearAttempts(profileId: Int) {
        remove(attemptsKey(profileId))
        remove(retryAtKey(profileId))
    }

    private fun verifierKey(profileId: Int) = stringPreferencesKey("pin_verifier_$profileId")
    private fun attemptsKey(profileId: Int) = intPreferencesKey("pin_attempts_$profileId")
    private fun retryAtKey(profileId: Int) = longPreferencesKey("pin_retry_at_$profileId")

    private fun normalizePin(pin: String): String? {
        if (pin.length != 4) return null
        return pin.map { it.digitToIntOrNull()?.digitToChar() ?: return null }.joinToString("")
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun encode(map: Map<Int, Boolean>): String =
        map.entries.joinToString(separator = ",") { "${it.key}:${it.value}" }

    private fun decode(raw: String?): Map<Int, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val enabled = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null
            id to enabled
        }.toMap()
    }
}

internal const val LOCAL_PROFILE_PIN_KEY_ALIAS = "syclop_local_profile_pin_v1"
private const val PIN_ATTEMPT_LIMIT = 5
private const val PIN_COOLDOWN_MILLIS = 30_000L

private fun signLocalPin(profileId: Int, pin: String): String {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = store.getKey(LOCAL_PROFILE_PIN_KEY_ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore"
    ).run {
        init(KeyGenParameterSpec.Builder(LOCAL_PROFILE_PIN_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        generateKey()
    }
    val signed = Mac.getInstance("HmacSHA256").apply { init(key) }
        .doFinal("$profileId:$pin".toByteArray())
    return Base64.encodeToString(signed, Base64.NO_WRAP)
}
