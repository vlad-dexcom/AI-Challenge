package com.example.geminichat.agent.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPlanBuilderTest {

    private val legExercises = ExerciseCatalog.search("legs", FitnessLevel.INTERMEDIATE)

    @Test
    fun `build trims exercises to fit the time budget`() {
        val plan = WorkoutPlanBuilder.build(
            exercises = legExercises,
            goal = "legs",
            level = FitnessLevel.INTERMEDIATE,
            minutes = 8
        )

        // 8 minutes / 4 minutes-per-exercise = 2 exercises.
        assertEquals(2, plan.exercises.size)
        assertEquals(8, plan.estimatedMinutes)
        assertEquals(8, plan.requestedMinutes)
        assertEquals("legs", plan.goal)
        assertEquals("intermediate", plan.level)
    }

    @Test
    fun `build never returns more than the max exercise cap even for a huge time budget`() {
        val plan = WorkoutPlanBuilder.build(
            exercises = legExercises,
            goal = "legs",
            level = FitnessLevel.INTERMEDIATE,
            minutes = 120
        )

        assertTrue(plan.exercises.size <= 8)
    }

    @Test
    fun `build assigns sets and reps according to level`() {
        val beginnerPlan = WorkoutPlanBuilder.build(legExercises, "legs", FitnessLevel.BEGINNER, minutes = 30)
        val advancedPlan = WorkoutPlanBuilder.build(legExercises, "legs", FitnessLevel.ADVANCED, minutes = 30)

        assertTrue(beginnerPlan.exercises.isNotEmpty())
        assertTrue(advancedPlan.exercises.isNotEmpty())
        assertTrue(beginnerPlan.exercises.all { it.setsAndReps == "3x10" })
        // Advanced, non-bodyweight (barbell squat) gets a different rep scheme than bodyweight.
        assertTrue(advancedPlan.exercises.any { it.setsAndReps == "5x5" || it.setsAndReps == "4x20" })
    }

    @Test
    fun `build returns an empty plan (not an error) when there are no exercises to build from`() {
        val plan = WorkoutPlanBuilder.build(
            exercises = emptyList(),
            goal = "unknown-goal",
            level = FitnessLevel.INTERMEDIATE,
            minutes = 30
        )

        assertTrue(plan.exercises.isEmpty())
        assertEquals(0, plan.estimatedMinutes)
    }
}
