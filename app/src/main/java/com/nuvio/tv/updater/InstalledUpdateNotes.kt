package com.nuvio.tv.updater

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AppOnboardingDataStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class InstalledUpdateNotes(val version: String, val notes: String)

internal data class InstalledUpdateNotesState(val isLoaded: Boolean = false, val notes: InstalledUpdateNotes? = null)

@Composable
internal fun rememberInstalledUpdateNotes(onboarding: AppOnboardingDataStore): State<InstalledUpdateNotesState> {
    val context = LocalContext.current.applicationContext
    return produceState(InstalledUpdateNotesState(), context, onboarding) {
        try {
            @Suppress("DEPRECATION")
            val installed = withContext(Dispatchers.IO) { context.packageManager.getPackageInfo(context.packageName, 0) }
            val acknowledged = onboarding.lastAcknowledgedUpdateVersionCode.first()
            if (acknowledged == null && installed.firstInstallTime > 0 && installed.firstInstallTime == installed.lastUpdateTime) {
                onboarding.acknowledgeUpdateVersionCode(BuildConfig.VERSION_CODE)
                value = InstalledUpdateNotesState(isLoaded = true)
                return@produceState
            }
            val bundle = withContext(Dispatchers.IO) {
                InstalledUpdateNotes(
                    context.assets.open("release-notes.version").bufferedReader().use { it.readText().trim() },
                    context.assets.open("release-notes.md").bufferedReader().use { it.readText() }
                )
            }
            if (bundle.notes.isBlank()) {
                value = InstalledUpdateNotesState(isLoaded = true)
                return@produceState
            }
            onboarding.lastAcknowledgedUpdateVersionCode.collect { versionCode ->
                value = InstalledUpdateNotesState(isLoaded = true, notes = bundle.takeIf {
                    InstalledUpdateNotesPolicy.shouldShow(BuildConfig.VERSION_NAME, it.version,
                        BuildConfig.VERSION_CODE, versionCode, installed.firstInstallTime, installed.lastUpdateTime)
                })
            }
        } catch (_: IOException) {
            // Optional notes must not block startup when local storage is unavailable.
            value = InstalledUpdateNotesState(isLoaded = true)
        } catch (_: PackageManager.NameNotFoundException) {
            // Without install identity, leave the current viewing session alone.
            value = InstalledUpdateNotesState(isLoaded = true)
        }
    }
}
