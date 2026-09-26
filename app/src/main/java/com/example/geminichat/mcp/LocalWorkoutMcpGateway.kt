package com.example.geminichat.mcp

import com.example.geminichat.agent.workout.WorkoutLogEntry
import com.example.geminichat.agent.workout.WorkoutLogStore
import com.example.geminichat.agent.workout.WorkoutSummaryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private val summaryJson = Json { prettyPrint = true }

/**
 * Day 18: an [McpGateway] with no network at all — `log_workout`/`get_workout_summary` are
 * backed by on-device JSON storage ([WorkoutLogStore]/[WorkoutSummaryStore]) instead of a real
 * remote MCP server (contrast [KotlinSdkMcpGateway], Day 17's gateway to the deployed Firebase
 * fitness server). It implements the exact same [McpGateway] contract, so
 * [com.example.geminichat.agent.mcp.McpToolCallingAgent]'s function-calling loop (declare tools
 * to Gemini, execute whichever one it calls, resubmit the result) is reused completely
 * unchanged — only the *transport* backing the tools is local instead of HTTP.
 *
 * This is what makes the periodic summary "24/7": [com.example.geminichat.agent.workout.WorkoutDigestWorker]
 * keeps refreshing [summaryStore] on its own schedule via WorkManager regardless of whether the
 * chat UI (or this gateway) is ever touched in between — `get_workout_summary` just reads
 * whatever that background job most recently computed.
 */
class LocalWorkoutMcpGateway(
    private val logStore: WorkoutLogStore,
    private val summaryStore: WorkoutSummaryStore
) : McpGateway {

    override suspend fun connect(serverUrl: String): McpServerInfo =
        McpServerInfo(
            name = "workout-digest-local",
            version = "1.0.0",
            capabilities = listOf("tools"),
            instructions = null
        )

    override suspend fun listTools(): List<McpToolInfo> = listOf(
        McpToolInfo(
            name = LOG_WORKOUT,
            title = "Log workout",
            description = "Records a completed workout (goal/muscle group and minutes spent) " +
                "so it counts toward the periodic workout summary.",
            parameters = listOf(
                McpToolParam(name = "goal", type = "string", description = "Goal/muscle group, e.g. legs, cardio, full_body.", required = true),
                McpToolParam(name = "minutes", type = "integer", description = "Minutes spent on the workout.", required = true)
            ),
            rawInputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("goal") {
                        put("type", "string")
                        put("description", "Goal/muscle group, e.g. legs, cardio, full_body.")
                    }
                    putJsonObject("minutes") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 300)
                        put("description", "Minutes spent on the workout.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("goal"))
                    add(JsonPrimitive("minutes"))
                }
            }
        ),
        McpToolInfo(
            name = GET_WORKOUT_SUMMARY,
            title = "Get workout summary",
            description = "Returns the latest periodic summary of logged workouts (totals and " +
                "a breakdown by goal), aggregated in the background on a schedule rather than " +
                "computed on the spot.",
            parameters = emptyList(),
            rawInputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }
        )
    )

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult =
        when (name) {
            LOG_WORKOUT -> logWorkout(arguments)
            GET_WORKOUT_SUMMARY -> getWorkoutSummary()
            else -> throw McpToolCallException("Unknown tool \"$name\".")
        }

    override suspend fun close() {
        // No connection/resource to release — everything here is plain local file I/O.
    }

    private fun logWorkout(arguments: Map<String, Any?>): McpToolCallResult {
        val goal = (arguments["goal"] as? String)?.trim()
        val minutes = (arguments["minutes"] as? Number)?.toInt()
        if (goal.isNullOrEmpty() || minutes == null || minutes <= 0) {
            return McpToolCallResult(
                text = "Both a non-empty \"goal\" and a positive \"minutes\" are required to log a workout.",
                isError = true
            )
        }

        logStore.append(
            WorkoutLogEntry(
                id = UUID.randomUUID().toString(),
                goal = goal,
                minutes = minutes,
                loggedAtEpochMillis = System.currentTimeMillis()
            )
        )
        return McpToolCallResult(
            text = "Logged a $minutes-minute \"$goal\" workout. It will be reflected in the next periodic summary.",
            isError = false
        )
    }

    private fun getWorkoutSummary(): McpToolCallResult {
        val summary = summaryStore.load()
            ?: return McpToolCallResult(
                text = "No workout summary yet — the periodic digest hasn't run since the first " +
                    "workout was logged. Try again shortly, or log a workout first.",
                isError = false
            )
        return McpToolCallResult(text = summaryJson.encodeToString(summary), isError = false)
    }

    companion object {
        const val LOG_WORKOUT = "log_workout"
        const val GET_WORKOUT_SUMMARY = "get_workout_summary"
        const val DEFAULT_SERVER_URL = "local://workout-digest"
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
