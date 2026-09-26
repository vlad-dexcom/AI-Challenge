package com.example.geminichat.mcp

import com.example.geminichat.agent.planner.Exercise
import com.example.geminichat.agent.planner.SavedWorkoutPlanStore
import com.example.geminichat.agent.planner.WorkoutPlan
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Day 19: [LocalWorkoutPlannerMcpGateway]'s tool contract — no network, so tested directly
 * against a real (temp-dir-backed) store, mirroring [LocalWorkoutMcpGatewayTest]. */
class LocalWorkoutPlannerMcpGatewayTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var tempDir: File
    private lateinit var planStore: SavedWorkoutPlanStore
    private lateinit var gateway: LocalWorkoutPlannerMcpGateway

    @Before
    fun setUp() {
        tempDir = File.createTempFile("local-workout-planner-gateway-test", "").apply {
            delete()
            mkdirs()
        }
        planStore = SavedWorkoutPlanStore(File(tempDir, SavedWorkoutPlanStore.FILE_NAME))
        gateway = LocalWorkoutPlannerMcpGateway(planStore)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `listTools advertises all three pipeline tools in order`() = runTest {
        gateway.connect(LocalWorkoutPlannerMcpGateway.DEFAULT_SERVER_URL)

        val names = gateway.listTools().map { it.name }

        assertEquals(
            listOf(
                LocalWorkoutPlannerMcpGateway.FIND_EXERCISES,
                LocalWorkoutPlannerMcpGateway.BUILD_WORKOUT_PLAN,
                LocalWorkoutPlannerMcpGateway.SAVE_WORKOUT_PLAN
            ),
            names
        )
    }

    @Test
    fun `find_exercises returns matching exercises as JSON`() = runTest {
        val result = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.FIND_EXERCISES,
            mapOf("goal" to "legs", "level" to "intermediate")
        )

        assertTrue(!result.isError)
        val exercises = json.decodeFromString(ListSerializer(Exercise.serializer()), result.text)
        assertTrue(exercises.isNotEmpty())
        assertTrue(exercises.all { it.goal == "legs" })
    }

    @Test
    fun `find_exercises reports an error for an unknown goal`() = runTest {
        val result = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.FIND_EXERCISES,
            mapOf("goal" to "unknown-goal", "level" to "intermediate")
        )

        assertTrue(result.isError)
    }

    @Test
    fun `build_workout_plan consumes find_exercises' raw output and returns a structured plan`() = runTest {
        val searchResult = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.FIND_EXERCISES,
            mapOf("goal" to "legs", "level" to "intermediate")
        )

        val planResult = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.BUILD_WORKOUT_PLAN,
            mapOf(
                "exercises_json" to searchResult.text,
                "goal" to "legs",
                "level" to "intermediate",
                "minutes" to 8
            )
        )

        assertTrue(!planResult.isError)
        val plan = json.decodeFromString(WorkoutPlan.serializer(), planResult.text)
        assertEquals("legs", plan.goal)
        assertEquals(2, plan.exercises.size)
    }

    @Test
    fun `build_workout_plan rejects malformed exercises_json instead of crashing`() = runTest {
        val result = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.BUILD_WORKOUT_PLAN,
            mapOf(
                "exercises_json" to "not json",
                "goal" to "legs",
                "level" to "intermediate",
                "minutes" to 30
            )
        )

        assertTrue(result.isError)
    }

    @Test
    fun `save_workout_plan persists build_workout_plan's raw output and round-trips`() = runTest {
        val searchResult = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.FIND_EXERCISES,
            mapOf("goal" to "legs", "level" to "intermediate")
        )
        val planResult = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.BUILD_WORKOUT_PLAN,
            mapOf(
                "exercises_json" to searchResult.text,
                "goal" to "legs",
                "level" to "intermediate",
                "minutes" to 30
            )
        )

        val saveResult = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.SAVE_WORKOUT_PLAN,
            mapOf("name" to "Leg Day", "plan_json" to planResult.text)
        )

        assertTrue(!saveResult.isError)
        assertTrue(saveResult.text.contains("Leg Day"))
        val saved = planStore.loadAll()
        assertEquals(1, saved.size)
        assertEquals("Leg Day", saved.single().name)
        assertEquals(planResult.text, saved.single().planJson)
    }

    @Test
    fun `save_workout_plan rejects plan_json that is not a build_workout_plan result`() = runTest {
        val result = gateway.callTool(
            LocalWorkoutPlannerMcpGateway.SAVE_WORKOUT_PLAN,
            mapOf("name" to "Leg Day", "plan_json" to "{\"not\":\"a plan\"}")
        )

        assertTrue(result.isError)
        assertEquals(0, planStore.loadAll().size)
    }
}
