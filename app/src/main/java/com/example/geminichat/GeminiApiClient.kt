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
    /**
     * Result of a single model call: the answer text, token usage (if reported), and
     * client-measured round-trip latency in milliseconds.
     */
    data class ModelRunResult(
        val answer: String,
        val usage: Usage?,
        val latencyMs: Long
    )

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

    suspend fun sendMessage(prompt: String, model: String): Result<ModelRunResult> {
        if (apiKey.isBlank()) {
            return Result.failure(
                Exception("No Gemini API key configured. Set GEMINI_API_KEY in local.properties.")
            )
        }

        val startNanos = System.nanoTime()
        return try {
            val httpResponse: HttpResponse = client.post {
                url(endpoint)
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(InteractionRequest(model = model, input = prompt))
            }

            val latencyMs = (System.nanoTime() - startNanos) / 1_000_000
            parseResponse(httpResponse, latencyMs)
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

    private suspend fun parseResponse(httpResponse: HttpResponse, latencyMs: Long): Result<ModelRunResult> {
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
            Result.success(ModelRunResult(answer = answer, usage = response.usage, latencyMs = latencyMs))
        }
    }

    fun close() = client.close()
}
