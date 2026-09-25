package com.example.geminichat

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Minimal request/response models for the Gemini **Interactions API**
 * (https://ai.google.dev/api/interactions-api), which supersedes the legacy
 * `generateContent` endpoint and is Google's recommended REST interface as of 2026.
 *
 * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/interactions
 */

@Serializable
data class InteractionRequest(
    val model: String,
    /**
     * The Interactions API accepts either a plain string (a fresh user turn) or an array of
     * typed step objects — Day 17 needs the latter to send a [FunctionResultInput] back after
     * a [InteractionStep] of `type == "function_call"`. [JsonElement] covers both without a
     * second, mutually-exclusive field. See [GeminiApiClient.complete] (string case) and
     * [GeminiApiClient.createInteraction]/`agent/mcp/McpToolCallingAgent` (array case).
     */
    val input: JsonElement,
    // Only set when the caller (an Agent) provides a persona/generation config; a plain
    // request without them behaves exactly like the original free-form chat.
    @SerialName("system_instruction") val systemInstruction: String? = null,
    @SerialName("generation_config") val generationConfig: GenerationConfig? = null,
    /** Day 17: function-calling tool declarations offered to the model, if any. */
    val tools: List<GeminiFunctionTool>? = null,
    /**
     * Day 17: set when this request continues a prior interaction (e.g. after a
     * `function_call` step) instead of starting a new one — the id of that prior
     * [InteractionResponse].
     */
    @SerialName("previous_interaction_id") val previousInteractionId: String? = null
)

/**
 * Configuration parameters for a model interaction.
 * See https://ai.google.dev/api/interactions-api (GenerationConfig fields).
 */
@Serializable
data class GenerationConfig(
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null
)

/**
 * Day 17: a single `type: "function"` tool declaration, as documented for the Interactions
 * API's `tools` request field. [parameters] is a JSON Schema object (typically an MCP tool's
 * `inputSchema`, passed through as-is — see `McpToolInfo.rawInputSchema`).
 */
@Serializable
data class GeminiFunctionTool(
    @EncodeDefault val type: String = "function",
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null
)

/**
 * Day 17: sent back as (an element of) a follow-up [InteractionRequest.input] array to answer
 * a `function_call` step, per the Interactions API's `FunctionResultStep` resource: `call_id`
 * must match the originating [InteractionStep.id].
 */
@Serializable
data class FunctionResultInput(
    @EncodeDefault val type: String = "function_result",
    @SerialName("call_id") val callId: String,
    val name: String? = null,
    val result: JsonElement,
    @SerialName("is_error") val isError: Boolean? = null
)

@Serializable
data class InteractionResponse(
    val id: String? = null,
    val status: String? = null,
    val steps: List<InteractionStep>? = null,
    val error: GeminiError? = null
)

@Serializable
data class InteractionStep(
    val type: String? = null,
    val content: List<StepContent>? = null,
    /** Present on a `type == "function_call"` step: the tool-call id (`FunctionCallStep.id`). */
    val id: String? = null,
    /** Present on a `type == "function_call"` step: the tool's name. */
    val name: String? = null,
    /** Present on a `type == "function_call"` step: the arguments to call it with. */
    val arguments: JsonObject? = null
)

@Serializable
data class StepContent(
    val type: String? = null,
    val text: String? = null
)

@Serializable
data class GeminiError(
    // Note: `code` is intentionally omitted — Google's error payloads inconsistently send it
    // as either an int (standard REST errors) or a string like "api_error" (model-serving
    // errors), and we don't use it for logic beyond the HTTP status code we already inspect.
    val message: String? = null,
    val status: String? = null
)
