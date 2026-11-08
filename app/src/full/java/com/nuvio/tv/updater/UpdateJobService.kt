package com.nuvio.tv.updater

import android.app.job.JobParameters
import android.app.job.JobService

/** Retires jobs persisted by older builds before they can do any work. */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        SelfUpdatePolicy.cancelLegacyJobs(this)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
