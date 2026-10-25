package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.runtime.AppRestarter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppRestartViewModel @Inject constructor(
    private val restarter: AppRestarter
) : ViewModel() {
    fun restart(): Boolean = restarter.restartActivityTask()
}
