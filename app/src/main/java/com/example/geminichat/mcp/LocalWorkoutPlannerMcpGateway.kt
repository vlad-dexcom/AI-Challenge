package com.example.geminichat.mcp

import com.example.geminichat.agent.planner.Exercise
import com.example.geminichat.agent.planner.ExerciseCatalog
import com.example.geminichat.agent.planner.FitnessLevel
import com.example.geminichat.agent.planner.SavedWorkoutPlan
import com.example.geminichat.agent.planner.SavedWorkoutPlanStore
import com.example.geminichat.agent.planner.WorkoutPlan
import com.example.geminichat.agent.planner.WorkoutPlanBuilder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private val plannerJson = Json { ignoreUnknownKeys = true }

/**
 * Day 19: an [McpGateway] with no network — three composable tools implementing the "Workout
 * Plan Builder" pipeline, backed by plain in-memory data ([ExerciseCatalog]/[WorkoutPlanBuilder])
 * and on-device JSON storage ([SavedWorkoutPlanStore]), exactly like Day 18's
 * [LocalWorkoutMcpGateway]. Because the [McpGateway] contract is identical,
 * [com.example.geminichat.agent.mcp.McpToolCallingAgent]'s function-calling loop — which already
 * supports an arbitrary number of *sequential* tool calls per turn (see `maxToolRounds`) — drives
 * all three tools with **no changes**: the model calls [FIND_EXERCISES], gets the raw exercise
 * JSON back, calls [BUILD_WORKOUT_PLAN] with that JSON, gets the structured plan JSON back, then
 * calls [SAVE_WORKOUT_PLAN] with that plan JSON. Each step's *input* is the previous step's
 * *output*, verbatim — this is what proves automatic chaining with correct data hand-off (see
 * `McpToolCallingAgentPipelineTest`).
 */
class LocalWorkoutPlannerMcpGateway(
    private val planStore: SavedWorkoutPlanStore
) : McpGateway {

    override suspend fun connect(serverUrl: String): McpServerInfo =
        McpServerInfo(
            name = "workout-plan-builder-local",
            version = "1.0.0",
            capabilities = listOf("tools"),
            instructions = null
        )

    override suspend fun listTools(): List<McpToolInfo> = listOf(
        McpToolInfo(
            name = FIND_EXERCISES,
            title = "Find exercises",
            description = "Step 1 of the workout plan pipeline: looks up exercises for a goal/" +
                "muscle group and fitness level from the built-in catalog. Returns raw exercise " +
                "JSON — pass it, unmodified, as the exercises_json argument of build_workout_plan.",
            parameters = listOf(
                McpToolParam(name = "goal", type = "string", description = "Muscle group/goal, e.g. legs, chest, back, shoulders, abs, cardio.", required = true),
                McpToolParam(name = "level", type = "string", description = "Fitness level: beginner, intermediate or advanced.", required = true)
            ),
            rawInputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("goal") { put("type", "string") }
                    putJsonObject("level") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add(JsonPrimitive("beginner"))
                            add(JsonPrimitive("intermediate"))
                            add(JsonPrimitive("advanced"))
                        }
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("goal"))
                    add(JsonPrimitive("level"))
                }
            }
        ),
        McpToolInfo(
            name = BUILD_WORKOUT_PLAN,
            title = "Build workout plan",
            description = "Step 2 of the workout plan pipeline: turns the raw exercise JSON " +
                "from find_exercises into a structured plan (sets/reps, trimmed to fit the time " +
                "budget). Pass find_exercises' result verbatim as exercises_json. Returns plan " +
                "JSON — pass it, unmodified, as the plan_json argument of save_workout_plan.",
            parameters = listOf(
                McpToolParam(name = "exercises_json", type = "string", description = "The exact JSON result returned by find_exercises.", required = true),
                McpToolParam(name = "goal", type = "string", description = "Muscle group/goal, same value passed to find_exercises.", required = true),
                McpToolParam(name = "level", type = "string", description = "Fitness level, same value passed to find_exercises.", required = true),
                McpToolParam(name = "minutes", type = "integer", description = "Total time available for the workout, in minutes.", required = true)
            ),
            rawInputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("exercises_json") { put("type", "string") }
                    putJsonObject("goal") { put("type", "string") }
                    putJsonObject("level") { put("type", "string") }
                    putJsonObject("minutes") {
                        put("type", "integer")
                        put("minimum", 5)
                        put("maximum", 120)
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("exercises_json"))
                    add(JsonPrimitive("goal"))
                    add(JsonPrimitive("level"))
                    add(JsonPrimitive("minutes"))
                }
            }
        ),
        McpToolInfo(
            name = SAVE_WORKOUT_PLAN,
            title = "Save workout plan",
            description = "Step 3 of the workout plan pipeline: persists a named plan so it can " +
                "be recalled later. Pass build_workout_plan's result verbatim as plan_json.",
            parameters = listOf(
                McpToolParam(name = "name", type = "string", description = "A short name for the saved plan, e.g. \"Leg Day\".", required = true),
                McpToolParam(name = "plan_json", type = "string", description = "The exact JSON result returned by build_workout_plan.", required = true)
            ),
            rawInputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("plan_json") { put("type", "string") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("name"))
                    add(JsonPrimitive("plan_json"))
                }
            }
        )
    )

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult =
        when (name) {
            FIND_EXERCISES -> findExercises(arguments)
            BUILD_WORKOUT_PLAN -> buildWorkoutPlan(arguments)
            SAVE_WORKOUT_PLAN -> saveWorkoutPlan(arguments)
            else -> throw McpToolCallException("Unknown tool \"$name\".")
        }

    override suspend fun close() {
        // No connection/resource to release — everything here is plain in-memory data + local
        // file I/O.
    }

    private fun findExercises(arguments: Map<String, Any?>): McpToolCallResult {
        val goal = (arguments["goal"] as? String)?.trim()
        val level = FitnessLevel.fromStringOrNull(arguments["level"] as? String)
        if (goal.isNullOrEmpty() || level == null) {
            return McpToolCallResult(
                text = "Both a non-empty \"goal\" and a valid \"level\" " +
                    "(beginner/intermediate/advanced) are required.",
                isError = true
            )
        }

        val matches = ExerciseCatalog.search(goal, level)
        if (matches.isEmpty()) {
            return McpToolCallResult(
                text = "No exercises found for goal \"$goal\".",
                isError = true
            )
        }
        return McpToolCallResult(
            text = plannerJson.encodeToString(ListSerializer(Exercise.serializer()), matches),
            isError = false
        )
    }

    private fun buildWorkoutPlan(arguments: Map<String, Any?>): McpToolCallResult {
        val exercisesJson = arguments["exercises_json"] as? String
        val goal = (arguments["goal"] as? String)?.trim()
        val level = FitnessLevel.fromStringOrNull(arguments["level"] as? String)
        val minutes = (arguments["minutes"] as? Number)?.toInt()
        if (exercisesJson.isNullOrEmpty() || goal.isNullOrEmpty() || level == null || minutes == null || minutes <= 0) {
            return McpToolCallResult(
                text = "\"exercises_json\", a non-empty \"goal\", a valid \"level\" and a " +
                    "positive \"minutes\" are all required.",
                isError = true
            )
        }

        val exercises = try {
            plannerJson.decodeFromString(ListSerializer(Exercise.serializer()), exercisesJson)
        } catch (e: SerializationException) {
            return McpToolCallResult(
                text = "\"exercises_json\" must be the exact JSON result returned by find_exercises.",
                isError = true
            )
        }

        val plan = WorkoutPlanBuilder.build(exercises, goal, level, minutes)
        return McpToolCallResult(text = plannerJson.encodeToString(WorkoutPlan.serializer(), plan), isError = false)
    }

    private fun saveWorkoutPlan(arguments: Map<String, Any?>): McpToolCallResult {
        val name = (arguments["name"] as? String)?.trim()
        val planJson = arguments["plan_json"] as? String
        if (name.isNullOrEmpty() || planJson.isNullOrEmpty()) {
            return McpToolCallResult(
                text = "Both a non-empty \"name\" and \"plan_json\" are required to save a plan.",
                isError = true
            )
        }

        // Validate it's the shape build_workout_plan actually produces before persisting it,
        // rather than silently saving arbitrary text under plan_json.
        try {
            plannerJson.decodeFromString(WorkoutPlan.serializer(), planJson)
        } catch (e: SerializationException) {
            return McpToolCallResult(
                text = "\"plan_json\" must be the exact JSON result returned by build_workout_plan.",
                isError = true
            )
        }

        val id = UUID.randomUUID().toString()
        planStore.append(
            SavedWorkoutPlan(
                id = id,
                name = name,
                planJson = planJson,
                savedAtEpochMillis = System.currentTimeMillis()
            )
        )
        return McpToolCallResult(text = "Saved workout plan \"$name\" (id: $id).", isError = false)
    }

    companion object {
        const val FIND_EXERCISES = "find_exercises"
        const val BUILD_WORKOUT_PLAN = "build_workout_plan"
        const val SAVE_WORKOUT_PLAN = "save_workout_plan"
        const val DEFAULT_SERVER_URL = "local://workout-plan-builder"
    }
}

private inline fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
    key: String,
    builderAction: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
) {
    put(key, buildJsonObject(builderAction))
}

private inline fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    builderAction: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit
) {
    put(key, kotlinx.serialization.json.buildJsonArray(builderAction))
}
