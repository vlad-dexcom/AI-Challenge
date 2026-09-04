package com.example.geminichat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val input: String,
    @SerialName("generation_config") val generationConfig: GenerationConfig? = null
)

/**
 * Sampling controls for the interaction. `temperature` is the only knob this app exposes:
 * it scales the randomness of token selection (0 = deterministic, higher = more random/creative).
 */
@Serializable
data class GenerationConfig(
    val temperature: Double
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
    val content: List<StepContent>? = null
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
