package com.nuvio.tv.core.cloud

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridServiceCredential
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.data.local.DebridSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class CloudLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    private val playbackSessions: CloudLibraryPlaybackSessionStore,
    torboxApi: TorboxCloudLibraryProviderApi,
    premiumizeApi: PremiumizeCloudLibraryProviderApi,
    allDebridApi: AllDebridCloudLibraryProviderApi,
    realDebridApi: RealDebridCloudLibraryProviderApi
) {
    private val providerApis: List<CloudLibraryProviderApi> = listOf(torboxApi, premiumizeApi, allDebridApi, realDebridApi)
    private val cleanupTickets = CloudPlaybackCleanupTickets()
    private val _completedDeletions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val completedDeletions = _completedDeletions.asSharedFlow()

    internal fun withoutDeletedItems(state: CloudLibraryUiState): CloudLibraryUiState {
        val scope = cleanupScope()
        return state.copy(providers = state.providers.map { provider ->
            provider.copy(items = provider.items.filterNot { wasDeleted(scope, it) })
        })
    }

    suspend fun refresh(): CloudLibraryUiState {
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryUiState(isLoaded = true, isEnabled = false)
        }

        val credentials = DebridProviders.configuredServices(settings)
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

        val providerStates = credentials.map { credential ->
            val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
            if (api == null) {
                return@map CloudLibraryProviderState(
                    provider = credential.provider,
                    errorMessage = context.getString(
                        R.string.cloud_library_error_provider_unavailable,
                        credential.provider.displayName
                    )
                )
            }

            api.listItems(credential.apiKey)
                .fold(
                    onSuccess = { items ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            items = items
                        )
                    },
                    onFailure = { error ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            errorMessage = error.message
                        )
                    }
                )
        }

        return CloudLibraryUiState(
            isLoaded = true,
            isEnabled = true,
            isRefreshing = false,
            providers = providerStates
        )
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        val profile = dataStore.activeProfileId.value
        val selectionRevision = dataStore.activeProfileSelectionRevision
        val accountRevision = dataStore.activeCredentialRevision
        if (accountRevision < 0L) return CloudLibraryPlaybackResult.MissingCredentials
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryPlaybackResult.Failed(
                context.getString(R.string.cloud_library_error_disabled)
            )
        }
        val credential = DebridProviders.configuredServices(settings)
            .firstOrNull { credential -> credential.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.MissingCredentials
        val api = providerApis.firstOrNull { it.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.Failed()
        val credentials = DebridProviders.all().associate { it.id to dataStore.credentialForProfile(profile, it.id).trim() }
        if (credentials[credential.provider.id] != credential.apiKey.trim()) return CloudLibraryPlaybackResult.MissingCredentials
        if (dataStore.activeProfileId.value != profile || dataStore.activeProfileSelectionRevision != selectionRevision)
            return CloudLibraryPlaybackResult.MissingCredentials
        val binding = ConnectedServicePlaybackGrants.registry.observe(profile, credentials, selectionRevision, accountRevision)
        val result = api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file
        )
        if (result is CloudLibraryPlaybackResult.Success) {
            val current = DebridProviders.all().associate { it.id to dataStore.credentialForProfile(profile, it.id).trim() }
            if (dataStore.activeProfileId.value != profile || dataStore.activeProfileSelectionRevision != selectionRevision || dataStore.activeCredentialRevision != accountRevision || current != credentials ||
                !ConnectedServicePlaybackGrants.registry.issue(result.url, credential.provider.id, binding)) {
                return CloudLibraryPlaybackResult.MissingCredentials
            }
        }
        return result
    }

    suspend fun connectedCloudCredentials(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        return settings
            .takeIf { it.cloudLibraryEnabled }
            ?.let(DebridProviders::configuredServices)
            .orEmpty()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }
    }

    internal suspend fun rememberCloudCleanup(
        token: String,
        playbackUrl: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ) = rememberCleanup(token, playbackUrl, item, file, cleanupScope())

    internal suspend fun rememberCreatedPlaybackCleanup(
        playbackUrl: String,
        item: CloudLibraryItem,
        scope: CloudPlaybackCleanupScope
    ) {
        val file = item.files.singleOrNull() ?: return
        rememberCleanup(null, playbackUrl, item, file, scope)
    }

    private suspend fun rememberCleanup(
        token: String?,
        playbackUrl: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        scope: CloudPlaybackCleanupScope
    ) {
        if (item.files.singleOrNull() != file || !file.playable || item.id.isBlank()) return
        try {
            if (!cleanupEnabled(scope) || !ConnectedServicePlaybackGrants.registry.permits(playbackUrl)) return
            cleanupTickets.remember(
                CloudPlaybackCleanupTicket(token, playbackUrl.cleanupDigest(), item, file, scope)
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A cleanup preference or proof failure must not interrupt playback.
        }
    }

    internal suspend fun torrentIdsBeforeCreate(
        scope: CloudPlaybackCleanupScope,
        providerId: String,
        apiKey: String
    ): Set<String>? {
        val provider = providerApis.firstOrNull { it.provider.id == providerId } ?: return null
        return withTimeoutOrNull(1_500L) {
            try {
                if (!cleanupAuthorized(scope, providerId, apiKey)) return@withTimeoutOrNull null
                provider.torrentIdsBeforeCreate(apiKey)
                    ?.takeIf { cleanupAuthorized(scope, providerId, apiKey) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun deleteAfterCompletedPlayback(cloudSessionToken: String?, playbackUrl: String): Boolean {
        val ticket = cleanupTickets.take(cloudSessionToken, playbackUrl.cleanupDigest()) ?: return false
        var deleted = false
        try {
            if (cloudSessionToken != null) {
                val session = playbackSessions.load(cloudSessionToken) ?: return false
                if (session.item.stableKey != ticket.item.stableKey || session.currentFile != ticket.file) return false
            }
            val providerId = ticket.item.providerId
            val provider = providerApis.firstOrNull { it.provider.id == providerId } ?: return false
            val scope = ticket.scope
            deleted = withTimeoutOrNull(12_000L) {
                if (cleanupScope() != scope) return@withTimeoutOrNull false
                val apiKey = dataStore.credentialForProfile(scope.profileId, providerId).trim()
                if (!cleanupAuthorized(scope, providerId, apiKey)) return@withTimeoutOrNull false
                provider.deleteCompletedFile(apiKey, ticket.item, ticket.file) {
                    cleanupAuthorized(scope, providerId, apiKey)
                }
            } ?: false
            return deleted
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        } finally {
            cleanupTickets.finish(ticket, deleted)
            if (deleted) _completedDeletions.tryEmit(Unit)
        }
    }

    internal fun wasDeleted(scope: CloudPlaybackCleanupScope, item: CloudLibraryItem): Boolean =
        cleanupTickets.wasDeleted(scope, item)

    private fun cleanupScope() = CloudPlaybackCleanupScope(
        dataStore.activeProfileId.value,
        dataStore.activeProfileSelectionRevision,
        dataStore.activeCredentialRevision
    )

    private suspend fun cleanupEnabled(scope: CloudPlaybackCleanupScope): Boolean =
        scope.accountRevision >= 0 && cleanupScope() == scope &&
            dataStore.cloudDeleteAfterPlaybackEnabledForProfile(scope.profileId) && cleanupScope() == scope

    private suspend fun cleanupAuthorized(scope: CloudPlaybackCleanupScope, providerId: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && scope.accountRevision >= 0 && cleanupScope() == scope &&
            dataStore.isCloudCleanupAuthorizedForProfile(scope.profileId, providerId, apiKey) && cleanupScope() == scope
}

private fun String.cleanupDigest(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
