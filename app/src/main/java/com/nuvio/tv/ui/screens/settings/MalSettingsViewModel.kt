package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.mal.MalAccountState
import com.nuvio.tv.data.mal.MalAnime
import com.nuvio.tv.data.mal.MalAuthRepository
import com.nuvio.tv.data.mal.MalAuthorizationSession
import com.nuvio.tv.data.mal.MalLibraryState
import com.nuvio.tv.data.mal.MalListStatus
import com.nuvio.tv.data.mal.MalListEdit
import com.nuvio.tv.data.mal.MalRepository
import com.nuvio.tv.data.mal.MalScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MalSettingsUiState(
    val account: MalAccountState = MalAccountState(),
    val session: MalAuthorizationSession? = null,
    val library: MalLibraryState = MalLibraryState(),
    val busy: Boolean = false,
    val searchResults: List<MalAnime>? = null,
    val notice: String? = null,
    val error: String? = null
)

private data class MalLocalUiState(val busy: Boolean = false, val results: List<MalAnime>? = null, val error: String? = null)

@HiltViewModel
class MalSettingsViewModel @Inject constructor(
    private val auth: MalAuthRepository,
    private val repository: MalRepository
) : ViewModel() {
    private val local = MutableStateFlow(MalLocalUiState())
    val uiState = combine(auth.state, auth.session, repository.state, auth.notice, local) { account, session, library, notice, extra ->
        MalSettingsUiState(account, session,
            if (library.scope == account.scope) library else MalLibraryState(scope = account.scope),
            extra.busy, extra.results, notice, extra.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MalSettingsUiState(account = auth.state.value))

    init {
        viewModelScope.launch {
            auth.state.map { it.scope }.distinctUntilChanged().collect { local.value = MalLocalUiState() }
        }
    }

    fun suggestedRedirect(): String = auth.defaultRedirectUri().orEmpty()
    fun open() { if (auth.state.value.connected) refresh() }
    fun close() { auth.cancel() }
    fun connect() = runAction { auth.connect(); Unit }
    fun cancelConnection() { auth.cancel() }
    fun signOut() { auth.signOut(); local.value = MalLocalUiState() }
    fun setAutomaticProgress(enabled: Boolean) = runAction { auth.store.automaticProgress(enabled) }
    fun configure(clientId: String, redirect: String) = runAction { auth.saveConfiguration(clientId, redirect) }
    fun submitCallback(value: String) = runAction { auth.submitCallback(value) }
    fun refresh() = runAction { repository.refresh() }
    fun search(query: String) = runAction {
        if (query.isBlank()) local.value = local.value.copy(results = null)
        else {
            val expected = auth.state.value.scope
            val results = repository.search(query)
            if (auth.store.isCurrent(expected)) local.value = local.value.copy(results = results)
        }
    }
    fun update(item: MalAnime, edit: MalListEdit, expected: MalScope, complete: () -> Unit) = runAction {
        require(auth.store.isCurrent(expected)) { "The active profile changed. Reopen the anime entry." }
        repository.update(item, edit, expected)
        if (auth.store.isCurrent(expected)) {
            local.value = local.value.copy(results = null)
            complete()
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (local.value.busy) return
        val expected = auth.state.value.scope
        local.value = local.value.copy(busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) {
                if (auth.store.isCurrent(expected)) local.value = local.value.copy(error = "Review the MyAnimeList application settings or returned callback, then try again.")
            }
            catch (_: Exception) {
                if (auth.store.isCurrent(expected)) local.value = local.value.copy(error = "MyAnimeList could not complete this request. Try again.")
            }
            finally { if (auth.store.isCurrent(expected)) local.value = local.value.copy(busy = false) }
        }
    }
}
