package com.example.geminichat.agent.planner

import kotlinx.serialization.Serializable

/** One entry of a built plan: an exercise plus how much of it to do. */
@Serializable
data class PlannedExerciseEntry(
    val name: String,
    val goal: String,
    val setsAndReps: String
)

/** The structured output of [WorkoutPlanBuilder.build] — step 2 ("process") of the pipeline. */
@Serializable
data class WorkoutPlan(
    val goal: String,
    val level: String,
    val requestedMinutes: Int,
    val estimatedMinutes: Int,
    val exercises: List<PlannedExerciseEntry>
)

/**
 * Day 19: pure, deterministic processing step of the Workout Plan Builder pipeline —
 * `build_workout_plan` (see [com.example.geminichat.mcp.LocalWorkoutPlannerMcpGateway]) hands it
 * the raw [Exercise] list `find_exercises` returned and gets back a trimmed, structured
 * [WorkoutPlan]. Deliberately rule-based rather than a second Gemini call: it keeps the
 * pipeline's tool boundaries and data flow easy to assert on in tests (see
 * `WorkoutPlanBuilderTest`).
 */
object WorkoutPlanBuilder {

    /** Roughly how many minutes one exercise (3 sets incl. rest) takes. */
    private const val MINUTES_PER_EXERCISE = 4
    private const val MAX_EXERCISES = 8

    private fun setsAndRepsFor(level: FitnessLevel, bodyweight: Boolean): String {
        if (bodyweight) {
            return when (level) {
                FitnessLevel.BEGINNER -> "3x10"
                FitnessLevel.INTERMEDIATE -> "3x15"
                FitnessLevel.ADVANCED -> "4x20"
            }
        }
        return when (level) {
            FitnessLevel.BEGINNER -> "3x10"
            FitnessLevel.INTERMEDIATE -> "4x8-10"
            FitnessLevel.ADVANCED -> "5x5"
        }
    }

    /**
     * Trims [exercises] to fit [minutes] (at [MAX_EXERCISES] max) and assigns sets/reps for
     * [level]. Returns a [WorkoutPlan] with an empty `exercises` list (not a thrown error) when
     * [exercises] is empty, so callers/tools can decide how to report "nothing to build a plan
     * from".
     */
    fun build(exercises: List<Exercise>, goal: String, level: FitnessLevel, minutes: Int): WorkoutPlan {
        val exerciseCount = MAX_EXERCISES.coerceAtMost(
            1.coerceAtLeast(Math.round(minutes / MINUTES_PER_EXERCISE.toDouble()).toInt())
        )
        val trimmed = exercises.take(exerciseCount)
        val planned = trimmed.map {
            PlannedExerciseEntry(
                name = it.name,
                goal = it.goal,
                setsAndReps = setsAndRepsFor(level, it.bodyweight)
            )
        }
        return WorkoutPlan(
            goal = goal,
            level = level.name.lowercase(),
            requestedMinutes = minutes,
            estimatedMinutes = planned.size * MINUTES_PER_EXERCISE,
            exercises = planned
        )
    }
}
