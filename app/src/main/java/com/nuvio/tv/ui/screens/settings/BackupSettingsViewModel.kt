package com.nuvio.tv.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.backup.LocalBackupFile
import com.nuvio.tv.core.backup.LocalBackupManager
import com.nuvio.tv.core.backup.PortableBackup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupSettingsUiState(
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val files: List<LocalBackupFile> = emptyList(),
    val preview: PortableBackup? = null,
    val pending: Boolean = false,
    val canUndo: Boolean = false
)

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(private val manager: LocalBackupManager) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupSettingsUiState())
    val uiState = mutableState.asStateFlow()
    fun suggestedName() = manager.fileName()
    fun open() = execute { refresh(); mutableState.update { it.copy(notice = manager.notice()) } }
    fun pickerUnavailable() { mutableState.update { it.copy(error = "The TV has no local document picker. Use Save on this TV or choose one of the listed local backups.") } }
    fun export(uri: Uri? = null) = execute {
        val notice = manager.export(uri); refresh(); mutableState.update { it.copy(notice = notice) }
    }
    fun inspect(uri: Uri) = execute { val backup = manager.inspect(uri); mutableState.update { it.copy(preview = backup) } }
    fun dismissPreview() { mutableState.update { it.copy(preview = null) } }
    fun confirmRestore() = execute {
        val backup = requireNotNull(uiState.value.preview)
        manager.queue(backup); mutableState.update { it.copy(preview = null, pending = true,
            notice = "Restore is queued for the next cold app start. Close Syclop with the button below, then reopen it. You can cancel before closing.") }
    }
    fun queueUndo() = execute {
        manager.queue(null); mutableState.update { it.copy(pending = true,
            notice = "Undo is queued. Close Syclop below, then reopen it to recover the previous private snapshot, including its account connections.") }
    }
    fun cancel() = execute { manager.cancel(); refresh(); mutableState.update { it.copy(notice = "Queued restore cancelled. No app data was changed.") } }
    private suspend fun refresh() { val files = manager.localFiles(); mutableState.update { it.copy(files = files, pending = manager.pending(), canUndo = manager.canUndo()) } }
    private fun execute(work: suspend () -> Unit) {
        if (uiState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { work() } catch (failure: Exception) {
                mutableState.update { it.copy(error = "The local backup operation could not complete. Use a valid Syclop backup on this TV or attached storage. Backup and restore require profile PIN locks to be off and enough free space for a private Undo snapshot. No queued restore was applied while the app is open.") }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
