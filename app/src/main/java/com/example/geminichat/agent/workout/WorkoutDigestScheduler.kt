package com.example.geminichat.agent.workout

import android.app.job.JobScheduler
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Day 18: registers [WorkoutDigestWorker] as periodic background work — called once from
 * [com.example.geminichat.MainActivity.onCreate]. Uses `enqueueUniquePeriodicWork` with
 * [ExistingPeriodicWorkPolicy.KEEP] so re-running `onCreate` (e.g. a config change or a second
 * launch) never schedules a duplicate periodic job; WorkManager itself persists the schedule
 * across app/process restarts and even device reboots, which is what makes this "24/7" without
 * a dedicated always-on server.
 */
object WorkoutDigestScheduler {

    const val UNIQUE_WORK_NAME = "workout-digest"
    private const val TAG = "WorkoutDigestScheduler"

    /**
     * [repeatIntervalMinutes] defaults to WorkManager's own minimum for periodic work (15
     * minutes) — Android does not allow shorter periodic intervals, regardless of what's
     * requested.
     */
    fun schedule(context: Context, repeatIntervalMinutes: Long = 15) {
        val request = PeriodicWorkRequestBuilder<WorkoutDigestWorker>(
            repeatIntervalMinutes,
            TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        logScheduledJobId(context.applicationContext)
    }

    /**
     * Debug/QA aid (see `docs/day18-scheduled-mcp-tool-test-scenario.md`): logs the underlying
     * `JobScheduler` job id WorkManager assigned to this periodic work, so it can be force-run
     * immediately (`adb shell cmd jobscheduler run -f <package> <job-id>`) instead of waiting
     * out the real 15-minute schedule. Looked up via the public [JobScheduler.getAllPendingJobs]
     * API (a short delay after enqueueing, since WorkManager schedules the underlying job on a
     * background thread) rather than any WorkManager-internal API, which isn't public.
     */
    private fun logScheduledJobId(appContext: Context) {
        Handler(Looper.getMainLooper()).postDelayed({
            val jobScheduler =
                appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobId = jobScheduler.allPendingJobs
                .firstOrNull { it.service.packageName == appContext.packageName }
                ?.id
            if (jobId != null) {
                Log.i(
                    TAG,
                    "Workout digest job id=$jobId - run it now with: " +
                        "adb shell cmd jobscheduler run -f ${appContext.packageName} $jobId"
                )
            } else {
                Log.w(TAG, "Workout digest job not visible in JobScheduler yet; try again shortly.")
            }
        }, JOB_LOG_DELAY_MILLIS)
    }

    private const val JOB_LOG_DELAY_MILLIS = 1_000L
}

