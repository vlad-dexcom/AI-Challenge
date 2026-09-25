package com.example.geminichat.agent.workout

import android.content.Context
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
    }
}
