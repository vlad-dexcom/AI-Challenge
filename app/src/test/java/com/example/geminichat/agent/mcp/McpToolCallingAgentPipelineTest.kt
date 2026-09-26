package com.example.geminichat.agent.mcp

import com.example.geminichat.GeminiFunctionTool
import com.example.geminichat.InteractionResponse
import com.example.geminichat.InteractionStep
import com.example.geminichat.StepContent
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.mcp.McpGateway
import com.example.geminichat.mcp.McpServerInfo
import com.example.geminichat.mcp.McpToolCallResult
import com.example.geminichat.mcp.McpToolInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Day 19: proves [McpToolCallingAgent]'s existing function-calling loop — unchanged from Day
 * 17/18 — automatically chains an arbitrary *pipeline* of MCP tools within a single
 * `handle(...)` call, and that each tool receives exactly the previous tool's raw output (not a
 * re-derived or model-invented value). Modeled on the fakes in [McpToolCallingAgentTest], but
 * this gateway records **every** call (not just the last), so multi-step ordering/arguments can
 * be asserted.
 */
private class RecordingPipelineMcpGateway(
    private val tools: List<McpToolInfo>,
    /** toolName -> the text that tool call should return. */
    val resultsByTool: Map<String, String>
) : McpGateway {
    data class RecordedCall(val toolName: String, val arguments: Map<String, Any?>)

    val calls = mutableListOf<RecordedCall>()

    override suspend fun connect(serverUrl: String): McpServerInfo =
        McpServerInfo(name = "pipeline-test", version = "1.0.0", capabilities = listOf("tools"), instructions = null)

    override suspend fun listTools(): List<McpToolInfo> = tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        calls += RecordedCall(name, arguments)
        val text = resultsByTool[name] ?: error("No scripted result for tool \"$name\".")
        return McpToolCallResult(text = text, isError = false)
    }

    override suspend fun close() {}
}

/**
 * Fake [ToolCallingLlmClient] returning a scripted sequence of `requires_action` responses (one
 * per pipeline step), each built by looking up the *previous* step's real recorded result from
 * [gateway.resultsByTool] — the same source of truth the gateway itself returns from — so a
 * step's arguments genuinely forward the prior tool's output rather than a value hardcoded to
 * match. After all steps, returns a final `completed` answer.
 */
private class ScriptedPipelineLlmClient(
    private val gateway: RecordingPipelineMcpGateway,
    /** One entry per pipeline step: the tool to call, and how to build its arguments given the
     * previous step's tool name (or `null` for the first step). */
    private val steps: List<Pair<String, (previousToolName: String?) -> Map<String, String>>>,
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

        val (toolName, buildArguments) = steps[stepIndex]
        val previousToolName = if (stepIndex == 0) null else steps[stepIndex - 1].first
        val arguments = buildArguments(previousToolName)

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

class McpToolCallingAgentPipelineTest {

    private val config = AgentConfig(
        id = "workout-plan-pipeline-coach",
        displayName = "Workout Plan Builder",
        description = "test",
        systemInstruction = "You are a fitness coach."
    )

    private fun toolInfo(name: String) = McpToolInfo(
        name = name,
        title = name,
        description = "test tool",
        parameters = emptyList(),
        rawInputSchema = buildJsonObject { put("type", JsonPrimitive("object")) }
    )

    private val searchResultText = "[{\"name\":\"Squat\",\"goal\":\"legs\"}]"
    private val planResultText = "{\"goal\":\"legs\",\"exercises\":[{\"name\":\"Squat\"}]}"
    private val saveResultText = "Saved workout plan \"Leg Day\" (id: abc-123)."

    @Test
    fun `automatically chains find_exercises, build_workout_plan and save_workout_plan in one turn`() = runTest {
        val gateway = RecordingPipelineMcpGateway(
            tools = listOf(toolInfo("find_exercises"), toolInfo("build_workout_plan"), toolInfo("save_workout_plan")),
            resultsByTool = mapOf(
                "find_exercises" to searchResultText,
                "build_workout_plan" to planResultText,
                "save_workout_plan" to saveResultText
            )
        )

        val llmClient = ScriptedPipelineLlmClient(
            gateway = gateway,
            steps = listOf(
                // Step 1: no prior result to forward — the model supplies the query itself.
                "find_exercises" to { _ -> mapOf("goal" to "legs", "level" to "intermediate") },
                // Step 2: forwards find_exercises' raw result, exactly as a well-behaved model
                // following the system instruction would.
                "build_workout_plan" to { prevTool ->
                    mapOf("exercises_json" to gateway.resultsByTool.getValue(prevTool!!), "minutes" to "30")
                },
                // Step 3: forwards build_workout_plan's raw result.
                "save_workout_plan" to { prevTool ->
                    mapOf("name" to "Leg Day", "plan_json" to gateway.resultsByTool.getValue(prevTool!!))
                }
            ),
            finalAnswer = "Saved your leg workout plan as \"Leg Day\"."
        )

        val agent = McpToolCallingAgent(config, llmClient, gateway, serverUrl = "local://workout-plan-builder")

        val result = agent.handle(AgentRequest(userMessage = "Build me a 30-minute leg workout and save it as Leg Day"))

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()

        // All 3 tools ran automatically, in order, within this single handle() call — no manual
        // intervention between steps.
        assertEquals(3, gateway.calls.size)
        assertEquals(
            listOf("find_exercises", "build_workout_plan", "save_workout_plan"),
            gateway.calls.map { it.toolName }
        )

        // build_workout_plan received find_exercises' raw result, verbatim.
        assertEquals(searchResultText, gateway.calls[1].arguments["exercises_json"])
        // save_workout_plan received build_workout_plan's raw result, verbatim.
        assertEquals(planResultText, gateway.calls[2].arguments["plan_json"])

        // The agent surfaces all 3 tool calls with their correct results, in order.
        assertEquals(3, response.toolCalls.size)
        assertEquals(searchResultText, response.toolCalls[0].resultText)
        assertEquals(planResultText, response.toolCalls[1].resultText)
        assertEquals(saveResultText, response.toolCalls[2].resultText)

        assertEquals("Saved your leg workout plan as \"Leg Day\".", response.text)
    }
}
