package com.example.geminichat

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    // The following are only set when "restricted mode" is enabled in the UI; a plain
    // request omits them entirely and behaves exactly like the original free-form chat.
    @SerialName("system_instruction") val systemInstruction: String? = null,
    @SerialName("generation_config") val generationConfig: GenerationConfig? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

/**
 * Configuration parameters for a model interaction.
 * See https://ai.google.dev/api/interactions-api (GenerationConfig fields).
 */
@Serializable
data class GenerationConfig(
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    @SerialName("thinking_level") val thinkingLevel: String? = null
)

/**
 * Requests structured JSON output constrained to [schema].
 * See https://ai.google.dev/gemini-api/docs/structured-output
 *
 * [type] and [mimeType] are marked @EncodeDefault so they're always serialized even though
 * they carry default values — kotlinx.serialization otherwise *omits* default-valued fields
 * when encoding (the global Json config here doesn't set encodeDefaults = true), which
 * previously caused the API to reject the request with "'type' parameter is required at
 * 'response_format'" because the field was silently missing from the outgoing JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseFormat(
    @EncodeDefault val type: String = "text",
    @EncodeDefault @SerialName("mime_type") val mimeType: String = "application/json",
    val schema: JsonElement
)

/**
 * Parsed shape of the model's answer when restricted mode is on and the model is asked
 * to return JSON matching [GeminiApiClient.RESTRICTED_RESPONSE_SCHEMA_JSON].
 *
 * Not currently deserialized anywhere ([GeminiApiClient.parseResponse] surfaces the raw JSON
 * string as-is for verification) — kept accurate so it's ready to use once real parsing/UI
 * consumption of the structured output is wired up.
 */
@Serializable
data class RestrictedAnswer(
    val message: String = "",
    val trainings: List<Training> = emptyList()
)

/** A single planned/suggested training session within a [RestrictedAnswer]. */
@Serializable
data class Training(
    @SerialName("day_of_week") val dayOfWeek: String = "",
    val focus: String = "",
    val exercises: List<Exercise> = emptyList(),
    val notes: String? = null
)

/** A single exercise within a [Training]. */
@Serializable
data class Exercise(
    val name: String = "",
    val sets: Int = 0,
    val reps: String = "",
    @SerialName("rest_seconds") val restSeconds: Int? = null
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
