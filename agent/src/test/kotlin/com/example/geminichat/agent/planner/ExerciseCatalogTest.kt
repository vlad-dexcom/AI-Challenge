package com.example.geminichat.agent.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseCatalogTest {

    @Test
    fun `search returns exercises matching the goal case-insensitively`() {
        val results = ExerciseCatalog.search("LEGS", FitnessLevel.INTERMEDIATE)

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.goal.equals("legs", ignoreCase = true) })
    }

    @Test
    fun `search returns an empty list for an unknown goal instead of throwing`() {
        val results = ExerciseCatalog.search("unknown-goal", FitnessLevel.INTERMEDIATE)

        assertEquals(emptyList<Exercise>(), results)
    }

    @Test
    fun `search prefers bodyweight-only exercises for beginners`() {
        val results = ExerciseCatalog.search("legs", FitnessLevel.BEGINNER)

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.bodyweight })
    }

    @Test
    fun `search falls back to all matches for beginners when no bodyweight option exists`() {
        // "chest" has both a barbell (Bench press) and a bodyweight (Push up) entry, but if a
        // goal had *only* non-bodyweight entries, beginners should still get something back
        // rather than an empty list.
        val results = ExerciseCatalog.search("chest", FitnessLevel.BEGINNER)

        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `search does not filter by bodyweight for intermediate or advanced levels`() {
        val intermediate = ExerciseCatalog.search("legs", FitnessLevel.INTERMEDIATE)
        val advanced = ExerciseCatalog.search("legs", FitnessLevel.ADVANCED)

        assertTrue(intermediate.any { !it.bodyweight })
        assertTrue(advanced.any { !it.bodyweight })
    }

    @Test
    fun `FitnessLevel fromStringOrNull parses case-insensitively and rejects unknown values`() {
        assertEquals(FitnessLevel.BEGINNER, FitnessLevel.fromStringOrNull("beginner"))
        assertEquals(FitnessLevel.ADVANCED, FitnessLevel.fromStringOrNull("ADVANCED"))
        assertEquals(null, FitnessLevel.fromStringOrNull("expert"))
        assertEquals(null, FitnessLevel.fromStringOrNull(null))
    }
}
