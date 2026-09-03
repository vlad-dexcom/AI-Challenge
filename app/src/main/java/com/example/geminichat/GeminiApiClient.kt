package com.example.geminichat

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * A single, independently toggleable request-level limitation the user can apply on top of a
 * plain chat message. Each one maps to a distinct piece of the Interactions API request; any
 * combination may be selected at once. Applies only while restricted mode's master switch is
 * on; structured JSON output is always requested whenever restricted mode is on (see
 * [GeminiApiClient.sendMessage]) rather than being a separately selectable chip.
 */
enum class Limitation(val label: String) {
    TOPIC_RESTRICTION("Physical training topics only"),
    MAX_OUTPUT_TOKENS("Max 256 output tokens"),
    STOP_SEQUENCES("Stop sequences: \\n\\n\\n, [END]")
}

/**
 * Plain REST client for the Gemini **Interactions API** (no Gemini SDK). Uses Ktor's
 * HttpClient to POST to the `interactions` endpoint and parses the JSON response manually.
 *
 * This targets the Interactions API (https://ai.google.dev/api/interactions-api), Google's
 * current recommended REST interface, which supersedes the legacy `generateContent` endpoint.
 *
 * Every failure path (missing API key, no network, timeout, non-2xx HTTP status,
 * malformed/empty payload, non-"completed" interaction status) is turned into a
 * [Result.failure] with a user-friendly message so the caller can always show
 * *something* to the user instead of failing silently or crashing.
 */
class GeminiApiClient(
    private val apiKey: String
) {
    companion object {
        /**
         * Curated list of Gemini models selectable in the UI. Useful when a specific model
         * is temporarily overloaded or rate-limited — the user can just switch models.
         */
        val AVAILABLE_MODELS = listOf(
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-2.5-pro",
            "gemini-3-pro",
        )
        const val DEFAULT_MODEL = "gemini-3.5-flash"

        // --- "Restricted mode" (physical-training-only) settings. Each Limitation is applied
        // independently, only when the user has selected it (and the master switch is on) in
        // the UI, so any combination of limitations can be requested. ---

        private const val SYSTEM_INSTRUCTION_BASE =
            "You are a Physical Training Assistant. You ONLY discuss physical training " +
                "topics: workouts, exercises, training programs, technique, warm-up/cool-down, " +
                "recovery, and general fitness safety. If the user asks about anything else, " +
                "politely decline and steer the conversation back to physical training instead " +
                "of answering the unrelated request. Keep answers concise."

        private const val SYSTEM_INSTRUCTION_JSON_SUFFIX =
            " Always respond with a single JSON object matching the required schema: a " +
                "\"message\" field with the plain-text/Markdown reply to show the user, and a " +
                "\"trainings\" field listing any concrete training sessions you're suggesting " +
                "(empty array if none apply to this reply)."

        private const val SYSTEM_INSTRUCTION_JSON_ONLY =
            "Always respond with a single JSON object matching the required schema: a " +
                "\"message\" field with the plain-text/Markdown reply to show the user, and a " +
                "\"trainings\" field listing any concrete training sessions you're suggesting " +
                "(empty array if none apply to this reply)."

        private const val MAX_OUTPUT_TOKENS = 256

        private val STOP_SEQUENCES = listOf("\n\n\n", "[END]")

        /** JSON Schema enforced on the model's output whenever restricted mode is on. */
        private const val RESTRICTED_RESPONSE_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "message": {
                  "type": "string",
                  "description": "The plain reply to show the user, formatted as Markdown"
                },
                "trainings": {
                  "type": "array",
                  "description": "Concrete training sessions being suggested, if any",
                  "items": {
                    "type": "object",
                    "properties": {
                      "day_of_week": {
                        "type": "string",
                        "description": "e.g. Monday, Tuesday"
                      },
                      "focus": {
                        "type": "string",
                        "description": "e.g. Upper Body Strength, Cardio, Rest Day"
                      },
                      "exercises": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "name": { "type": "string" },
                            "sets": { "type": "integer" },
                            "reps": {
                              "type": "string",
                              "description": "e.g. \"10-12\" or \"30 seconds\""
                            },
                            "rest_seconds": { "type": "integer" }
                          },
                          "required": ["name", "sets", "reps"]
                        }
                      },
                      "notes": { "type": "string" }
                    },
                    "required": ["day_of_week", "focus", "exercises"]
                  }
                }
              },
              "required": ["message", "trainings"]
            }
        """
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val restrictedResponseSchema = json.parseToJsonElement(RESTRICTED_RESPONSE_SCHEMA_JSON)

    private val client = HttpClient(OkHttp) {
        expectSuccess = false // we inspect the status code ourselves for better messages

        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    }

    private val endpoint = "https://generativelanguage.googleapis.com/v1beta/interactions"

    suspend fun sendMessage(
        prompt: String,
        model: String,
        restrictedModeEnabled: Boolean,
        limitations: Set<Limitation> = emptySet()
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(
                Exception("No Gemini API key configured. Set GEMINI_API_KEY in local.properties.")
            )
        }

        // Structured JSON output is always required whenever restricted mode is on, regardless
        // of which of the other limitation chips are individually selected — it's what lets the
        // rest of the app parse the reply later, not an optional/user-facing chip.
        val jsonOutputRequested = restrictedModeEnabled

        return try {
            val httpResponse: HttpResponse = client.post {
                url(endpoint)
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    InteractionRequest(
                        model = model,
                        input = prompt,
                        systemInstruction = buildSystemInstruction(restrictedModeEnabled, limitations),
                        generationConfig = buildGenerationConfig(limitations),
                        responseFormat = if (jsonOutputRequested) {
                            ResponseFormat(schema = restrictedResponseSchema)
                        } else {
                            null
                        }
                    )
                )
            }

            parseResponse(httpResponse, jsonOutputRequested)
        } catch (e: HttpRequestTimeoutException) {
            Result.failure(Exception("Request timed out. Please try again."))
        } catch (e: UnresolvedAddressException) {
            Result.failure(Exception("No internet connection. Please check your network and try again."))
        } catch (e: UnknownHostException) {
            Result.failure(Exception("No internet connection. Please check your network and try again."))
        } catch (e: SerializationException) {
            Result.failure(Exception("Received an unexpected response from Gemini. Please try again."))
        } catch (e: IOException) {
            Result.failure(Exception("Network error: ${e.message ?: "please check your connection and try again."}"))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Something went wrong. Please try again."))
        }
    }

    private fun buildSystemInstruction(restrictedModeEnabled: Boolean, limitations: Set<Limitation>): String? {
        if (!restrictedModeEnabled) return null
        val topicRestricted = Limitation.TOPIC_RESTRICTION in limitations
        // JSON output is always required in restricted mode, so the model always needs to be
        // told to emit schema-conforming JSON, with or without the topic-restriction persona.
        return if (topicRestricted) {
            SYSTEM_INSTRUCTION_BASE + SYSTEM_INSTRUCTION_JSON_SUFFIX
        } else {
            SYSTEM_INSTRUCTION_JSON_ONLY
        }
    }

    private fun buildGenerationConfig(limitations: Set<Limitation>): GenerationConfig? {
        val maxTokensRequested = Limitation.MAX_OUTPUT_TOKENS in limitations
        val stopSequencesRequested = Limitation.STOP_SEQUENCES in limitations
        if (!maxTokensRequested && !stopSequencesRequested) return null

        return GenerationConfig(
            maxOutputTokens = if (maxTokensRequested) MAX_OUTPUT_TOKENS else null,
            stopSequences = if (stopSequencesRequested) STOP_SEQUENCES else null,
            // Only needed when capping output tokens: without it, gemini-3.5-flash can spend
            // the whole budget on internal "thought" tokens and leave none for the answer.
            thinkingLevel = if (maxTokensRequested) "minimal" else null
        )
    }

    private suspend fun parseResponse(httpResponse: HttpResponse, jsonOutputRequested: Boolean): Result<String> {
        val bodyText = try {
            httpResponse.bodyAsText()
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to read Gemini response."))
        }

        val response: InteractionResponse? = try {
            if (bodyText.isBlank()) null else json.decodeFromString(InteractionResponse.serializer(), bodyText)
        } catch (e: SerializationException) {
            null
        }

        if (!httpResponse.status.isSuccess()) {
            val apiMessage = response?.error?.message
            val message = when (httpResponse.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                    "Invalid or missing Gemini API key."
                HttpStatusCode.TooManyRequests ->
                    "Rate limit exceeded. Please wait a moment and try again."
                else -> apiMessage ?: "Gemini API error (${httpResponse.status.value})."
            }
            return Result.failure(Exception(message))
        }

        response?.error?.let {
            return Result.failure(Exception(it.message ?: "Gemini API error"))
        }

        if (response == null) {
            return Result.failure(Exception("Received an unexpected response from Gemini."))
        }

        // "incomplete" means generation stopped early (e.g. hit max_output_tokens or a stop
        // sequence) — the model_output step still has useful (possibly truncated) text, so we
        // don't treat it as a hard failure; only genuinely failed/cancelled statuses are errors.
        if (response.status != null && response.status != "completed" && response.status != "incomplete") {
            return Result.failure(Exception("Gemini did not complete the request (status: ${response.status})."))
        }

        val rawAnswer = response.steps
            ?.firstOrNull { it.type == "model_output" }
            ?.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString("")

        if (rawAnswer.isNullOrBlank()) {
            return Result.failure(Exception("Gemini returned an empty response."))
        }

        if (!jsonOutputRequested) {
            return Result.success(rawAnswer)
        }

        // TODO: once JSON output is consumed elsewhere in the app (e.g. to drive structured
        // UI), parse rawAnswer into RestrictedAnswer here instead of returning it verbatim.
        // For now, surface the raw JSON string as-is so it can be visually verified end-to-end.
        return Result.success(rawAnswer)
    }

    /**
     * Best-effort extraction of the `"message"` field's text from a (possibly truncated) JSON
     * blob, for when [rawAnswer] doesn't fully parse as [RestrictedAnswer]. Handles a missing
     * closing quote/brace and unescapes common JSON string escapes.
     *
     * Currently unused: [parseResponse] surfaces the raw JSON string as-is for verification.
     * Kept for when the app starts parsing/consuming the structured JSON output instead.
     */
    @Suppress("unused")
    private fun extractMessageFallback(rawAnswer: String): String? {
        val match = Regex("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"?").find(rawAnswer)
            ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    fun close() = client.close()
}
