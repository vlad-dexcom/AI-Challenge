package com.example.core.llm

import com.example.core.platform.defaultHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One NDJSON line of a streaming `/api/chat` reply; the last one has [done] = true and carries the counters. */
data class OllamaStreamChunk(
    val content: String,
    val thinking: String = "",
    val done: Boolean = false,
    val doneReason: String? = null,
    val model: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val evalMillis: Long = 0,
    val totalMillis: Long = 0,
)

/** Upstream refused the request ([status] is the HTTP code) or reported an error inside the stream ([status] = null). */
class OllamaStreamException(val status: Int?, message: String) : IllegalStateException(message)

/**
 * Streaming counterpart of [OllamaChatClient]: `/api/chat` with `stream=true` as a cold [Flow].
 * Cancelling the collector closes the HTTP connection, which makes Ollama stop generating.
 */
class OllamaStreamingClient(
    private val baseUrl: String = OllamaChatClient.DEFAULT_URL,
    engine: HttpClientEngine = defaultHttpEngine(),
    requestTimeoutMillis: Long = 600_000,
    idleTimeoutMillis: Long = 120_000,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClient(engine) {
        install(HttpTimeout) {
            this.requestTimeoutMillis = requestTimeoutMillis
            socketTimeoutMillis = idleTimeoutMillis
            connectTimeoutMillis = 5_000
        }
    }

    fun chatStream(
        messages: List<OllamaMessage>,
        model: String,
        temperature: Double? = null,
        numCtx: Int? = null,
        maxTokens: Int? = null,
        think: Boolean? = null,
    ): Flow<OllamaStreamChunk> = flow {
        val options = if (temperature == null && numCtx == null && maxTokens == null) null else Options(temperature, numCtx, maxTokens)
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(model, messages, true, options, think))
        http.preparePost("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw OllamaStreamException(response.status.value, "Ollama HTTP ${response.status.value}: ${response.bodyAsText().take(300)}")
            }
            val channel = response.bodyAsChannel()
            var finished = false
            while (!finished) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val r = json.decodeFromString(ChatLine.serializer(), line)
                if (r.error != null) throw OllamaStreamException(null, "Ollama: ${r.error.take(300)}")
                finished = r.done
                emit(
                    OllamaStreamChunk(
                        content = r.message?.content.orEmpty(),
                        thinking = r.message?.thinking.orEmpty(),
                        done = r.done,
                        doneReason = r.doneReason,
                        model = r.model.orEmpty(),
                        promptTokens = r.promptEvalCount,
                        completionTokens = r.evalCount,
                        evalMillis = r.evalDuration / 1_000_000,
                        totalMillis = r.totalDuration / 1_000_000,
                    ),
                )
            }
            if (!finished) throw OllamaStreamException(null, "Ollama stream ended before the final chunk")
        }
    }

    @Serializable private data class Options(
        val temperature: Double? = null,
        @SerialName("num_ctx") val numCtx: Int? = null,
        @SerialName("num_predict") val numPredict: Int? = null,
    )
    @Serializable private data class ChatRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean,
        val options: Options? = null,
        val think: Boolean? = null,
    )
    @Serializable private data class ChatLine(
        val model: String? = null,
        val message: LineMessage? = null,
        val done: Boolean = false,
        @SerialName("done_reason") val doneReason: String? = null,
        val error: String? = null,
        @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
        @SerialName("eval_count") val evalCount: Int = 0,
        @SerialName("eval_duration") val evalDuration: Long = 0,
        @SerialName("total_duration") val totalDuration: Long = 0,
    )
    @Serializable private data class LineMessage(val content: String = "", val thinking: String? = null)
}
