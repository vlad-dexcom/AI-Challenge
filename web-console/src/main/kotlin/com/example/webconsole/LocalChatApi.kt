package com.example.webconsole

import com.example.core.llm.OllamaChatClient
import com.example.core.llm.OllamaMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalChatConfig(val baseUrl: String, val defaultModel: String, val reachable: Boolean, val models: List<String>, val error: String? = null)

@Serializable
data class LocalChatRequest(
    val messages: List<OllamaMessage>,
    val model: String? = null,
    val system: String? = null,
    val temperature: Double? = null,
    val think: Boolean? = null,
)

@Serializable
data class LocalChatResponse(
    val reply: String,
    val thinking: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val tokensPerSecond: Double,
    val totalMillis: Long,
)

/** Day 26/27: plain chat with a local Ollama model. No memory layers, no RAG, no cloud; the browser owns the transcript. */
class LocalChatApi(private val baseUrl: String, private val client: OllamaChatClient, private val baseModel: String = client.model) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun config(): String {
        val models = runBlocking { client.models() }
        val cfg = LocalChatConfig(baseUrl, baseModel, models.isSuccess, models.getOrDefault(emptyList()),
            models.exceptionOrNull()?.let { "Ollama is not reachable at $baseUrl (${it.message?.take(120)}). Start it with: brew services start ollama" })
        return json.encodeToString(LocalChatConfig.serializer(), cfg)
    }

    fun chat(body: String): String {
        val req = try {
            json.decodeFromString(LocalChatRequest.serializer(), body)
        } catch (e: Exception) {
            throw ApiException(400, "Invalid request: ${e.message?.take(200)}")
        }
        if (req.messages.isEmpty() || req.messages.last().role != "user" || req.messages.last().content.isBlank()) {
            throw ApiException(400, "The last message must be a non-empty user message")
        }
        if (req.messages.any { it.role !in setOf("user", "assistant") }) throw ApiException(400, "Only user/assistant messages are accepted")
        val messages = listOfNotNull(req.system?.takeIf { it.isNotBlank() }?.let { OllamaMessage("system", it) }) + req.messages
        val reply = runBlocking { client.chat(messages, req.model?.takeIf { it.isNotBlank() } ?: baseModel, req.temperature, req.think) }
            .getOrElse { throw ApiException(502, "Local model call failed: ${it.message?.take(300)}") }
        return json.encodeToString(
            LocalChatResponse.serializer(),
            LocalChatResponse(reply.content, reply.thinking, reply.model, reply.promptTokens, reply.completionTokens, reply.tokensPerSecond, reply.totalMillis),
        )
    }
}
