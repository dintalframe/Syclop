package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridDeviceAuthorization
import com.nuvio.tv.core.debrid.DebridDeviceAuthorizationTokenResult
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridAccountValidator
import com.nuvio.tv.core.debrid.DebridAccountCheck
import com.nuvio.tv.core.setup.FirstWatchAccountOutcome
import com.nuvio.tv.core.setup.firstWatchAccountBinding
import com.nuvio.tv.core.debrid.DebridNativeDeviceAuth
import com.nuvio.tv.core.debrid.PremiumizeDeviceAuth
import com.nuvio.tv.core.debrid.DebridAuthProfileGuard
import com.nuvio.tv.core.debrid.DebridManualCredentialSaver
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DebridFormatterConfigServer
import com.nuvio.tv.core.server.DebridFormatterSettings
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.domain.model.DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamSortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class DebridSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    private val torboxApi: TorboxApi,
    private val premiumizeApi: PremiumizeApi,
    private val accountValidator: DebridAccountValidator,
    private val nativeDeviceAuth: DebridNativeDeviceAuth,
    private val premiumizeDeviceAuth: PremiumizeDeviceAuth,
    val firstWatch: com.nuvio.tv.core.setup.FirstWatchReadinessRepository
) : ViewModel() {
    val activeProfileId = dataStore.activeProfileId
    private val authProfileGuard = DebridAuthProfileGuard(activeProfileId.value) { dataStore.activeProfileSelectionRevision }
    private var formatterServer: DebridFormatterConfigServer? = null
    private var logoBytes: ByteArray? = null

    private val _uiState = MutableStateFlow(DebridSettingsUiState())
    val uiState: StateFlow<DebridSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val validationError: SharedFlow<String> = _validationError.asSharedFlow()
    private val _checkingAccounts = MutableStateFlow(false)
    val checkingAccounts: StateFlow<Boolean> = _checkingAccounts.asStateFlow()
    private var accountCheckJob: Job? = null
    private val accountCheckGeneration = java.util.concurrent.atomic.AtomicLong()

    fun checkSavedAccounts() {
        if (_checkingAccounts.value) return
        cancelAccountCheck()
        val operation = accountCheckGeneration.get()
        val captured = firstWatch.captureScope()
        val providers = _uiState.value.resolverProviders.map { it.id }
        if (providers.isEmpty()) return
        _checkingAccounts.value = true
        accountCheckJob = viewModelScope.launch {
            fun current() = accountCheckGeneration.get() == operation && firstWatch.isCurrent(captured)
            try {
                for (provider in providers) {
                    if (!current()) return@launch
                    val key = dataStore.credentialForProfile(captured.profileId, provider)
                    if (key.isBlank() || !current()) return@launch
                    val result = checkProviderAccount(provider, key)
                    if (!current()) return@launch
                    dataStore.recordFirstWatchAccount(captured, provider,
                        firstWatchAccountBinding(captured.profileId, provider, key), result,
                        System.currentTimeMillis(), ::current)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current()) _validationError.tryEmit(context.getString(R.string.first_watch_unavailable))
            } finally {
                if (accountCheckGeneration.get() == operation) {
                    accountCheckJob = null
                    _checkingAccounts.value = false
                }
            }
        }
    }

    fun cancelAccountCheck() {
        accountCheckGeneration.incrementAndGet()
        accountCheckJob?.cancel()
        accountCheckJob = null
        _checkingAccounts.value = false
    }

    private val manualCredentialSaver = DebridManualCredentialSaver(
        scope = viewModelScope,
        captureScope = { authProfileGuard.capture(activeProfileId.value) },
        isCurrent = { authProfileGuard.valid(it, activeProfileId.value) },
        credential = dataStore::credentialForProfile,
        validate = ::checkProviderAccount,
        commit = { profile, provider, key, expected, check ->
            dataStore.setProviderApiKeyForProfile(profile, provider, key, expected,
                acceptedCheck = check, checkedAtMs = System.currentTimeMillis())
        },
        setBusy = { _validating.value = it },
        onFailure = { failure ->
            _validationError.tryEmit(context.getString(if (failure == DebridManualCredentialSaver.Failure.PROFILE_CHANGED)
                R.string.debrid_auth_profile_changed else R.string.debrid_key_invalid))
        }
    )

    init {
        viewModelScope.launch {
            activeProfileId.collectLatest { authProfileGuard.observe(it); manualCredentialSaver.cancel(); cancelAccountCheck() }
        }
        loadLogoBytes()
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    // File-backed drawable resources can be opened as raw bytes without decoding.
    @android.annotation.SuppressLint("ResourceType")
    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.syclop_primary_head_512)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    fun setCloudLibraryEnabled(enabled: Boolean) {
        update { dataStore.setCloudLibraryEnabled(enabled) }
    }

    fun setCloudDeleteAfterPlayback(enabled: Boolean) {
        val profileId = activeProfileId.value
        update { dataStore.setCloudDeleteAfterPlayback(profileId, enabled) }
    }

    fun setConnectedServiceFallbackEnabled(enabled: Boolean) {
        update { dataStore.setConnectedServiceFallbackEnabled(enabled) }
    }

    fun setPreferredResolverProviderId(providerId: String) {
        update { dataStore.setPreferredResolverProviderId(providerId) }
    }

    fun startFormatterQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_network_required)) }
            return
        }
        stopFormatterServer()
        formatterServer = DebridFormatterConfigServer.startOnAvailablePort(
            currentSettingsProvider = {
                val state = _uiState.value
                DebridFormatterSettings(
                    nameTemplate = state.streamNameTemplate,
                    descriptionTemplate = state.streamDescriptionTemplate,
                    streamPreferences = state.streamPreferences
                )
            },
            onSettingsChanged = { settings ->
                _uiState.update { it.copy(
                    streamNameTemplate = settings.nameTemplate,
                    streamDescriptionTemplate = settings.descriptionTemplate,
                    streamPreferences = settings.streamPreferences
                ) }
                viewModelScope.launch {
                    dataStore.setStreamTemplates(
                        nameTemplate = settings.nameTemplate,
                        descriptionTemplate = settings.descriptionTemplate
                    )
                    dataStore.setStreamPreferences(settings.streamPreferences)
                }
            },
            context = context,
            logoProvider = { logoBytes }
        )
        val server = formatterServer
        if (server == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }
        val url = server.pairingUrl(ip)
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = true,
                formatterQrCodeBitmap = QrCodeGenerator.generate(url, 512),
                formatterServerUrl = url,
                serverError = null
            )
        }
    }

    fun stopFormatterQrMode() {
        stopFormatterServer()
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = false,
                formatterQrCodeBitmap = null,
                formatterServerUrl = null
            )
        }
    }

    fun resetFormatterTemplates() {
        update { dataStore.resetStreamTemplates() }
    }

    fun setInstantPlaybackPreparationEnabled(enabled: Boolean) {
        val nextLimit = if (enabled) {
            DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT
        } else {
            0
        }
        update { dataStore.setInstantPlaybackPreparationLimit(nextLimit) }
    }

    fun setInstantPlaybackPreparationLimit(limit: Int) {
        update { dataStore.setInstantPlaybackPreparationLimit(limit) }
    }

    fun setStreamMaxResults(maxResults: Int) {
        update { dataStore.setStreamMaxResults(maxResults) }
    }

    fun setStreamSortMode(mode: DebridStreamSortMode) {
        update { dataStore.setStreamSortMode(mode) }
    }

    fun setStreamMinimumQuality(quality: DebridStreamMinimumQuality) {
        update { dataStore.setStreamMinimumQuality(quality) }
    }

    fun setStreamDolbyVisionFilter(filter: DebridStreamFeatureFilter) {
        update { dataStore.setStreamDolbyVisionFilter(filter) }
    }

    fun setStreamHdrFilter(filter: DebridStreamFeatureFilter) {
        update { dataStore.setStreamHdrFilter(filter) }
    }

    fun setStreamCodecFilter(filter: DebridStreamCodecFilter) {
        update { dataStore.setStreamCodecFilter(filter) }
    }

    fun setStreamPreferences(preferences: DebridStreamPreferences) {
        update { dataStore.setStreamPreferences(preferences) }
    }

    fun saveProviderCredential(providerId: String, value: String) {
        val ticket = authProfileGuard.capture(activeProfileId.value)
        update {
            if (authProfileGuard.valid(ticket, activeProfileId.value)) {
                dataStore.setProviderApiKeyForProfile(ticket.profileId, providerId, value.trim())
            }
        }
    }

    fun validateAndSaveTorboxApiKey(value: String, onSuccess: () -> Unit) {
        validateAndSaveProviderApiKey(DebridProviders.TORBOX_ID, value, onSuccess)
    }

    fun validateAndSaveProviderApiKey(providerId: String, value: String, onSuccess: () -> Unit) {
        manualCredentialSaver.submit(providerId, value, onSuccess)
    }

    fun cancelProviderCredentialValidation() { manualCredentialSaver.cancel() }

    private suspend fun checkProviderAccount(providerId: String, apiKey: String): DebridAccountCheck {
        return try {
            when (DebridProviders.byId(providerId)?.id) {
                DebridProviders.TORBOX_ID -> {
                    val response = torboxApi.getUser("Bearer $apiKey")
                    val body = response.body()?.use { body ->
                        if (response.isSuccessful) com.google.gson.JsonParser.parseString(body.string()).asJsonObject else null
                    }
                    response.errorBody()?.close()
                    DebridAccountCheck(if (response.isSuccessful && body?.get("success")?.asBoolean == true &&
                        body?.get("data")?.isJsonObject == true) FirstWatchAccountOutcome.ACCEPTED
                        else if (response.code() in setOf(401, 403)) FirstWatchAccountOutcome.REJECTED
                        else FirstWatchAccountOutcome.UNAVAILABLE)
                }
                DebridProviders.PREMIUMIZE_ID -> {
                    val response = premiumizeApi.accountInfo("Bearer $apiKey")
                    response.errorBody()?.close()
                    val body = response.body()
                    DebridAccountCheck(if (response.isSuccessful && body?.status.equals("success", ignoreCase = true))
                        FirstWatchAccountOutcome.ACCEPTED else if (response.code() in setOf(401, 403)) FirstWatchAccountOutcome.REJECTED
                        else FirstWatchAccountOutcome.UNAVAILABLE,
                        body?.premiumUntil?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }?.times(1_000L))
                }
                DebridProviders.REAL_DEBRID_ID, DebridProviders.ALL_DEBRID_ID ->
                    accountValidator.check(providerId, apiKey)
                else -> DebridAccountCheck(FirstWatchAccountOutcome.UNAVAILABLE)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DebridAccountCheck(FirstWatchAccountOutcome.UNAVAILABLE)
        }
    }

    suspend fun startDeviceAuthorization(providerId: String): DebridDeviceAuthorization? {
        val ticket = authProfileGuard.capture(activeProfileId.value)
        val expected = dataStore.credentialForProfile(ticket.profileId, providerId)
        val result = when (DebridProviders.byId(providerId)?.id) {
            DebridProviders.REAL_DEBRID_ID, DebridProviders.ALL_DEBRID_ID -> nativeDeviceAuth.start(providerId)
            DebridProviders.TORBOX_ID -> {
                val response = torboxApi.startDeviceAuthorization("Syclop")
                val data = response.body()?.takeIf { response.isSuccessful && it.success != false }?.data
                    ?: return null
                val deviceCode = data.deviceCode?.takeIf { it.isNotBlank() } ?: return null
                val userCode = data.code?.takeIf { it.isNotBlank() } ?: return null
                val verificationUrl = data.verificationUrl?.takeIf { it.isNotBlank() } ?: return null
                DebridDeviceAuthorization(
                    providerId = DebridProviders.TORBOX_ID,
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUrl = verificationUrl,
                    friendlyVerificationUrl = data.friendlyVerificationUrl?.takeIf { it.isNotBlank() } ?: verificationUrl,
                    intervalSeconds = data.interval?.coerceAtLeast(1) ?: 5,
                    expiresAt = data.expiresAt?.takeIf { it.isNotBlank() }
                )
            }
            DebridProviders.PREMIUMIZE_ID -> premiumizeDeviceAuth.start(premiumizeClientIdOrThrow())
            else -> null
        }
        if (!authProfileGuard.valid(ticket, activeProfileId.value)) return null
        return result?.copy(profileId = ticket.profileId, profileRevision = ticket.revision, expectedCredential = expected,
            profileSelectionRevision = ticket.selectionRevision)
    }

    fun isCurrentDeviceSession(session: DebridDeviceAuthorization): Boolean {
        val profile = session.profileId ?: return false
        val revision = session.profileRevision ?: return false
        val selection = session.profileSelectionRevision ?: return false
        return authProfileGuard.valid(DebridAuthProfileGuard.Ticket(profile, revision, selection), activeProfileId.value)
    }

    fun cancelDeviceAuthorization() { authProfileGuard.invalidate() }

    suspend fun completeDeviceAuthorization(session: DebridDeviceAuthorization,
        result: DebridDeviceAuthorizationTokenResult.Authorized): Boolean {
        if (!isCurrentDeviceSession(session) || nativeDeviceAuth.expired(session)) return false
        val checked = checkProviderAccount(session.providerId, result.accessToken)
        if (checked.outcome != FirstWatchAccountOutcome.ACCEPTED) return false
        if (!isCurrentDeviceSession(session) || nativeDeviceAuth.expired(session)) return false
        return dataStore.setProviderApiKeyForProfile(session.profileId!!, session.providerId,
            result.accessToken, session.expectedCredential, result.realDebridOAuth,
            acceptedCheck = checked, checkedAtMs = System.currentTimeMillis())
    }

    suspend fun redeemDeviceAuthorization(providerId: String, session: DebridDeviceAuthorization): DebridDeviceAuthorizationTokenResult {
        if (!isCurrentDeviceSession(session)) return DebridDeviceAuthorizationTokenResult.Failed(null)
        if (nativeDeviceAuth.expired(session)) return DebridDeviceAuthorizationTokenResult.Expired
        val normalized = session.deviceCode.trim()
        if (normalized.isBlank()) return DebridDeviceAuthorizationTokenResult.Failed(null)
        return when (DebridProviders.byId(providerId)?.id) {
            DebridProviders.REAL_DEBRID_ID, DebridProviders.ALL_DEBRID_ID -> nativeDeviceAuth.poll(session)
            DebridProviders.TORBOX_ID -> {
                val response = torboxApi.redeemDeviceAuthorization(TorboxDeviceTokenRequestDto(normalized))
                torboxDeviceAuthorizationTokenResult(response)
            }
            DebridProviders.PREMIUMIZE_ID -> premiumizeDeviceAuth.poll(premiumizeClientIdOrThrow(), session)
            else -> DebridDeviceAuthorizationTokenResult.Unsupported
        }
    }

    private fun premiumizeClientIdOrThrow(): String =
        BuildConfig.PREMIUMIZE_CLIENT_ID.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Premiumize sign-in is missing PREMIUMIZE_CLIENT_ID.")

    private fun torboxDeviceAuthorizationTokenResult(
        response: retrofit2.Response<TorboxEnvelopeDto<TorboxDeviceTokenDto>>
    ): DebridDeviceAuthorizationTokenResult {
        val envelope = response.body()
        val accessToken = envelope
            ?.takeIf { response.isSuccessful && it.success != false }
            ?.data
            ?.accessToken
            ?.takeIf { it.isNotBlank() }
        if (accessToken != null) {
            return DebridDeviceAuthorizationTokenResult.Authorized(accessToken)
        }
        val message = listOfNotNull(envelope?.error, envelope?.detail, response.errorBody()?.string())
            .joinToString(" ")
            .lowercase()
        return when {
            message.contains("pending") ||
                message.contains("not authorized") ||
                message.contains("not been used") ||
                message.contains("not used yet") ||
                message.contains("scan the code") ->
                DebridDeviceAuthorizationTokenResult.Pending
            message.contains("expired") ->
                DebridDeviceAuthorizationTokenResult.Expired
            response.code() == 404 || response.code() == 409 || response.code() == 425 ->
                DebridDeviceAuthorizationTokenResult.Pending
            response.code() == 410 ->
                DebridDeviceAuthorizationTokenResult.Expired
            else ->
                DebridDeviceAuthorizationTokenResult.Failed(null)
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private fun stopFormatterServer() {
        formatterServer?.stop()
        formatterServer = null
    }

    override fun onCleared() {
        stopFormatterServer()
        super.onCleared()
    }
}

data class DebridSettingsUiState(
    val enabled: Boolean = true,
    val cloudLibraryEnabled: Boolean = true,
    val torboxApiKey: String = "",
    val premiumizeApiKey: String = "",
    val realDebridApiKey: String = "",
    val allDebridApiKey: String = "",
    val preferredResolverProviderId: String = "",
    val connectedServiceFallbackEnabled: Boolean = true,
    val instantPlaybackPreparationLimit: Int = 0,
    val streamMaxResults: Int = 0,
    val streamSortMode: DebridStreamSortMode = DebridStreamSortMode.DEFAULT,
    val streamMinimumQuality: DebridStreamMinimumQuality = DebridStreamMinimumQuality.ANY,
    val streamDolbyVisionFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamHdrFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamCodecFilter: DebridStreamCodecFilter = DebridStreamCodecFilter.ANY,
    val streamPreferences: DebridStreamPreferences = DebridStreamPreferences(),
    val streamNameTemplate: String = "",
    val streamDescriptionTemplate: String = "",
    val isFormatterQrModeActive: Boolean = false,
    val formatterQrCodeBitmap: Bitmap? = null,
    val formatterServerUrl: String? = null,
    val serverError: String? = null,
    val cloudDeleteAfterPlayback: Boolean = false
) {
    val providerApiKeys: Map<String, String>
        get() = mapOf(
            DebridProviders.TORBOX_ID to torboxApiKey,
            DebridProviders.PREMIUMIZE_ID to premiumizeApiKey,
            DebridProviders.REAL_DEBRID_ID to realDebridApiKey,
            DebridProviders.ALL_DEBRID_ID to allDebridApiKey
        )

    val hasAnyApiKey: Boolean
        get() = DebridProviders.visible().any { provider -> apiKeyFor(provider.id).isNotBlank() }

    val resolverProviders: List<com.nuvio.tv.core.debrid.DebridProvider>
        get() = DebridProviders.visible()
            .filter { provider ->
                apiKeyFor(provider.id).isNotBlank() &&
                    (provider.supports(DebridProviderCapability.ClientResolve) ||
                        provider.supports(DebridProviderCapability.LocalTorrentResolve))
            }

    val activeResolverProvider: com.nuvio.tv.core.debrid.DebridProvider?
        get() = resolverProviders.firstOrNull { it.id == preferredResolverProviderId }
            ?: resolverProviders.firstOrNull()

    val hasResolverProvider: Boolean
        get() = activeResolverProvider != null

    val cloudLibraryProviders: List<com.nuvio.tv.core.debrid.DebridProvider>
        get() = DebridProviders.visible()
            .filter { provider ->
                apiKeyFor(provider.id).isNotBlank() &&
                    provider.supports(DebridProviderCapability.CloudLibrary)
            }

    val hasCloudLibraryProvider: Boolean
        get() = cloudLibraryProviders.isNotEmpty()

    val canResolvePlayableLinks: Boolean
        get() = hasResolverProvider

    val canUseCloudLibrary: Boolean
        get() = cloudLibraryEnabled && hasCloudLibraryProvider

    fun apiKeyFor(providerId: String): String =
        providerApiKeys[providerId].orEmpty()

    fun fromSettings(settings: DebridSettings): DebridSettingsUiState = copy(
        enabled = settings.enabled,
        cloudLibraryEnabled = settings.cloudLibraryEnabled,
        cloudDeleteAfterPlayback = settings.cloudDeleteAfterPlayback,
        torboxApiKey = settings.torboxApiKey,
        premiumizeApiKey = settings.premiumizeApiKey,
        realDebridApiKey = settings.realDebridApiKey,
        allDebridApiKey = settings.allDebridApiKey,
        preferredResolverProviderId = settings.preferredResolverProviderId,
        connectedServiceFallbackEnabled = settings.connectedServiceFallbackEnabled,
        instantPlaybackPreparationLimit = settings.instantPlaybackPreparationLimit,
        streamMaxResults = settings.streamMaxResults,
        streamSortMode = settings.streamSortMode,
        streamMinimumQuality = settings.streamMinimumQuality,
        streamDolbyVisionFilter = settings.streamDolbyVisionFilter,
        streamHdrFilter = settings.streamHdrFilter,
        streamCodecFilter = settings.streamCodecFilter,
        streamPreferences = settings.streamPreferences,
        streamNameTemplate = settings.streamNameTemplate,
        streamDescriptionTemplate = settings.streamDescriptionTemplate
    )

    override fun toString(): String = "DebridSettingsUiState(enabled=$enabled, credentials=[redacted])"
}
