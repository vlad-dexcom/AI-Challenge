package com.example.geminichat.agent.workout

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Day 18: round-trip persistence for [WorkoutLogStore] and [WorkoutSummaryStore], modeled on
 * [com.example.geminichat.agent.task.TaskStateStoreTest]. */
class WorkoutStoresTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("workout-store-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `WorkoutLogStore returns empty list when nothing was logged yet`() {
        val store = WorkoutLogStore(File(tempDir, WorkoutLogStore.FILE_NAME))

        assertEquals(emptyList<WorkoutLogEntry>(), store.loadAll())
    }

    @Test
    fun `WorkoutLogStore appends without dropping earlier entries`() {
        val store = WorkoutLogStore(File(tempDir, WorkoutLogStore.FILE_NAME))
        val first = WorkoutLogEntry(id = "1", goal = "legs", minutes = 30, loggedAtEpochMillis = 100L)
        val second = WorkoutLogEntry(id = "2", goal = "cardio", minutes = 20, loggedAtEpochMillis = 200L)

        store.append(first)
        store.append(second)

        assertEquals(listOf(first, second), store.loadAll())
    }

    @Test
    fun `WorkoutSummaryStore returns null when nothing was saved yet`() {
        val store = WorkoutSummaryStore(File(tempDir, WorkoutSummaryStore.FILE_NAME))

        assertEquals(null, store.load())
    }

    @Test
    fun `WorkoutSummaryStore round-trips and overwrites the previous summary`() {
        val store = WorkoutSummaryStore(File(tempDir, WorkoutSummaryStore.FILE_NAME))
        val first = WorkoutSummary(
            generatedAtEpochMillis = 1L,
            periodDays = 7,
            totalWorkouts = 1,
            totalMinutes = 30,
            workoutsByGoal = mapOf("legs" to 1),
            minutesByGoal = mapOf("legs" to 30)
        )
        val second = first.copy(generatedAtEpochMillis = 2L, totalWorkouts = 2, totalMinutes = 60)

        store.save(first)
        store.save(second)

        assertEquals(second, store.load())
    }
}
