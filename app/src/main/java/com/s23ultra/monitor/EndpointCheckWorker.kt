package com.s23ultra.monitor

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Battery-efficient endpoint health checks via WorkManager (JobScheduler-backed).
 *
 * Runs every 15 minutes — the minimum WorkManager interval. The system coalesces
 * this job with other scheduled work, respects Doze mode, and avoids waking the
 * radio outside of already-active maintenance windows. This replaces the previous
 * coroutine loop which ran every 5 minutes regardless of device state.
 *
 * Requires network connectivity — WorkManager will defer automatically when offline.
 */
class EndpointCheckWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val client           = DatadogClient(ctx)
            val withTransitions  = EndpointChecker.checkWithTransitions()
            val results          = withTransitions.map { it.first }

            MonitoringService.lastEndpointResults = results
            MonitoringService.lastEndpointCheckMs = System.currentTimeMillis()

            client.sendEndpointResults(results, withTransitions)
            DiagLogger.log(ctx, DiagLogger.Level.INFO,
                "EndpointCheckWorker: ${results.count { it.isUp }}/${results.size} up")

            Result.success()
        } catch (e: Exception) {
            DiagLogger.log(ctx, DiagLogger.Level.WARN,
                "EndpointCheckWorker failed: ${e.message?.take(100)}")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "springshot_endpoint_checks"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<EndpointCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
