package com.example.geminichat.agent.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.geminichat.BuildConfig

/**
 * Day 18 debug/QA aid only: lets a manual tester force an immediate run of
 * [WorkoutDigestWorker] instead of waiting out the real periodic schedule (15 minutes minimum)
 * or fighting `adb shell cmd jobscheduler run -f` (unreliable across Android/emulator versions —
 * it targets the *system* JobScheduler job, which may not correspond 1:1 with WorkManager's own
 * bookkeeping, or may silently no-op if the job's internal state doesn't line up).
 *
 * This sidesteps JobScheduler entirely: it just enqueues an unconstrained one-time
 * [androidx.work.OneTimeWorkRequest] for the exact same worker, which WorkManager's own executor
 * runs almost immediately regardless of any periodic job's state. See
 * `docs/day18-scheduled-mcp-tool-test-scenario.md` for the `adb shell am broadcast` command that
 * triggers this.
 *
 * Guarded by [BuildConfig.DEBUG] so it is a no-op in release builds even though the manifest
 * entry itself must be `exported` for `adb shell am broadcast` to reach it.
 */
class WorkoutDigestDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "Force-enqueuing an immediate WorkoutDigestWorker run (debug build only).")
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WorkoutDigestWorker>().build()
        )
    }

    companion object {
        const val ACTION_RUN_NOW = "com.example.geminichat.RUN_WORKOUT_DIGEST_NOW"
        private const val TAG = "WorkoutDigestDebugRx"
    }
}
