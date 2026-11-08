package com.nuvio.tv.core.runtime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import com.nuvio.tv.core.initialization.StartupInitializationCoordinator
import com.nuvio.tv.core.player.ExternalPlaybackTracker
import javax.inject.Inject
import javax.inject.Singleton

/** Closes Syclop's activity task through the normal Android lifecycle. */
@Singleton
class AppCloser @Inject constructor(
    private val initialization: StartupInitializationCoordinator,
    private val externalPlaybackTracker: ExternalPlaybackTracker
) {
    /**
     * Returns to the device Home without killing the process or changing saved app data.
     * A missing or unavailable Home leaves Syclop open so the caller can report the failure.
     */
    fun close(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false

        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(homeIntent)
        } catch (_: ActivityNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        }

        initialization.prepareForAppRestart()
        externalPlaybackTracker.prepareForAppTaskExit()
        activity.finishAffinity()
        activity.finishAndRemoveTask()
        return true
    }
}
