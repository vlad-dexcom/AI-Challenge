package com.example.geminichat.mcp

import com.example.geminichat.agent.workout.WorkoutLogStore
import com.example.geminichat.agent.workout.WorkoutSummary
import com.example.geminichat.agent.workout.WorkoutSummaryStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Day 18: [LocalWorkoutMcpGateway]'s tool contract — no network, so tested directly against
 * real (temp-dir-backed) stores rather than fakes. */
class LocalWorkoutMcpGatewayTest {

    private lateinit var tempDir: File
    private lateinit var logStore: WorkoutLogStore
    private lateinit var summaryStore: WorkoutSummaryStore
    private lateinit var gateway: LocalWorkoutMcpGateway

    @Before
    fun setUp() {
        tempDir = File.createTempFile("local-workout-gateway-test", "").apply {
            delete()
            mkdirs()
        }
        logStore = WorkoutLogStore(File(tempDir, WorkoutLogStore.FILE_NAME))
        summaryStore = WorkoutSummaryStore(File(tempDir, WorkoutSummaryStore.FILE_NAME))
        gateway = LocalWorkoutMcpGateway(logStore, summaryStore)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `listTools advertises both tools`() = runTest {
        gateway.connect(LocalWorkoutMcpGateway.DEFAULT_SERVER_URL)

        val names = gateway.listTools().map { it.name }

        assertEquals(
            listOf(LocalWorkoutMcpGateway.LOG_WORKOUT, LocalWorkoutMcpGateway.GET_WORKOUT_SUMMARY),
            names
        )
    }

    @Test
    fun `log_workout appends an entry to the store`() = runTest {
        val result = gateway.callTool(
            LocalWorkoutMcpGateway.LOG_WORKOUT,
            mapOf("goal" to "legs", "minutes" to 30)
        )

        assertTrue(!result.isError)
        assertEquals(1, logStore.loadAll().size)
        assertEquals("legs", logStore.loadAll().single().goal)
        assertEquals(30, logStore.loadAll().single().minutes)
    }

    @Test
    fun `log_workout rejects a missing goal or non-positive minutes`() = runTest {
        val missingGoal = gateway.callTool(LocalWorkoutMcpGateway.LOG_WORKOUT, mapOf("minutes" to 30))
        val zeroMinutes =
            gateway.callTool(LocalWorkoutMcpGateway.LOG_WORKOUT, mapOf("goal" to "legs", "minutes" to 0))

        assertTrue(missingGoal.isError)
        assertTrue(zeroMinutes.isError)
        assertEquals(0, logStore.loadAll().size)
    }

    @Test
    fun `get_workout_summary reports no data before the digest has run`() = runTest {
        val result = gateway.callTool(LocalWorkoutMcpGateway.GET_WORKOUT_SUMMARY, emptyMap())

        assertTrue(!result.isError)
        assertTrue(result.text.contains("No workout summary yet"))
    }

    @Test
    fun `get_workout_summary returns whatever the background digest last saved`() = runTest {
        summaryStore.save(
            WorkoutSummary(
                generatedAtEpochMillis = 123L,
                periodDays = 7,
                totalWorkouts = 2,
                totalMinutes = 50,
                workoutsByGoal = mapOf("legs" to 2),
                minutesByGoal = mapOf("legs" to 50)
            )
        )

        val result = gateway.callTool(LocalWorkoutMcpGateway.GET_WORKOUT_SUMMARY, emptyMap())

        assertTrue(!result.isError)
        assertTrue(result.text.contains("\"totalWorkouts\": 2"))
    }
}
