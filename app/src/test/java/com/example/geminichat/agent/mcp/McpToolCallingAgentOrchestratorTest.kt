package com.example.geminichat.agent.mcp

import com.example.geminichat.GeminiFunctionTool
import com.example.geminichat.InteractionResponse
import com.example.geminichat.InteractionStep
import com.example.geminichat.StepContent
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.mcp.CompositeMcpGateway
import com.example.geminichat.mcp.McpGateway
import com.example.geminichat.mcp.McpServerInfo
import com.example.geminichat.mcp.McpToolCallResult
import com.example.geminichat.mcp.McpToolInfo
import com.example.geminichat.mcp.NamedMcpGateway
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every call it receives, tagged with [serverName], so cross-server ordering can be asserted. */
private class RecordingServerGateway(
    val serverName: String,
    private val tools: List<McpToolInfo>,
    private val resultsByTool: Map<String, String>
) : McpGateway {
    data class RecordedCall(val serverName: String, val toolName: String, val arguments: Map<String, Any?>)

    companion object {
        val calls = mutableListOf<RecordedCall>()
    }

    override suspend fun connect(serverUrl: String): McpServerInfo =
        McpServerInfo(name = serverName, version = "1.0.0", capabilities = listOf("tools"), instructions = null)

    override suspend fun listTools(): List<McpToolInfo> = tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        calls += RecordedCall(serverName, name, arguments)
        val text = resultsByTool[name] ?: error("No scripted result for tool \"$name\" on \"$serverName\".")
        return McpToolCallResult(text = text, isError = false)
    }

    override suspend fun close() {}
}

private fun toolInfo(name: String) = McpToolInfo(
    name = name,
    title = name,
    description = "test tool",
    parameters = emptyList(),
    rawInputSchema = buildJsonObject { put("type", JsonPrimitive("object")) }
)

/** Scripted [ToolCallingLlmClient]: one `requires_action` per step, then a final `completed` answer. */
private class ScriptedOrchestratorLlmClient(
    private val steps: List<Pair<String, Map<String, String>>>,
    private val finalAnswer: String
) : ToolCallingLlmClient {
    var callCount = 0
        private set

    override suspend fun createInteraction(
        model: String,
        input: kotlinx.serialization.json.JsonElement,
        systemInstruction: String?,
        tools: List<GeminiFunctionTool>?,
        previousInteractionId: String?
    ): Result<InteractionResponse> {
        val stepIndex = callCount
        callCount++

        if (stepIndex >= steps.size) {
            return Result.success(
                InteractionResponse(
                    id = "interaction-final",
                    status = "completed",
                    steps = listOf(
                        InteractionStep(
                            type = "model_output",
                            content = listOf(StepContent(type = "text", text = finalAnswer))
                        )
                    )
                )
            )
        }

        val (toolName, arguments) = steps[stepIndex]
        return Result.success(
            InteractionResponse(
                id = "interaction-$stepIndex",
                status = "requires_action",
                steps = listOf(
                    InteractionStep(
                        type = "function_call",
                        id = "call-$stepIndex",
                        name = toolName,
                        arguments = buildJsonObject {
                            arguments.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                        }
                    )
                )
            )
        )
    }
}

/**
 * Day 20: proves the orchestrator persona's single [McpToolCallingAgent.handle] call correctly
 * routes a *long, multi-step* flow across **three different MCP servers/gateways** wired
 * together by [CompositeMcpGateway] — the exercise's "используются инструменты с разных
 * серверов" / "корректность выбора и порядка вызовов" requirement — without any change to
 * [McpToolCallingAgent]'s existing function-calling loop.
 */
class McpToolCallingAgentOrchestratorTest {

    private val config = AgentConfig(
        id = "orchestrator-coach",
        displayName = "Orchestrator Coach",
        description = "test",
        systemInstruction = "You are a fitness coach with tools on three MCP servers."
    )

    @Test
    fun `verified workout plan flow calls tools on the right server, in order, in one turn`() = runTest {
        RecordingServerGateway.calls.clear()

        val remoteGateway = RecordingServerGateway(
            serverName = "wger",
            tools = listOf(toolInfo("get_exercise_info"), toolInfo("suggest_workout")),
            resultsByTool = mapOf(
                "get_exercise_info" to "Squat: a compound leg exercise (verified in wger)."
            )
        )
        val plannerGateway = RecordingServerGateway(
            serverName = "workout-planner",
            tools = listOf(toolInfo("find_exercises"), toolInfo("build_workout_plan"), toolInfo("save_workout_plan")),
            resultsByTool = mapOf(
                "find_exercises" to "[{\"name\":\"Squat\",\"goal\":\"legs\"}]",
                "build_workout_plan" to "{\"goal\":\"legs\",\"exercises\":[{\"name\":\"Squat\"}]}",
                "save_workout_plan" to "Saved workout plan \"Leg Day\" (id: abc-123)."
            )
        )
        val digestGateway = RecordingServerGateway(
            serverName = "workout-digest",
            tools = listOf(toolInfo("log_workout"), toolInfo("get_workout_summary")),
            resultsByTool = mapOf("log_workout" to "Logged a 30-minute \"legs\" workout.")
        )

        val composite = CompositeMcpGateway(
            listOf(
                NamedMcpGateway("wger", "https://wger.example/mcp", remoteGateway),
                NamedMcpGateway("workout-planner", "local://workout-plan-builder", plannerGateway),
                NamedMcpGateway("workout-digest", "local://workout-digest", digestGateway)
            )
        )

        // Scenario: "Verify Squat against the real database, build/save a 30-min leg plan named
        // 'Leg Day', then log it as done" — a single handle() call chaining wger → planner (x2)
        // → digest, crossing servers three times.
        val llmClient = ScriptedOrchestratorLlmClient(
            steps = listOf(
                "get_exercise_info" to mapOf("name" to "Squat"),
                "find_exercises" to mapOf("goal" to "legs", "level" to "intermediate"),
                "build_workout_plan" to mapOf(
                    "exercises_json" to "[{\"name\":\"Squat\",\"goal\":\"legs\"}]",
                    "goal" to "legs",
                    "level" to "intermediate",
                    "minutes" to "30"
                ),
                "save_workout_plan" to mapOf(
                    "name" to "Leg Day",
                    "plan_json" to "{\"goal\":\"legs\",\"exercises\":[{\"name\":\"Squat\"}]}"
                ),
                "log_workout" to mapOf("goal" to "legs", "minutes" to "30")
            ),
            finalAnswer = "Verified Squat, saved \"Leg Day\", and logged today's leg workout."
        )

        val agent = McpToolCallingAgent(config, llmClient, composite, serverUrl = "orchestrator://all", maxToolRounds = 6)

        val result = agent.handle(
            AgentRequest(
                userMessage = "Verify Squat, build and save a 30-minute leg plan called Leg Day, " +
                    "then log it as done."
            )
        )

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()

        // All 5 tools ran automatically, in the scripted order, within this single handle() call.
        assertEquals(5, response.toolCalls.size)
        assertEquals(
            listOf("get_exercise_info", "find_exercises", "build_workout_plan", "save_workout_plan", "log_workout"),
            response.toolCalls.map { it.toolName }
        )

        // Each call was routed to the correct underlying server — the point of orchestration:
        // similarly-themed tools (exercise/workout related) must not collide across servers.
        assertEquals(
            listOf("wger", "workout-planner", "workout-planner", "workout-planner", "workout-digest"),
            RecordingServerGateway.calls.map { it.serverName }
        )

        assertEquals(
            "Verified Squat, saved \"Leg Day\", and logged today's leg workout.",
            response.text
        )
    }
}
