package com.nuvio.tv.core.debrid

import com.google.gson.JsonParser
import com.nuvio.tv.data.remote.api.RealDebridApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Named

@Singleton
class DebridAccountValidator @Inject constructor(
    @param:Named("realDebridValidation") private val realDebridApi: RealDebridApi,
    private val allDebridService: AllDebridService
) {
    /** Validate directly with the service. Credentials are neither exchanged nor sent to an app server. */
    suspend fun validate(providerId: String, apiKey: String): Boolean = try {
        when (providerId) {
            DebridProviders.REAL_DEBRID_ID -> {
                val response = realDebridApi.getUser("Bearer ${apiKey.trim()}")
                response.errorBody()?.close()
                val user = response.body()?.use { body ->
                    if (response.isSuccessful) JsonParser.parseString(body.string()).asJsonObject else null
                }
                user != null && user.get("id")?.isJsonPrimitive == true &&
                    user.get("username")?.asString?.isNotBlank() == true
            }
            DebridProviders.ALL_DEBRID_ID -> allDebridService.user(apiKey).username?.isNotBlank() == true
            else -> false
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }
}
