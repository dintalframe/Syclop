package com.nuvio.tv.core.trakt

import android.content.Context
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URI

/** Public application identity belongs to Syclop, while OAuth tokens belong to a profile. */
data class TraktAppRegistration(
    val clientId: String = "",
    val redirectUri: String = DEFAULT_REDIRECT_URI
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
    override fun toString(): String = "TraktAppRegistration(configured=$isConfigured)"

    companion object {
        const val DEFAULT_REDIRECT_URI = "https://github.com/dintalframe"
    }
}

internal fun validatedTraktRegistration(clientId: String, redirectUri: String): TraktAppRegistration {
    val id = clientId.trim()
    require(id.isEmpty() || (id.length in 16..256 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_" })) {
        "Enter the Syclop application Client ID from Trakt."
    }
    val redirect = redirectUri.trim().ifBlank { TraktAppRegistration.DEFAULT_REDIRECT_URI }
    val uri = runCatching { URI(redirect) }.getOrNull()
    require(redirect.length <= 512 && !redirect.any { it.isWhitespace() } &&
        (uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)) {
        "Use the exact HTTPS redirect URI registered for the Syclop application."
    }
    return TraktAppRegistration(id, redirect)
}

@Singleton
class TraktAppRegistrationStore @Inject constructor(@ApplicationContext context: Context) {
    // A client ID is public, not a user password or OAuth token. No client secret is stored or sent.
    private val preferences = context.getSharedPreferences("syclop_trakt_app_registration", Context.MODE_PRIVATE)
    private val _registration = MutableStateFlow(read())
    val registration: StateFlow<TraktAppRegistration> = _registration.asStateFlow()
    fun current(): TraktAppRegistration = _registration.value

    private fun read() = TraktAppRegistration(
        clientId = preferences.getString("client_id", null) ?: BuildConfig.TRAKT_CLIENT_ID.trim(),
        redirectUri = preferences.getString("redirect_uri", null)
            ?: BuildConfig.TRAKT_REDIRECT_URI.ifBlank { TraktAppRegistration.DEFAULT_REDIRECT_URI }
    )

    fun save(clientId: String, redirectUri: String) {
        val value = validatedTraktRegistration(clientId, redirectUri)
        check(preferences.edit().putString("client_id", value.clientId)
            .putString("redirect_uri", value.redirectUri).commit()) { "Could not save application setup." }
        _registration.value = value
    }
}
