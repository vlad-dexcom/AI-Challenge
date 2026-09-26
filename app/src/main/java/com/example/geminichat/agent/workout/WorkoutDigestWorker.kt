package com.example.geminichat.agent.workout

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Day 18: the "фоновый сбор данных" half of the exercise — a [CoroutineWorker], run periodically
 * by WorkManager (see [WorkoutDigestScheduler]), that reads every logged workout (written by the
 * `log_workout` tool via [WorkoutLogStore]) and re-aggregates them into the single latest
 * [WorkoutSummary] (via [WorkoutDigestAggregator]), overwriting whatever [WorkoutSummaryStore]
 * held before. The `get_workout_summary` tool (see
 * [com.example.geminichat.mcp.LocalWorkoutMcpGateway]) only ever reads that saved summary, so the
 * aggregation cost is paid here, once per scheduled run, not on every chat turn.
 *
 * Uses [Context.getFilesDir] directly (the same directory [com.example.geminichat.MainActivity]
 * points every other `*Store` at) rather than requiring the caller to inject file paths, since
 * WorkManager itself constructs this class via reflection — there is no constructor injection
 * point to use here the way [com.example.geminichat.ChatViewModel] does for its stores.
 */
class WorkoutDigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "doWork started (runAttemptCount=$runAttemptCount)")
        try {
            val logStore = WorkoutLogStore(File(applicationContext.filesDir, WorkoutLogStore.FILE_NAME))
            val summaryStore =
                WorkoutSummaryStore(File(applicationContext.filesDir, WorkoutSummaryStore.FILE_NAME))

            val logs = logStore.loadAll()
            val summary = WorkoutDigestAggregator.aggregate(
                logs = logs,
                nowEpochMillis = System.currentTimeMillis()
            )
            summaryStore.save(summary)
            Log.i(
                TAG,
                "doWork succeeded: ${logs.size} logged workout(s) read, " +
                    "summary=totalWorkouts=${summary.totalWorkouts} totalMinutes=${summary.totalMinutes}"
            )
            Result.success()
        } catch (e: Exception) {
            // Transient failure (e.g. disk I/O) — let WorkManager retry on its own schedule
            // rather than surfacing a crash; the previous summary just stays stale until then.
            Log.e(TAG, "doWork failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WorkoutDigestWorker"
    }
}

