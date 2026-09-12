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
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

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
 *
 * Implements [LlmClient] so it can be plugged into an [com.example.geminichat.agent.Agent]
 * without the agent layer knowing anything about Gemini, Ktor, or REST.
 */
class GeminiApiClient(
    private val apiKey: String,
) : LlmClient {
    companion object {
        /**
         * Curated list of Gemini models selectable in the UI. Useful when a specific model
         * is temporarily overloaded or rate-limited — the user can just switch models.
         */
        val AVAILABLE_MODELS = listOf(
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-2.5-pro",
            "gemini-3-pro",
        )
        const val DEFAULT_MODEL = "gemini-3.5-flash"

        /**
         * Approximate published context windows (in tokens) per model. These are used only as
         * a local guard in [LlmAgent] to preemptively refuse an over-budget conversation before
         * an API call is made — not billed/authoritative numbers from Google.
         */
        private val CONTEXT_WINDOW_TOKENS = mapOf(
            "gemini-3.5-flash" to 1_000_000,
            // TODO return back normal tokens quantity after tests
            "gemini-3.5-flash-lite" to 8_000,
            "gemini-3.6-flash" to 1_000_000,
            "gemini-3.7-flash" to 1_000_000,
            "gemini-2.5-pro" to 2_000_000,
            "gemini-3-pro" to 2_000_000,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

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

    override fun contextWindowTokens(model: String): Int =
        CONTEXT_WINDOW_TOKENS[model] ?: LlmClient.DEFAULT_CONTEXT_WINDOW_TOKENS

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(
                Exception("No Gemini API key configured. Set GEMINI_API_KEY in local.properties.")
            )
        }

        val generationConfig = if (spec.maxOutputTokens != null || spec.temperature != null) {
            GenerationConfig(maxOutputTokens = spec.maxOutputTokens, temperature = spec.temperature)
        } else {
            null
        }

        return try {
            val httpResponse: HttpResponse = client.post {
                url(endpoint)
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    InteractionRequest(
                        model = spec.model,
                        input = spec.input,
                        systemInstruction = spec.systemInstruction,
                        generationConfig = generationConfig
                    )
                )
            }

            parseResponse(httpResponse)
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

    private suspend fun parseResponse(httpResponse: HttpResponse): Result<String> {
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

        if (response.status != null && response.status != "completed") {
            return Result.failure(Exception("Gemini did not complete the request (status: ${response.status})."))
        }

        val answer = response.steps
            ?.firstOrNull { it.type == "model_output" }
            ?.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString("")

        return if (answer.isNullOrBlank()) {
            Result.failure(Exception("Gemini returned an empty response."))
        } else {
            Result.success(answer)
        }
    }

    fun close() = client.close()
}
