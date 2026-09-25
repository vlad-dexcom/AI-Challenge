package com.example.geminichat.agent.workout

import kotlinx.serialization.Serializable

/**
 * Day 18: one logged workout, recorded by the `log_workout` tool (see
 * [com.example.geminichat.mcp.LocalWorkoutMcpGateway]) and persisted by [WorkoutLogStore]. This
 * is the raw data the periodic [WorkoutDigestWorker] aggregates into a [WorkoutSummary] — kept
 * deliberately small (just enough to bucket/sum by goal) rather than mirroring the full
 * `suggest_workout` plan shape from Day 17.
 */
@Serializable
data class WorkoutLogEntry(
    val id: String,
    /** Free-form goal/muscle-group label, e.g. "legs", "cardio", "full_body". */
    val goal: String,
    val minutes: Int,
    val loggedAtEpochMillis: Long
)
