package com.nuvio.tv.core.debrid

import com.google.gson.JsonParser
import com.nuvio.tv.data.remote.api.RealDebridApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Named
import com.nuvio.tv.core.setup.FirstWatchAccountOutcome

data class DebridAccountCheck(val outcome: FirstWatchAccountOutcome, val entitlementExpiresAtMs: Long? = null)

@Singleton
class DebridAccountValidator @Inject constructor(
    @param:Named("realDebridValidation") private val realDebridApi: RealDebridApi,
    private val allDebridService: AllDebridService
) {
    /** Validate directly with the service. Credentials are neither exchanged nor sent to an app server. */
    suspend fun validate(providerId: String, apiKey: String): Boolean =
        check(providerId, apiKey).outcome == FirstWatchAccountOutcome.ACCEPTED

    suspend fun check(providerId: String, apiKey: String): DebridAccountCheck = try {
        when (providerId) {
            DebridProviders.REAL_DEBRID_ID -> {
                val response = realDebridApi.getUser("Bearer ${apiKey.trim()}")
                response.errorBody()?.close()
                val user = response.body()?.use { body ->
                    if (response.isSuccessful) JsonParser.parseString(body.string()).asJsonObject else null
                }
                DebridAccountCheck(if (user != null && user.get("id")?.isJsonPrimitive == true &&
                    user.get("username")?.asString?.isNotBlank() == true) FirstWatchAccountOutcome.ACCEPTED
                    else if (response.code() in setOf(401, 403)) FirstWatchAccountOutcome.REJECTED
                    else FirstWatchAccountOutcome.UNAVAILABLE)
            }
            DebridProviders.ALL_DEBRID_ID -> DebridAccountCheck(
                if (allDebridService.user(apiKey).username?.isNotBlank() == true) FirstWatchAccountOutcome.ACCEPTED
                else FirstWatchAccountOutcome.UNAVAILABLE)
            else -> DebridAccountCheck(FirstWatchAccountOutcome.UNAVAILABLE)
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        DebridAccountCheck(if (error is AllDebridApiException && error.reason == DirectDebridFailureReason.AUTH_EXPIRED)
            FirstWatchAccountOutcome.REJECTED else FirstWatchAccountOutcome.UNAVAILABLE)
    }
}
