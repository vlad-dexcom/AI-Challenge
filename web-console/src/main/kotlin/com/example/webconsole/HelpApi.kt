package com.example.webconsole

import com.example.devassistant.HelpAssistant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HelpRequest(val question: String)

@Serializable
data class HelpSource(val file: String, val section: String)

@Serializable
data class HelpMcpCall(val tool: String, val text: String, val isError: Boolean)

@Serializable
data class HelpResponse(
    val question: String,
    val answer: String,
    val idk: Boolean,
    val sources: List<HelpSource>,
    val mcpCalls: List<HelpMcpCall>,
    val language: String,
)

/**
 * Day 31 `/help` for the web console. The assistant (docs index + Gemini + git MCP) is created lazily on the first
 * request, because building the index calls the embedding API; a missing key is reported as a 400 then, not at startup.
 */
class HelpApi(assistantFactory: suspend () -> HelpAssistant) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val assistant: HelpAssistant by lazy {
        try {
            runBlocking { assistantFactory() }
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, e.message ?: "Developer assistant is not configured")
        }
    }

    fun help(body: String): String {
        val req = try {
            json.decodeFromString(HelpRequest.serializer(), body)
        } catch (e: Exception) {
            throw ApiException(400, "Invalid request: ${e.message?.take(200)}")
        }
        // "/help <question>" is accepted as typed in the CLI.
        val question = req.question.trim().removePrefix("/help").trim()
        if (question.isEmpty()) throw ApiException(400, "Question is empty")
        if (question.length > MAX_QUESTION) throw ApiException(400, "Question is too long (max $MAX_QUESTION characters)")

        val a = runBlocking { assistant.help(question) }.getOrElse { throw ApiException(502, it.message ?: "The assistant failed") }
        val s = a.structured
        return json.encodeToString(
            HelpResponse.serializer(),
            HelpResponse(
                question, a.answer, s.idk,
                s.sources.map { HelpSource(it.file, it.section) }.distinct(),
                a.mcpCalls.map { HelpMcpCall(it.tool, it.text, it.isError) },
                s.language,
            ),
        )
    }

    private companion object {
        const val MAX_QUESTION = 1000
    }
}
