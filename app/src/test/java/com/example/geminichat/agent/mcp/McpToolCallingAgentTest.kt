package com.example.geminichat.agent.mcp

import com.example.geminichat.GeminiFunctionTool
import com.example.geminichat.InteractionResponse
import com.example.geminichat.InteractionStep
import com.example.geminichat.StepContent
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.mcp.McpConnectionException
import com.example.geminichat.mcp.McpGateway
import com.example.geminichat.mcp.McpServerInfo
import com.example.geminichat.mcp.McpToolCallException
import com.example.geminichat.mcp.McpToolCallResult
import com.example.geminichat.mcp.McpToolInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake [McpGateway] so [McpToolCallingAgent] can be tested with no network/SDK. */
private class FakeMcpGateway(
    private val tools: List<McpToolInfo>,
    private val toolResult: McpToolCallResult = McpToolCallResult(text = "ok", isError = false),
    private val toolFailure: McpToolCallException? = null
) : McpGateway {
    var connectCallCount = 0
        private set
    var lastCallToolName: String? = null
        private set
    var lastCallToolArguments: Map<String, Any?>? = null
        private set

    override suspend fun connect(serverUrl: String): McpServerInfo {
        connectCallCount++
        return McpServerInfo(name = "fitness-mcp", version = "1.0.0", capabilities = listOf("tools"), instructions = null)
    }

    override suspend fun listTools(): List<McpToolInfo> = tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        lastCallToolName = name
        lastCallToolArguments = arguments
        toolFailure?.let { throw it }
        return toolResult
    }

    override suspend fun close() {}
}

/** Fake [ToolCallingLlmClient] returning a scripted sequence of [InteractionResponse]s. */
private class FakeToolCallingLlmClient(
    private val responses: List<Result<InteractionResponse>>
) : ToolCallingLlmClient {
    var callCount = 0
        private set
    val seenTools = mutableListOf<List<GeminiFunctionTool>?>()
    val seenPreviousInteractionIds = mutableListOf<String?>()

    override suspend fun createInteraction(
        model: String,
        input: kotlinx.serialization.json.JsonElement,
        systemInstruction: String?,
        tools: List<GeminiFunctionTool>?,
        previousInteractionId: String?
    ): Result<InteractionResponse> {
        val response = responses[callCount]
        seenTools += tools
        seenPreviousInteractionIds += previousInteractionId
        callCount++
        return response
    }
}

class McpToolCallingAgentTest {

    private val config = AgentConfig(
        id = "fitness-mcp-coach",
        displayName = "Fitness Coach",
        description = "test",
        systemInstruction = "You are a fitness coach."
    )

    private val exerciseTool = McpToolInfo(
        name = "get_exercise_info",
        title = "Get exercise info",
        description = "Looks up an exercise",
        parameters = emptyList(),
        rawInputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
        }
    )

    private fun completedResponse(text: String, id: String = "interaction-1") = InteractionResponse(
        id = id,
        status = "completed",
        steps = listOf(InteractionStep(type = "model_output", content = listOf(StepContent(type = "text", text = text))))
    )

    private fun requiresActionResponse(
        toolName: String,
        callId: String = "call-1",
        arguments: JsonObject = buildJsonObject { put("name", JsonPrimitive("push up")) },
        id: String = "interaction-1"
    ) = InteractionResponse(
        id = id,
        status = "requires_action",
        steps = listOf(InteractionStep(type = "function_call", id = callId, name = toolName, arguments = arguments))
    )

    @Test
    fun `answers directly without calling any tool when the model does not need one`() = runTest {
        val mcpGateway = FakeMcpGateway(tools = listOf(exerciseTool))
        val llmClient = FakeToolCallingLlmClient(responses = listOf(Result.success(completedResponse("Push-ups work your chest."))))
        val agent = McpToolCallingAgent(config, llmClient, mcpGateway, serverUrl = "https://example.test/mcp")

        val result = agent.handle(AgentRequest(userMessage = "What do push-ups work?"))

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("Push-ups work your chest.", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(1, llmClient.callCount)
        assertEquals(1, mcpGateway.connectCallCount)
    }

    @Test
    fun `calls the MCP tool and resubmits its result to answer`() = runTest {
        val mcpGateway = FakeMcpGateway(
            tools = listOf(exerciseTool),
            toolResult = McpToolCallResult(text = "{\"name\":\"Push Up\",\"category\":\"Chest\"}", isError = false)
        )
        val llmClient = FakeToolCallingLlmClient(
            responses = listOf(
                Result.success(requiresActionResponse(toolName = "get_exercise_info")),
                Result.success(completedResponse("Push-ups target your chest.", id = "interaction-2"))
            )
        )
        val agent = McpToolCallingAgent(config, llmClient, mcpGateway, serverUrl = "https://example.test/mcp")

        val result = agent.handle(AgentRequest(userMessage = "Tell me about push ups"))

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("Push-ups target your chest.", response.text)
        assertEquals(1, response.toolCalls.size)
        assertEquals("get_exercise_info", response.toolCalls.first().toolName)
        assertEquals("{\"name\":\"Push Up\",\"category\":\"Chest\"}", response.toolCalls.first().resultText)
        assertFalse(response.toolCalls.first().isError)

        assertEquals(2, llmClient.callCount)
        assertEquals("interaction-1", llmClient.seenPreviousInteractionIds[1])
        assertEquals("get_exercise_info", mcpGateway.lastCallToolName)
        assertEquals("push up", mcpGateway.lastCallToolArguments?.get("name"))
    }

    @Test
    fun `surfaces a tool failure to the model instead of crashing`() = runTest {
        val mcpGateway = FakeMcpGateway(
            tools = listOf(exerciseTool),
            toolFailure = McpToolCallException("server unreachable")
        )
        val llmClient = FakeToolCallingLlmClient(
            responses = listOf(
                Result.success(requiresActionResponse(toolName = "get_exercise_info")),
                Result.success(completedResponse("Sorry, I couldn't look that up.", id = "interaction-2"))
            )
        )
        val agent = McpToolCallingAgent(config, llmClient, mcpGateway, serverUrl = "https://example.test/mcp")

        val result = agent.handle(AgentRequest(userMessage = "Tell me about push ups"))

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(1, response.toolCalls.size)
        assertTrue(response.toolCalls.first().isError)
    }

    @Test
    fun `fails when the MCP server cannot be reached`() = runTest {
        val mcpGateway = object : McpGateway {
            override suspend fun connect(serverUrl: String): McpServerInfo =
                throw McpConnectionException("down")
            override suspend fun listTools(): List<McpToolInfo> = emptyList()
            override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult =
                error("not reached")
            override suspend fun close() {}
        }
        val llmClient = FakeToolCallingLlmClient(responses = emptyList())
        val agent = McpToolCallingAgent(config, llmClient, mcpGateway, serverUrl = "https://example.test/mcp")

        val result = agent.handle(AgentRequest(userMessage = "hi"))

        assertTrue(result.isFailure)
        assertEquals(0, llmClient.callCount)
    }

    @Test
    fun `rejects a blank message without calling the model`() = runTest {
        val mcpGateway = FakeMcpGateway(tools = listOf(exerciseTool))
        val llmClient = FakeToolCallingLlmClient(responses = emptyList())
        val agent = McpToolCallingAgent(config, llmClient, mcpGateway, serverUrl = "https://example.test/mcp")

        val result = agent.handle(AgentRequest(userMessage = "   "))

        assertTrue(result.isFailure)
        assertEquals(0, llmClient.callCount)
        assertEquals(0, mcpGateway.connectCallCount)
    }

    @Test
    fun `reuses the cached connection across multiple handle calls`() = runTest {
        val mcpGateway = FakeMcpGateway(tools = listOf(exerciseTool))
        val llmClient = FakeToolCallingLlmClient(
            responses = listOf(
                Result.success(completedResponse("First answer.")),
                Result.success(completedResponse("Second answer.", id = "interaction-2"))
            )
        )
        val agent = McpToolCallingAgent(config, llmClient, mcpGateway, serverUrl = "https://example.test/mcp")

        agent.handle(AgentRequest(userMessage = "first"))
        agent.handle(AgentRequest(userMessage = "second"))

        assertEquals(1, mcpGateway.connectCallCount)
    }
}
