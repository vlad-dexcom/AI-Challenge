package com.example.geminichat.agent.workout

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Day 18: [WorkoutDigestAggregator] pure aggregation logic, isolated from anything
 * Android/WorkManager-specific. */
class WorkoutDigestAggregatorTest {

    private val now = 1_700_000_000_000L // arbitrary fixed instant

    @Test
    fun `empty log yields an all-zero summary`() {
        val summary = WorkoutDigestAggregator.aggregate(emptyList(), now)

        assertEquals(now, summary.generatedAtEpochMillis)
        assertEquals(WorkoutDigestAggregator.DEFAULT_PERIOD_DAYS, summary.periodDays)
        assertEquals(0, summary.totalWorkouts)
        assertEquals(0, summary.totalMinutes)
        assertEquals(emptyMap<String, Int>(), summary.workoutsByGoal)
        assertEquals(emptyMap<String, Int>(), summary.minutesByGoal)
    }

    @Test
    fun `sums minutes and counts per goal within the window`() {
        val logs = listOf(
            entry(goal = "legs", minutes = 30, daysAgo = 1),
            entry(goal = "legs", minutes = 45, daysAgo = 2),
            entry(goal = "cardio", minutes = 20, daysAgo = 3)
        )

        val summary = WorkoutDigestAggregator.aggregate(logs, now, periodDays = 7)

        assertEquals(3, summary.totalWorkouts)
        assertEquals(95, summary.totalMinutes)
        assertEquals(mapOf("legs" to 2, "cardio" to 1), summary.workoutsByGoal)
        assertEquals(mapOf("legs" to 75, "cardio" to 20), summary.minutesByGoal)
    }

    @Test
    fun `entries older than the window are ignored entirely`() {
        val logs = listOf(
            entry(goal = "legs", minutes = 30, daysAgo = 1),
            entry(goal = "legs", minutes = 999, daysAgo = 30)
        )

        val summary = WorkoutDigestAggregator.aggregate(logs, now, periodDays = 7)

        assertEquals(1, summary.totalWorkouts)
        assertEquals(30, summary.totalMinutes)
        assertEquals(mapOf("legs" to 1), summary.workoutsByGoal)
    }

    private fun entry(goal: String, minutes: Int, daysAgo: Long) = WorkoutLogEntry(
        id = "$goal-$daysAgo",
        goal = goal,
        minutes = minutes,
        loggedAtEpochMillis = now - TimeUnit.DAYS.toMillis(daysAgo)
    )
}
