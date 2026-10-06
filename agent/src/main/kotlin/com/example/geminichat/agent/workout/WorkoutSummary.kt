package com.example.geminichat.agent.workout

import kotlinx.serialization.Serializable

/**
 * Day 18: the aggregated result [WorkoutDigestWorker] computes periodically and
 * [WorkoutSummaryStore] persists — this is what the `get_workout_summary` tool (see
 * [com.example.geminichat.mcp.LocalWorkoutMcpGateway]) returns to the agent. It is *read*, never
 * recomputed, on every tool call: the aggregation cost is paid once per scheduled run, not once
 * per chat turn.
 */
@Serializable
data class WorkoutSummary(
    val generatedAtEpochMillis: Long,
    /** Trailing window this summary covers, e.g. 7 for "last 7 days". */
    val periodDays: Int,
    val totalWorkouts: Int,
    val totalMinutes: Int,
    /** Workout count per goal/muscle-group label, e.g. {"legs": 3, "cardio": 2}. */
    val workoutsByGoal: Map<String, Int>,
    /** Minutes spent per goal/muscle-group label. */
    val minutesByGoal: Map<String, Int>
)
