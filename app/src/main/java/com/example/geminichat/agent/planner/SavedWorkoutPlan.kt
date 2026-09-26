package com.example.geminichat.agent.planner

import kotlinx.serialization.Serializable

/**
 * Day 19: one workout plan saved by the `save_workout_plan` tool (see
 * [com.example.geminichat.mcp.LocalWorkoutPlannerMcpGateway]) — step 3 ("save the result") of
 * the pipeline. [planJson] stores the JSON text `build_workout_plan` produced verbatim, so
 * saving never has to re-parse/re-derive it.
 */
@Serializable
data class SavedWorkoutPlan(
    val id: String,
    val name: String,
    val planJson: String,
    val savedAtEpochMillis: Long
)
