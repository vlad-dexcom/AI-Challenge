package com.example.core.llm

import com.example.core.platform.defaultHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OllamaMessage(val role: String, val content: String)

/** One assistant turn plus Ollama's own timing counters (durations are nanoseconds in the API; converted to ms here). */
data class OllamaReply(
    val content: String,
    val thinking: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val evalMillis: Long,
    val totalMillis: Long,
) {
    val tokensPerSecond: Double get() = if (evalMillis > 0) completionTokens * 1000.0 / evalMillis else 0.0
}

/** Plain-REST client for a local Ollama server (`/api/chat`, `/api/tags`); no cloud, no API key, no retries. */
class OllamaChatClient(
    private val baseUrl: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
    engine: HttpClientEngine = defaultHttpEngine(),
) {
    companion object {
        const val DEFAULT_URL = "http://localhost:11434"
        const val DEFAULT_MODEL = "gemma4:26b-a4b-it-qat"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClient(engine) {
        install(HttpTimeout) { requestTimeoutMillis = 300_000; socketTimeoutMillis = 300_000; connectTimeoutMillis = 5_000 }
    }

    suspend fun chat(
        messages: List<OllamaMessage>,
        model: String = this.model,
        temperature: Double? = null,
        think: Boolean? = null,
        jsonMode: Boolean = false,
        numCtx: Int? = null,
        maxTokens: Int? = null,
    ): Result<OllamaReply> = try {
        val options = if (temperature == null && numCtx == null && maxTokens == null) null else Options(temperature, numCtx, maxTokens)
        var body = ChatRequest(model, messages, false, options, think, if (jsonMode) "json" else null)
        var response = post(body)
        var text = response.bodyAsText()
        // Models without a thinking mode reject the `think` flag; retry plain so one toggle works for every model.
        if (think != null && response.status.value == 400 && text.contains("does not support thinking")) {
            body = body.copy(think = null)
            response = post(body)
            text = response.bodyAsText()
        }
        if (!response.status.isSuccess()) {
            Result.failure(IllegalStateException("Ollama HTTP ${response.status.value}: ${text.take(300)}"))
        } else {
            val r = json.decodeFromString(ChatResponse.serializer(), text)
            val content = r.message?.content?.trim().orEmpty()
            if (content.isEmpty()) Result.failure(IllegalStateException("Ollama returned an empty answer"))
            else Result.success(
                OllamaReply(content, r.message?.thinking?.trim().orEmpty(), r.model ?: model, r.promptEvalCount, r.evalCount, r.evalDuration / 1_000_000, r.totalDuration / 1_000_000),
            )
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Names of the models pulled on the server; failure means Ollama is not reachable. */
    suspend fun models(): Result<List<String>> = try {
        val response = http.get("$baseUrl/api/tags")
        if (!response.status.isSuccess()) Result.failure(IllegalStateException("Ollama HTTP ${response.status.value}"))
        else Result.success(json.decodeFromString(TagsResponse.serializer(), response.bodyAsText()).models.map { it.name })
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun post(body: ChatRequest) = http.post("$baseUrl/api/chat") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(ChatRequest.serializer(), body))
    }

    @Serializable private data class Options(
        val temperature: Double? = null,
        @kotlinx.serialization.SerialName("num_ctx") val numCtx: Int? = null,
        @kotlinx.serialization.SerialName("num_predict") val numPredict: Int? = null,
    )
    @Serializable private data class ChatRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean,
        val options: Options? = null,
        val think: Boolean? = null,
        val format: String? = null,
    )
    @Serializable private data class ChatResponse(
        val model: String? = null,
        val message: ReplyMessage? = null,
        @kotlinx.serialization.SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
        @kotlinx.serialization.SerialName("eval_count") val evalCount: Int = 0,
        @kotlinx.serialization.SerialName("eval_duration") val evalDuration: Long = 0,
        @kotlinx.serialization.SerialName("total_duration") val totalDuration: Long = 0,
    )
    @Serializable private data class ReplyMessage(val content: String = "", val thinking: String? = null)
    @Serializable private data class Tag(val name: String)
    @Serializable private data class TagsResponse(val models: List<Tag> = emptyList())
}
