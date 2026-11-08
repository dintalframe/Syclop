package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.runtime.AppCloser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppShutdownViewModel @Inject constructor(
    private val closer: AppCloser
) : ViewModel() {
    fun shutdown(activity: Activity): Boolean = closer.close(activity)
}
