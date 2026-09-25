package com.example.geminichat.agent.mcp

import com.example.geminichat.GeminiFunctionTool
import com.example.geminichat.InteractionResponse
import kotlinx.serialization.json.JsonElement

/**
 * Day 17: the subset of [com.example.geminichat.GeminiApiClient] that [McpToolCallingAgent]
 * needs to drive a function-calling loop — separated from [com.example.geminichat.agent.LlmClient]
 * (which only returns final text) because tool-calling needs the *raw* [InteractionResponse]
 * (to see `status == "requires_action"` and its `function_call` steps) and needs to send
 * [tools] plus [previousInteractionId] to continue an interaction. Kept as its own small
 * interface — rather than added to [com.example.geminichat.agent.LlmClient] — so every other
 * agent/test is unaffected, and so [McpToolCallingAgent] can be unit-tested against a fake.
 */
interface ToolCallingLlmClient {
    suspend fun createInteraction(
        model: String,
        input: JsonElement,
        systemInstruction: String? = null,
        tools: List<GeminiFunctionTool>? = null,
        previousInteractionId: String? = null
    ): Result<InteractionResponse>
}
