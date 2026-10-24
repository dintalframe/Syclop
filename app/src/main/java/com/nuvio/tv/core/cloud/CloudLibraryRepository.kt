package com.nuvio.tv.core.cloud

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridServiceCredential
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.data.local.DebridSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants

@Singleton
class CloudLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    torboxApi: TorboxCloudLibraryProviderApi,
    premiumizeApi: PremiumizeCloudLibraryProviderApi,
    allDebridApi: AllDebridCloudLibraryProviderApi,
    realDebridApi: RealDebridCloudLibraryProviderApi
) {
    private val providerApis: List<CloudLibraryProviderApi> = listOf(torboxApi, premiumizeApi, allDebridApi, realDebridApi)

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
}
