package com.nuvio.tv.updater

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Context
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Test

class SelfUpdateDisabledPolicyTest {
    @Test
    fun `retires only the old updater jobs when the app opens`() {
        val scheduler = mockk<JobScheduler>(relaxed = true)
        val context = mockk<Context> {
            every { getSystemService(Context.JOB_SCHEDULER_SERVICE) } returns scheduler
        }

        SelfUpdatePolicy.cancelLegacyJobs(context)

        verify(exactly = 1) { scheduler.cancel(10_020); scheduler.cancel(10_021); scheduler.cancel(10_022) }
        confirmVerified(scheduler)
    }

    @Test
    fun `persisted background updater cannot start work or request a retry`() {
        val scheduler = mockk<JobScheduler>(relaxed = true)
        val service = spyk(UpdateJobService())
        every { service.getSystemService(Context.JOB_SCHEDULER_SERVICE) } returns scheduler
        val params = mockk<JobParameters>()

        assertFalse(service.onStartJob(params))
        assertFalse(service.onStopJob(params))

        verify(exactly = 1) { scheduler.cancel(10_020); scheduler.cancel(10_021); scheduler.cancel(10_022) }
        verify(exactly = 0) { service.applicationContext }
        confirmVerified(scheduler)
        confirmVerified(params)
    }
}
