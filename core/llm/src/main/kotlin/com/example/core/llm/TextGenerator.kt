package com.example.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import com.example.core.platform.defaultHttpEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Minimal "prompt in, text out" LLM abstraction so the RAG pipeline stays independent of any provider (the app adapts its own `LlmClient`). */
fun interface TextGenerator {
    suspend fun generate(systemInstruction: String?, prompt: String): Result<String>

    /**
     * Same, with per-call [options]. The default ignores them, so implementations over any endpoint
     * (generateContent here, the Interactions API in `:app`) stay valid; [GeminiTextGenerator] honours them.
     */
    suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> =
        generate(systemInstruction, prompt)
}

/** [temperature] null = the generator's own default; [json] asks for a JSON-only reply (Day 24 structured output). */
data class GenerationOptions(val temperature: Double? = null, val json: Boolean = false)

/** Plain-REST Gemini `generateContent` client (no SDK) used by the CLI. Retries 429/5xx with exponential backoff. */
class GeminiTextGenerator(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val temperature: Double = 0.2,
    private val maxRetries: Int = 4,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    engine: HttpClientEngine = defaultHttpEngine(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : TextGenerator {
    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000; connectTimeoutMillis = 30_000 }
    }

    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> =
        generate(systemInstruction, prompt, GenerationOptions())

    override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("GEMINI_API_KEY is not set"))
        val body = Request(
            contents = listOf(Content(listOf(Part(prompt)))),
            systemInstruction = systemInstruction?.let { Content(listOf(Part(it))) },
            generationConfig = GenerationConfig(options.temperature ?: temperature, if (options.json) "application/json" else null),
        )
        var attempt = 0
        while (true) {
            try {
                val response = http.post("$baseUrl/models/$model:generateContent") {
                    contentType(ContentType.Application.Json)
                    header("x-goog-api-key", apiKey)
                    setBody(body)
                }
                if (response.status.isSuccess()) {
                    val text = json.decodeFromString<Response>(response.bodyAsText())
                        .candidates.firstOrNull()?.content?.parts?.mapNotNull { it.text }?.joinToString("")?.trim()
                    return if (text.isNullOrEmpty()) Result.failure(IllegalStateException("Gemini returned an empty answer"))
                    else Result.success(text)
                }
                val retryable = response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
                if (!retryable || attempt >= maxRetries) {
                    return Result.failure(IllegalStateException("Gemini HTTP ${response.status.value}: ${response.bodyAsText().take(300)}"))
                }
            } catch (e: kotlinx.io.IOException) {
                if (attempt >= maxRetries) return Result.failure(e)
            }
            sleep(1000L shl attempt++)
        }
    }

    @Serializable private data class Part(val text: String? = null)
    @Serializable private data class Content(val parts: List<Part> = emptyList())
    @Serializable private data class GenerationConfig(val temperature: Double, val responseMimeType: String? = null)
    @Serializable private data class Request(
        val contents: List<Content>,
        val systemInstruction: Content? = null,
        val generationConfig: GenerationConfig,
    )
    @Serializable private data class Candidate(val content: Content? = null)
    @Serializable private data class Response(val candidates: List<Candidate> = emptyList())
}
