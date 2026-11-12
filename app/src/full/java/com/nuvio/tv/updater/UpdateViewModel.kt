package com.nuvio.tv.updater

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val pendingInstall: File? = null,
    val showBanner: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
    val updateBannerEnabled: Boolean = SelfUpdatePolicy.enabled,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val manualPrompt: Boolean = false
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val downloadStore: UpdateDownloadStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private var updateCheckJob: Job? = null
    private var downloadJob: Job? = null
    private var awaitingUnknownSourcesSettings = false

    private val preferencesInitialization = if (SelfUpdatePolicy.enabled) {
        viewModelScope.launch {
            val enabled = updatePreferences.updateBannerEnabled.first()
            val channel = updatePreferences.updateChannel.first()
            _uiState.update { it.copy(updateBannerEnabled = enabled, updateChannel = channel) }
            savePreferences { withContext(Dispatchers.IO) { downloadStore.clearIfInstalled() } }
        }
    } else null

    suspend fun checkForUpdatesAutomatically() {
        if (!SelfUpdatePolicy.enabled || BuildConfig.IS_DEBUG_BUILD) return
        preferencesInitialization?.join()
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        val state = _uiState.value
        if (!SelfUpdatePolicy.enabled || state.isDownloading) return
        if (!force && (!state.updateBannerEnabled || state.showBanner || updateCheckJob?.isActive == true)) return
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (!force && !UpdateBannerPolicy.canCheckAutomatically(now, updatePreferences.lastCheckAtMs.first())) return@launch
            cancelInstallAttempt()
            _uiState.update { it.copy(isChecking = true, showBanner = false, feedbackMessage = null) }
            val channel = _uiState.value.updateChannel
            val result = updateRepository.getLatestUpdate(channel)
            savePreferences { updatePreferences.setLastCheckAtMs(now) }
            val update = result.getOrNull()?.takeIf { VersionUtils.isRemoteNewer(it.tag, BuildConfig.VERSION_NAME) }
            val deferred = updatePreferences.deferredRelease.first()
            _uiState.update { current ->
                val shouldShow = update != null && UpdateBannerPolicy.shouldShow(
                    isRemoteNewer = true,
                    force = force,
                    bannerEnabled = current.updateBannerEnabled,
                    dismissedTag = deferred?.first,
                    updateTag = update.tag,
                    deferredUntilMs = deferred?.second ?: 0L,
                    nowMs = now
                )
                current.copy(
                    isChecking = false,
                    update = update,
                    showBanner = shouldShow,
                    manualPrompt = force && showNoUpdateFeedback,
                    showUnknownSourcesDialog = false,
                    feedbackMessage = if (showNoUpdateFeedback && update == null) {
                        when {
                            result.isSuccess -> noUpdateFeedback(channel)
                            result.exceptionOrNull() is NoEligibleUpdateException -> context.getString(R.string.update_no_compatible_release)
                            else -> context.getString(R.string.update_error_check_failed)
                        }
                    } else null
                )
            }
        }
    }

    private fun noUpdateFeedback(channel: UpdateChannel): String = context.getString(
        if (channel == UpdateChannel.STABLE && VersionUtils.isPrerelease(BuildConfig.VERSION_NAME)) R.string.update_waiting_for_stable
        else R.string.update_latest_version
    )

    fun dismissBanner() {
        val state = _uiState.value
        cancelInstallAttempt()
        _uiState.update { it.copy(showBanner = false) }
        state.update?.tag?.let { tag ->
            viewModelScope.launch {
                savePreferences { updatePreferences.deferRelease(tag, System.currentTimeMillis() + UpdateBannerPolicy.DEFER_MS) }
            }
        }
    }

    fun dismissUnknownSourcesDialog() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
    }

    fun consumeFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    fun setUpdateBannerEnabled(enabled: Boolean) {
        if (!SelfUpdatePolicy.enabled) return
        if (!enabled) cancelInstallAttempt()
        _uiState.update { it.copy(updateBannerEnabled = enabled, showBanner = it.showBanner && enabled) }
        viewModelScope.launch {
            savePreferences { updatePreferences.setUpdateBannerEnabled(enabled) }
            if (enabled) checkForUpdatesAutomatically()
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        if (!SelfUpdatePolicy.enabled || _uiState.value.updateChannel == channel) return
        cancelInstallAttempt()
        _uiState.update { it.copy(updateChannel = channel, update = null, showBanner = false) }
        viewModelScope.launch {
            savePreferences { updatePreferences.setUpdateChannel(channel) }
            if (!BuildConfig.IS_DEBUG_BUILD) checkForUpdates(force = true, showNoUpdateFeedback = true)
        }
    }

    fun downloadUpdate() {
        val state = _uiState.value
        if (!SelfUpdatePolicy.enabled || !state.showBanner || state.isDownloading ||
            state.pendingInstall != null || awaitingUnknownSourcesSettings
        ) return
        val update = state.update ?: return
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(showUnknownSourcesDialog = true) }
            return
        }
        _uiState.update {
            it.copy(isDownloading = true, downloadProgress = null, showUnknownSourcesDialog = false, errorMessage = null)
        }
        downloadJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    downloadStore.download(update) { downloaded, total ->
                        val progress = total?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }
                        _uiState.update { if (it.isDownloading) it.copy(downloadProgress = progress) else it }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    downloadProgress = null,
                    pendingInstall = result.getOrNull(),
                    errorMessage = if (result.isFailure) context.getString(R.string.update_error_download_failed) else null
                )
            }
        }
    }

    fun launchPendingInstaller(activity: ComponentActivity) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val file = _uiState.value.pendingInstall ?: return
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(pendingInstall = null, showUnknownSourcesDialog = true) }
            return
        }
        _uiState.update { it.copy(pendingInstall = null, showBanner = false) }
        runCatching { ApkInstaller.launchSystemInstaller(activity, file) }
            .onFailure {
                _uiState.update { it.copy(showBanner = true, errorMessage = context.getString(R.string.update_error_install_failed)) }
            }
    }

    fun openUnknownSourcesSettings(launch: (Intent) -> Unit) {
        if (!SelfUpdatePolicy.enabled || !_uiState.value.showUnknownSourcesDialog) return
        val intent = ApkInstaller.buildUnknownSourcesSettingsIntent(context) ?: return
        awaitingUnknownSourcesSettings = true
        dismissUnknownSourcesDialog()
        runCatching { launch(intent) }.onFailure {
            awaitingUnknownSourcesSettings = false
            _uiState.update { it.copy(errorMessage = context.getString(R.string.update_error_install_failed)) }
        }
    }

    fun onUnknownSourcesSettingsReturned() {
        if (!awaitingUnknownSourcesSettings) return
        awaitingUnknownSourcesSettings = false
        if (ApkInstaller.canRequestPackageInstalls(context)) downloadUpdate()
    }

    private fun cancelInstallAttempt() {
        downloadJob?.cancel()
        awaitingUnknownSourcesSettings = false
        _uiState.update {
            it.copy(
                isDownloading = false, downloadProgress = null, pendingInstall = null,
                showUnknownSourcesDialog = false, errorMessage = null
            )
        }
    }

    private suspend fun savePreferences(action: suspend () -> Unit) {
        try {
            action()
        } catch (_: IOException) {
            // A storage failure must not interrupt the current session.
        }
    }
}
