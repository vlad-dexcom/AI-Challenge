package com.example.geminichat.agent.mcp

import com.example.geminichat.FunctionResultInput
import com.example.geminichat.GeminiFunctionTool
import com.example.geminichat.InteractionResponse
import com.example.geminichat.agent.Agent
import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.AgentResponse
import com.example.geminichat.agent.TokenEstimator
import com.example.geminichat.agent.TokenUsage
import com.example.geminichat.mcp.McpConnectionException
import com.example.geminichat.mcp.McpGateway
import com.example.geminichat.mcp.McpToolCallException
import com.example.geminichat.mcp.McpToolCallResult
import com.example.geminichat.mcp.McpToolInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull

/**
 * One completed tool call made while answering a single [Agent.handle] request — surfaced on
 * [AgentResponse] (see [AgentResponse.toolCalls]) so the UI/logs can show the user *which* MCP
 * tool ran and what it returned, satisfying the Day 17 exercise's "получите и используйте
 * результат" (get and use the result), not just "call the tool".
 */
data class ToolInvocation(
    val toolName: String,
    val arguments: Map<String, JsonElement>,
    val resultText: String,
    val isError: Boolean
)

/**
 * Day 17's [Agent]: talks to Gemini with [mcpTools] declared as function-calling tools; when
 * the model responds with `status == "requires_action"`, it invokes the matching tool(s) on
 * [mcpGateway] (a real remote MCP server — see `mcp-server/functions`, deployed as a Firebase
 * Cloud Function wrapping the wger.de fitness API) and resubmits the result(s) so the model can
 * produce its final answer. This is the client-side function-calling loop (as opposed to
 * the Interactions API's alternate, server-side `type: "mcp_server"` tool) because the
 * exercise asks for *the app* to call the tool and use its result.
 *
 * [mcpGateway] is connected lazily on first use and the connection/tool list is cached for the
 * lifetime of this agent instance (an MCP `initialize` + `tools/list` round trip per chat
 * message would be wasteful) — see [ensureConnected]. A dropped connection surfaces as an
 * ordinary [Result.failure] on the next [handle] call, retried automatically.
 */
class McpToolCallingAgent(
    override val config: AgentConfig,
    private val client: ToolCallingLlmClient,
    private val mcpGateway: McpGateway,
    private val serverUrl: String,
    private val maxToolRounds: Int = 4
) : Agent {

    private val connectionMutex = Mutex()
    private var cachedTools: List<McpToolInfo>? = null

    override suspend fun handle(request: AgentRequest): Result<AgentResponse> {
        val userMessage = request.userMessage.trim()
        if (userMessage.isEmpty()) {
            return Result.failure(IllegalArgumentException("Please enter a message."))
        }

        val tools = try {
            ensureConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpConnectionException) {
            return Result.failure(e)
        }

        val geminiTools = tools.map { it.toGeminiFunctionTool() }
        val startedAt = System.currentTimeMillis()
        val toolInvocations = mutableListOf<ToolInvocation>()

        var response = client.createInteraction(
            model = request.modelOverride ?: config.model,
            input = JsonPrimitive(userMessage),
            systemInstruction = config.systemInstruction,
            tools = geminiTools
        ).getOrElse { return Result.failure(it) }

        var rounds = 0
        while (response.status == "requires_action" && rounds < maxToolRounds) {
            rounds++
            val calls = response.steps.orEmpty().filter { it.type == "function_call" }
            if (calls.isEmpty()) break

            val results = calls.map { step ->
                val toolName = step.name
                val callId = step.id
                if (toolName == null || callId == null) {
                    return Result.failure(
                        Exception("Gemini requested a tool call missing a name or id.")
                    )
                }
                val arguments = step.arguments.orEmpty()
                val callResult: McpToolCallResult = try {
                    mcpGateway.callTool(toolName, arguments.mapValues { it.value.jsonElementToPlain() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: McpToolCallException) {
                    McpToolCallResult(text = e.message ?: "Tool call failed.", isError = true)
                }
                toolInvocations += ToolInvocation(
                    toolName = toolName,
                    arguments = arguments,
                    resultText = callResult.text,
                    isError = callResult.isError
                )
                FunctionResultInput(
                    callId = callId,
                    name = toolName,
                    result = JsonPrimitive(callResult.text),
                    isError = callResult.isError
                )
            }

            val nextInput = buildJsonArray {
                results.forEach { add(kotlinx.serialization.json.Json.encodeToJsonElement(it)) }
            }
            response = client.createInteraction(
                model = request.modelOverride ?: config.model,
                input = nextInput,
                tools = geminiTools,
                previousInteractionId = response.id
            ).getOrElse { return Result.failure(it) }
        }

        val elapsedMs = System.currentTimeMillis() - startedAt
        val answer = response.extractFinalText()
            ?: return Result.failure(Exception("${config.displayName} returned an empty response."))

        val requestTokens = TokenEstimator.estimate(userMessage)
        val systemInstructionTokens = TokenEstimator.estimate(config.systemInstruction)
        val completionTokens = TokenEstimator.estimate(answer)

        return Result.success(
            AgentResponse(
                text = answer,
                agentId = config.id,
                model = request.modelOverride ?: config.model,
                elapsedMs = elapsedMs,
                tokenUsage = TokenUsage(
                    requestTokens = requestTokens,
                    historyTokens = 0,
                    systemInstructionTokens = systemInstructionTokens,
                    promptTokens = requestTokens + systemInstructionTokens,
                    completionTokens = completionTokens
                ),
                toolCalls = toolInvocations
            )
        )
    }

    /** Connects to [serverUrl] and lists its tools on first call; reuses the result afterwards. */
    private suspend fun ensureConnected(): List<McpToolInfo> = connectionMutex.withLock {
        cachedTools?.let { return@withLock it }
        mcpGateway.connect(serverUrl)
        val tools = mcpGateway.listTools()
        cachedTools = tools
        tools
    }
}

private fun McpToolInfo.toGeminiFunctionTool(): GeminiFunctionTool = GeminiFunctionTool(
    name = name,
    description = description,
    parameters = rawInputSchema
)

/** Best-effort conversion of an argument value from Gemini back to a plain Kotlin value for the MCP SDK's `callTool`. */
private fun JsonElement.jsonElementToPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
    is kotlinx.serialization.json.JsonArray -> map { it.jsonElementToPlain() }
    is kotlinx.serialization.json.JsonObject -> mapValues { it.value.jsonElementToPlain() }
}


/** Extracts the model's final text answer from a `"completed"` [InteractionResponse]. */
private fun InteractionResponse.extractFinalText(): String? {
    if (status != null && status != "completed") return null
    return steps
        ?.firstOrNull { it.type == "model_output" }
        ?.content
        ?.filter { it.type == "text" }
        ?.mapNotNull { it.text }
        ?.joinToString("")
        ?.takeIf { it.isNotBlank() }
}
