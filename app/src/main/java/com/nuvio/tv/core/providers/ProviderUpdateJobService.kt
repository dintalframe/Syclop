package com.nuvio.tv.core.providers

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProviderUpdateJobService: JobService() {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies { fun providerUpdates(): ProviderUpdateCoordinator }
    private class Task(val scope: CoroutineScope, val stopped: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean())
    private val tasks = java.util.concurrent.ConcurrentHashMap<Int, Task>()
    override fun onStartJob(params: JobParameters): Boolean {
        val coordinator = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).providerUpdates()
        val task = Task(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        tasks.put(params.jobId, task)?.let { it.stopped.set(true);it.scope.cancel() }
        task.scope.launch {
            var remainingDue = false
            try { remainingDue = coordinator.updateAll(periodic = params.jobId == PERIODIC) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Existing status/last valid configuration are retained. */ }
            finally {
                if (tasks.remove(params.jobId, task) && !task.stopped.get()) jobFinished(params, remainingDue)
                task.scope.cancel()
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.let { it.stopped.set(true);it.scope.cancel() }
        return true
    }
    override fun onDestroy() {
        tasks.values.forEach { it.stopped.set(true);it.scope.cancel() };tasks.clear();super.onDestroy()
    }
    companion object {
        private const val PERIODIC = 10_040
        private const val CATCH_UP = 10_041
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, ProviderUpdateJobService::class.java)
            if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(ProviderUpdatePolicy.INTERVAL_MS, ProviderUpdatePolicy.PERIODIC_FLEX_MS).setPersisted(true).build())
            // One app-start opportunity, not polling. The persisted per-provider clock skips fresh data.
            if (scheduler.getPendingJob(CATCH_UP) == null) scheduler.schedule(JobInfo.Builder(CATCH_UP, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(15_000L).build())
        }
    }
}
