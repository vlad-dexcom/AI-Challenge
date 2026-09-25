package com.example.geminichat.agent.workout

import java.util.concurrent.TimeUnit

/**
 * Day 18: pure aggregation logic behind [WorkoutDigestWorker] — kept separate from anything
 * Android/WorkManager-specific so it's a plain, fast JVM unit (see
 * `WorkoutDigestAggregatorTest`), the same split [com.example.geminichat.agent.task.TaskStateMachine]
 * uses to keep business logic testable independent of its Android callers.
 */
object WorkoutDigestAggregator {

    /** Default trailing window a summary covers, in days. */
    const val DEFAULT_PERIOD_DAYS = 7

    /**
     * Aggregates [logs] into a [WorkoutSummary] covering the [periodDays] trailing [nowEpochMillis].
     * Entries older than the window are ignored entirely (not just excluded from totals) so a
     * summary always reflects "recent activity", not the whole lifetime log.
     */
    fun aggregate(
        logs: List<WorkoutLogEntry>,
        nowEpochMillis: Long,
        periodDays: Int = DEFAULT_PERIOD_DAYS
    ): WorkoutSummary {
        val windowStart = nowEpochMillis - TimeUnit.DAYS.toMillis(periodDays.toLong())
        val inWindow = logs.filter { it.loggedAtEpochMillis in windowStart..nowEpochMillis }

        val workoutsByGoal = inWindow.groupingBy { it.goal }.eachCount()
        val minutesByGoal = inWindow
            .groupBy { it.goal }
            .mapValues { (_, entries) -> entries.sumOf { it.minutes } }

        return WorkoutSummary(
            generatedAtEpochMillis = nowEpochMillis,
            periodDays = periodDays,
            totalWorkouts = inWindow.size,
            totalMinutes = inWindow.sumOf { it.minutes },
            workoutsByGoal = workoutsByGoal,
            minutesByGoal = minutesByGoal
        )
    }
}
