package com.example.core.llm

import com.example.core.platform.defaultHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Text added before documents / queries: several embedders are trained with task instructions and degrade without them. */
data class EmbedPrompts(val document: String = "", val query: String = "") {
    val isEmpty: Boolean get() = document.isEmpty() && query.isEmpty()

    companion object {
        val NONE = EmbedPrompts()

        /** Conventions published for the model families (EmbeddingGemma "task: ... | query:" format, Qwen3-Embedding "Instruct:" for queries only). */
        fun defaultsFor(model: String): EmbedPrompts = when {
            model.startsWith("embeddinggemma") -> EmbedPrompts("title: none | text: ", "task: search result | query: ")
            model.startsWith("qwen3-embedding") ->
                EmbedPrompts("", "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ")
            model.startsWith("nomic-embed") -> EmbedPrompts("search_document: ", "search_query: ")
            else -> NONE
        }
    }
}

/**
 * Local embeddings through Ollama `POST /api/embed` - no cloud, no key.
 *
 * [modelName] (stored in index metadata, so an index is only searchable with the same setup) is `ollama:<model>`,
 * plus `+prompts` when task prompts are in use, because they change the vectors.
 * Use [connect] to learn the real [dimension] from the server; vectors are L2-normalised client-side.
 */
class OllamaEmbeddingClient(
    private val model: String,
    override val dimension: Int,
    private val prompts: EmbedPrompts = EmbedPrompts.NONE,
    private val baseUrl: String = OllamaChatClient.DEFAULT_URL,
    private val batchSize: Int = 32,
    engine: HttpClientEngine = defaultHttpEngine(),
) : EmbeddingClient {
    override val modelName: String = "ollama:$model" + if (prompts.isEmpty) "" else "+prompts"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = HttpClient(engine) {
        install(HttpTimeout) { requestTimeoutMillis = 300_000; socketTimeoutMillis = 300_000; connectTimeoutMillis = 5_000 }
    }

    override suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType): List<FloatArray> {
        val prefix = if (taskType == EmbeddingTaskType.RETRIEVAL_QUERY) prompts.query else prompts.document
        return texts.chunked(batchSize).flatMap { embedBatch(it.map { t -> prefix + t }) }
    }

    private suspend fun embedBatch(batch: List<String>): List<FloatArray> {
        val response = try {
            http.post("$baseUrl/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(Request.serializer(), Request(model, batch)))
            }
        } catch (e: Exception) {
            throw EmbeddingException("Ollama is not reachable at $baseUrl: ${e.message}", e)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw EmbeddingException("Ollama embeddings HTTP ${response.status.value}: ${text.take(300)}")
        val parsed = try {
            json.decodeFromString(Response.serializer(), text)
        } catch (e: Exception) {
            throw EmbeddingException("Unexpected Ollama embeddings response: ${e.message}", e)
        }
        check(parsed.embeddings.size == batch.size) { "Ollama returned ${parsed.embeddings.size} embeddings for ${batch.size} inputs" }
        return parsed.embeddings.map { v ->
            check(v.size == dimension) { "Expected $dimension dims, got ${v.size}" }
            v.toFloatArray().l2Normalized()
        }
    }

    @Serializable private data class Request(val model: String, val input: List<String>, val truncate: Boolean = true)
    @Serializable private data class Response(val embeddings: List<List<Float>> = emptyList())

    companion object {
        /** Embeds a probe text to learn the model's vector size, then returns a ready client. */
        suspend fun connect(
            model: String,
            prompts: EmbedPrompts = EmbedPrompts.defaultsFor(model),
            baseUrl: String = OllamaChatClient.DEFAULT_URL,
            batchSize: Int = 32,
            engine: HttpClientEngine = defaultHttpEngine(),
        ): OllamaEmbeddingClient {
            val probe = OllamaEmbeddingClient(model, 0, prompts, baseUrl, batchSize, engine)
            val dim = probe.probeDimension()
            return OllamaEmbeddingClient(model, dim, prompts, baseUrl, batchSize, engine)
        }
    }

    private suspend fun probeDimension(): Int {
        val response = try {
            http.post("$baseUrl/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(Request.serializer(), Request(model, listOf("dimension probe"))))
            }
        } catch (e: Exception) {
            throw EmbeddingException("Ollama is not reachable at $baseUrl: ${e.message}", e)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw EmbeddingException("Ollama embeddings HTTP ${response.status.value}: ${text.take(300)} (is '$model' pulled?)")
        return json.decodeFromString(Response.serializer(), text).embeddings.firstOrNull()?.size
            ?: throw EmbeddingException("Ollama returned no embedding for '$model'")
    }
}
