package com.example.geminichat.agent.planner

import kotlinx.serialization.Serializable

/**
 * Day 19: one exercise in the built-in, offline catalog used by the `find_exercises` tool (see
 * [com.example.geminichat.mcp.LocalWorkoutPlannerMcpGateway]) — this is step 1 ("fetch data") of
 * the Workout Plan Builder pipeline. Deliberately small and static (contrast Day 17's
 * `get_exercise_info`/`suggest_workout`, which hit the real wger.de database over the network):
 * the point of this exercise is tool *composition*, not another network integration.
 */
@Serializable
data class Exercise(
    val name: String,
    /** Muscle-group/goal label, e.g. "legs", "chest", "back", "cardio", "abs". */
    val goal: String,
    val equipment: List<String>,
    val bodyweight: Boolean
)

/** Fitness levels the plan builder tunes sets/reps for. */
enum class FitnessLevel {
    BEGINNER, INTERMEDIATE, ADVANCED;

    companion object {
        fun fromStringOrNull(value: String?): FitnessLevel? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Day 19: the static exercise catalog backing `find_exercises`, plus the pure [search] function
 * over it. Kept as plain data/functions (no I/O) so it's trivially unit-testable.
 */
object ExerciseCatalog {

    private val exercises = listOf(
        Exercise("Squat", goal = "legs", equipment = listOf("barbell"), bodyweight = false),
        Exercise("Bodyweight squat", goal = "legs", equipment = emptyList(), bodyweight = true),
        Exercise("Lunge", goal = "legs", equipment = emptyList(), bodyweight = true),
        Exercise("Bench press", goal = "chest", equipment = listOf("barbell", "bench"), bodyweight = false),
        Exercise("Push up", goal = "chest", equipment = emptyList(), bodyweight = true),
        Exercise("Pull up", goal = "back", equipment = listOf("pull-up bar"), bodyweight = true),
        Exercise("Deadlift", goal = "back", equipment = listOf("barbell"), bodyweight = false),
        Exercise("Shoulder press", goal = "shoulders", equipment = listOf("dumbbells"), bodyweight = false),
        Exercise("Plank", goal = "abs", equipment = emptyList(), bodyweight = true),
        Exercise("Crunch", goal = "abs", equipment = emptyList(), bodyweight = true),
        Exercise("Jumping jacks", goal = "cardio", equipment = emptyList(), bodyweight = true),
        Exercise("Burpee", goal = "cardio", equipment = emptyList(), bodyweight = true)
    )

    /**
     * Every exercise matching [goal] (case-insensitive), preferring bodyweight-only entries when
     * [level] is [FitnessLevel.BEGINNER] (no equipment assumed available) — mirrors
     * `suggestWorkout.ts`'s `BODYWEIGHT_EQUIPMENT_ID` filter for beginners. Returns an empty
     * list for an unknown goal rather than throwing, so `find_exercises` can report "no matches"
     * instead of failing the tool call.
     */
    fun search(goal: String, level: FitnessLevel): List<Exercise> {
        val matches = exercises.filter { it.goal.equals(goal, ignoreCase = true) }
        if (level != FitnessLevel.BEGINNER) return matches
        val bodyweightOnly = matches.filter { it.bodyweight }
        return bodyweightOnly.ifEmpty { matches }
    }
}
