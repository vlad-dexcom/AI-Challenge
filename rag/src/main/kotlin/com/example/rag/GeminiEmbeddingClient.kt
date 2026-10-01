package com.example.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plain-REST client for the Gemini embeddings API (`models/{model}:batchEmbedContents`,
 * https://ai.google.dev/api/embeddings) - no SDK, same Ktor style as the chat `GeminiApiClient`.
 *
 *  - Inputs are sent in batches of at most [batchSize] (the API caps a batch at 100 requests).
 *  - [EmbeddingTaskType.RETRIEVAL_DOCUMENT] vs `RETRIEVAL_QUERY` is passed as `taskType`.
 *  - HTTP 429 and 5xx (and I/O errors) are retried with exponential backoff up to [maxRetries].
 *  - Vectors are requested at [dimension] dims via `outputDimensionality` and L2-normalised
 *    client-side (the API only returns unit-length vectors at the full 3072 dims).
 */
class GeminiEmbeddingClient(
    private val apiKey: String,
    override val modelName: String = DEFAULT_MODEL,
    override val dimension: Int = DEFAULT_DIMENSION,
    private val batchSize: Int = 100,
    private val maxRetries: Int = 4,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    engine: HttpClientEngine = OkHttp.create(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : EmbeddingClient {
    companion object {
        const val DEFAULT_MODEL = "gemini-embedding-001"
        const val DEFAULT_DIMENSION = 768
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val http = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    override suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType): List<FloatArray> {
        require(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        return texts.chunked(batchSize).flatMap { embedBatch(it, taskType) }
    }

    private suspend fun embedBatch(batch: List<String>, taskType: EmbeddingTaskType): List<FloatArray> {
        val body = BatchRequest(batch.map {
            EmbedRequest(
                model = "models/$modelName",
                content = Content(listOf(Part(it))),
                taskType = taskType.apiValue,
                outputDimensionality = dimension,
            )
        })
        var attempt = 0
        while (true) {
            val retryable: String
            try {
                val response = http.post("$baseUrl/models/$modelName:batchEmbedContents") {
                    contentType(ContentType.Application.Json)
                    header("x-goog-api-key", apiKey)
                    setBody(body)
                }
                if (response.status.isSuccess()) {
                    val parsed = json.decodeFromString<BatchResponse>(response.bodyAsText())
                    check(parsed.embeddings.size == batch.size) {
                        "Gemini returned ${parsed.embeddings.size} embeddings for ${batch.size} inputs"
                    }
                    return parsed.embeddings.map { e ->
                        check(e.values.size == dimension) { "Expected $dimension dims, got ${e.values.size}" }
                        e.values.toFloatArray().l2Normalized()
                    }
                }
                val text = response.bodyAsText().take(300)
                if (response.status != HttpStatusCode.TooManyRequests && response.status.value < 500) {
                    throw EmbeddingException("Gemini embeddings HTTP ${response.status.value}: $text")
                }
                retryable = "HTTP ${response.status.value}: $text"
            } catch (e: java.io.IOException) {
                if (attempt >= maxRetries) throw EmbeddingException("Network error: ${e.message}", e)
                sleep(backoffMillis(attempt++))
                continue
            }
            if (attempt >= maxRetries) throw EmbeddingException("Gemini embeddings failed after retries - $retryable")
            sleep(backoffMillis(attempt++))
        }
    }

    private fun backoffMillis(attempt: Int) = 1000L shl attempt

    @Serializable private data class Part(val text: String)
    @Serializable private data class Content(val parts: List<Part>)
    @Serializable private data class EmbedRequest(
        val model: String,
        val content: Content,
        val taskType: String,
        val outputDimensionality: Int,
    )
    @Serializable private data class BatchRequest(val requests: List<EmbedRequest>)
    @Serializable private data class Values(val values: List<Float>)
    @Serializable private data class BatchResponse(val embeddings: List<Values> = emptyList())
}

class EmbeddingException(message: String, cause: Throwable? = null) : Exception(message, cause)
