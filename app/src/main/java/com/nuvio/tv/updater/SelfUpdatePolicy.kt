package com.nuvio.tv.updater

import android.app.job.JobScheduler
import android.content.Context
import com.nuvio.tv.BuildConfig

/** Build policy, not a saved preference. Provider/channel jobs have separate owners and IDs. */
object SelfUpdatePolicy {
    val enabled: Boolean = BuildConfig.FEATURE_IN_APP_UPDATES_ENABLED
    internal val jobIds = setOf(10_020, 10_021, 10_022)

    /** Also retires persisted jobs left by an older APK. Never cancels unrelated background work. */
    fun cancelIfDisabled(context: Context): Boolean {
        if (enabled) return false
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        jobIds.forEach { scheduler?.cancel(it) }
        return true
    }
}
