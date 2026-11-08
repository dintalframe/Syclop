package com.nuvio.tv.updater

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
            _uiState.update { it.copy(isChecking = true, showBanner = false, feedbackMessage = null, errorMessage = null) }
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
        downloadJob?.cancel()
        _uiState.update {
            it.copy(showBanner = false, showUnknownSourcesDialog = false, isDownloading = false,
                downloadProgress = null, errorMessage = null)
        }
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
        _uiState.update { it.copy(updateBannerEnabled = enabled, showBanner = it.showBanner && enabled) }
        viewModelScope.launch {
            savePreferences { updatePreferences.setUpdateBannerEnabled(enabled) }
            if (enabled) checkForUpdatesAutomatically()
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        if (!SelfUpdatePolicy.enabled || _uiState.value.updateChannel == channel) return
        _uiState.update { it.copy(updateChannel = channel, update = null, showBanner = false) }
        viewModelScope.launch {
            savePreferences { updatePreferences.setUpdateChannel(channel) }
            if (!BuildConfig.IS_DEBUG_BUILD) checkForUpdates(force = true, showNoUpdateFeedback = true)
        }
    }

    fun downloadUpdate(allowInstaller: () -> Boolean) {
        val state = _uiState.value
        if (!SelfUpdatePolicy.enabled || !state.showBanner || state.isDownloading) return
        val update = state.update ?: return
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(showUnknownSourcesDialog = true) }
            return
        }
        _uiState.update { it.copy(isDownloading = true, downloadProgress = null, errorMessage = null) }
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
            val launchInstaller = result.isSuccess && allowInstaller()
            val error = when {
                result.isFailure -> context.getString(R.string.update_error_download_failed)
                launchInstaller -> result.mapCatching { ApkInstaller.launchSystemInstaller(context, it) }
                    .exceptionOrNull()?.let { context.getString(R.string.update_error_install_failed) }
                else -> null
            }
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    downloadProgress = null,
                    showBanner = !launchInstaller || error != null,
                    errorMessage = error
                )
            }
        }
    }

    fun openUnknownSourcesSettings() {
        if (!SelfUpdatePolicy.enabled || !_uiState.value.showUnknownSourcesDialog) return
        dismissUnknownSourcesDialog()
        runCatching { ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let(context::startActivity) }
            .onFailure { _uiState.update { it.copy(errorMessage = context.getString(R.string.update_error_install_failed)) } }
    }

    private suspend fun savePreferences(action: suspend () -> Unit) {
        try {
            action()
        } catch (_: IOException) {
            // A storage failure must not interrupt the current session.
        }
    }
}
