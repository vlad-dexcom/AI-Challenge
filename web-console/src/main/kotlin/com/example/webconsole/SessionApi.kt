package com.example.webconsole

import com.example.core.platform.toKxPath

import com.example.rag.CachedTextGenerator
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.GeminiTextGenerator
import com.example.rag.IndexMeta
import com.example.rag.IndexStore
import com.example.rag.LlmUsage
import com.example.core.llm.TextGenerator
import com.example.rag.VectorRetriever
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import com.example.rag.chat.ChatEngine
import com.example.rag.chat.ChatOptions
import com.example.rag.chat.ChatSession
import com.example.rag.chat.MemoryChange
import com.example.rag.chat.MemoryEdit
import com.example.rag.chat.SessionStore
import com.example.rag.chat.SessionSummary
import com.example.rag.chat.TaskMemory

@Serializable
data class SessionList(val sessions: List<SessionSummary>)

@Serializable
data class SessionResponse(val session: ChatSession)

@Serializable
data class SendMessageRequest(val text: String, val options: ChatOptions = ChatOptions())

/**
 * Session endpoints of the web UI (HTTP plumbing stays in `UiServer`):
 * GET/POST /api/sessions, GET/DELETE /api/sessions/{id}, POST /api/sessions/{id}/messages,
 * PUT /api/sessions/{id}/memory, POST /api/sessions/{id}/memory/reset, POST /api/sessions/{id}/reset.
 * `UiApi` serialises all session calls (the server itself is multi-threaded), so a session is never written concurrently.
 */
class SessionApi(
    private val store: SessionStore,
    private val indexDir: File,
    private val apiKey: String,
    private val embedderFor: (IndexMeta) -> EmbeddingClient?,
    private val generatorFor: (String) -> TextGenerator?,
    private val local: LocalRag? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun handle(method: String, path: String, body: String): String {
        val parts = path.removePrefix("/api/sessions").trim('/').split('/').filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() && method == "GET" -> json.encodeToString(SessionList.serializer(), SessionList(store.list()))
            parts.isEmpty() && method == "POST" -> respond(store.create())
            parts.size == 1 && method == "GET" -> respond(load(parts[0]))
            parts.size == 1 && method == "DELETE" -> { load(parts[0]); store.delete(parts[0]); json.encodeToString(SessionList.serializer(), SessionList(store.list())) }
            parts.size == 2 && parts[1] == "messages" && method == "POST" -> send(load(parts[0]), body)
            parts.size == 2 && parts[1] == "memory" && method == "PUT" -> editMemory(load(parts[0]), body)
            parts.size == 3 && parts[1] == "memory" && parts[2] == "reset" && method == "POST" -> resetMemory(load(parts[0]))
            parts.size == 2 && parts[1] == "reset" && method == "POST" -> respond(resetChat(load(parts[0])))
            else -> throw ApiException(404, "Not found")
        }
    }

    private fun respond(s: ChatSession) = json.encodeToString(SessionResponse.serializer(), SessionResponse(s))

    private fun load(id: String): ChatSession {
        if (!SessionStore.isValidId(id)) throw ApiException(400, "Invalid session id")
        return store.load(id) ?: throw ApiException(404, "No such session")
    }

    private fun resetChat(s: ChatSession) =
        s.copy(messages = emptyList(), memory = TaskMemory(), summary = "", summarizedUpTo = 0, title = "New chat").also(store::save)

    private fun resetMemory(s: ChatSession): String {
        val turn = s.turnCount
        val cleared = s.copy(memory = TaskMemory(changes = listOf(MemoryChange(turn, "memory", null, null, null, "reset", "user"))))
        store.save(cleared)
        return respond(cleared)
    }

    private fun editMemory(s: ChatSession, body: String): String {
        val edit = try { json.decodeFromString(MemoryEdit.serializer(), body) } catch (e: Exception) { throw ApiException(400, "Invalid request: ${e.message?.take(200)}") }
        val merged = s.memory.applyEdit(edit, s.turnCount)
        val saved = s.copy(memory = merged.memory)
        store.save(saved)
        return respond(saved)
    }

    private fun send(s: ChatSession, body: String): String {
        val req = try { json.decodeFromString(SendMessageRequest.serializer(), body) } catch (e: Exception) { throw ApiException(400, "Invalid request: ${e.message?.take(200)}") }
        val text = req.text.trim()
        val o = req.options
        if (text.isEmpty()) throw ApiException(400, "Message is empty")
        if (text.length > MAX_MESSAGE) throw ApiException(400, "Message is too long (max $MAX_MESSAGE characters)")
        if (o.topK !in 1..20) throw ApiException(400, "topK must be between 1 and 20")
        if (o.topKBefore !in 1..50 || o.topK > o.topKBefore) throw ApiException(400, "topK before must be between topK and 50")
        if (o.threshold.isNaN() || o.threshold !in 0f..1f) throw ApiException(400, "threshold must be between 0 and 1")
        if (o.keepLastTurns !in 1..20) throw ApiException(400, "keepLastTurns must be between 1 and 20")
        if (o.summaryBatch !in 1..20) throw ApiException(400, "summaryBatch must be between 1 and 20")
        if (o.maxHistoryChars !in 500..30_000) throw ApiException(400, "maxHistoryChars must be between 500 and 30000")
        if (o.strategy !in listOf("fixed", "structure")) throw ApiException(400, "Unknown strategy '${o.strategy}'")
        if (o.model?.let { !MODEL_RE.matches(it) } == true) throw ApiException(400, "Invalid model name")

        val useLocal = o.provider == ChatApi.LOCAL || o.provider == ChatApi.HYBRID
        val localAnswer = o.provider == ChatApi.LOCAL
        if (!useLocal && o.provider != "gemini") throw ApiException(400, "Unknown provider '${o.provider}'")
        if (useLocal && local == null) throw ApiException(400, "The local provider is not configured")
        val file = File(if (useLocal) local!!.indexDir else indexDir, "${o.strategy}.json")
        if (!file.isFile) throw ApiException(404, if (useLocal) "No local index for '${o.strategy}'. Run: ./gradlew :rag:tools:run --args=\"index --provider ollama\"" else "No saved index for '${o.strategy}'. Run: ./gradlew :rag:run --args=\"index\"")
        val index = IndexStore().load(file.toKxPath())
        val embedder = (if (useLocal) local!!.embedderFor else embedderFor)(index.meta)
            ?: throw ApiException(400, if (useLocal) "Index was built with ${index.meta.embeddingModel}, which the local provider cannot query." else "Index was built with ${index.meta.embeddingModel}; set GEMINI_API_KEY (env var or local.properties) and restart the ui.")
        val model = o.model ?: if (localAnswer) local!!.defaultModel else GeminiTextGenerator.DEFAULT_MODEL
        val raw = (if (localAnswer) local!!.generatorFor else generatorFor)(model) ?: throw ApiException(400, "GEMINI_API_KEY is not set: cannot generate answers. Set the env var or add it to local.properties and restart the ui.")
        val usage = LlmUsage()
        val engine = ChatEngine(VectorRetriever(embedder, index, o.topKBefore), CachedTextGenerator(raw, model, usage), usage)
        val updated = runBlocking { engine.send(s, text, o) }.getOrElse { throw ApiException(502, "Answer failed: ${it.message?.take(300)}") }
        store.save(updated)
        return respond(updated)
    }

    companion object {
        private const val MAX_MESSAGE = 2000
        private val MODEL_RE = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
