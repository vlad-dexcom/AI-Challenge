package com.example.rag.chat

import com.example.rag.ChatChunk
import com.example.rag.ChatSource
import com.example.rag.ChatTrace
import com.example.rag.StructuredAnswer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/** What the assistant message of one turn carries besides its text: everything the UI and the evals need. */
@Serializable
data class TurnInfo(
    val structured: StructuredAnswer? = null,
    /** Sources shown under the answer (empty only for an "I don't know" turn). */
    val sources: List<ChatSource> = emptyList(),
    /** "I don't know" turns: the closest chunks that were looked at (NOT used as evidence). */
    val consulted: List<ChatSource> = emptyList(),
    /** The chunks that went into the prompt (for the "Retrieved chunks" view). */
    val chunks: List<ChatChunk> = emptyList(),
    val trace: ChatTrace? = null,
    val memoryDiff: List<MemoryChange> = emptyList(),
    /** Why the memory stayed unchanged or part of a patch was ignored. */
    val memoryNotes: List<String> = emptyList(),
    val llmCalls: Int = 0,
    /** Sum of the recorded LLM latencies (a cached call adds the latency of its first run). */
    val llmMs: Long = 0,
    val wallMs: Long = 0,
    val summaryUpdated: Boolean = false,
    val memoryMode: MemoryMode = MemoryMode.FULL,
)

/** [turn] is shared by the user message and the assistant reply of one exchange (1-based). */
@Serializable
data class ChatMessage(val role: String, val text: String, val turn: Int, val info: TurnInfo? = null)

@Serializable
data class ChatSession(
    val id: String,
    val title: String = "New chat",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val messages: List<ChatMessage> = emptyList(),
    val memory: TaskMemory = TaskMemory(),
    /** Rolling summary of the turns up to [summarizedUpTo] (inclusive); those turns are no longer sent verbatim. */
    val summary: String = "",
    val summarizedUpTo: Int = 0,
) {
    val turnCount: Int get() = messages.maxOfOrNull { it.turn } ?: 0
}

@Serializable
data class SessionSummary(val id: String, val title: String, val updatedAt: Long, val turns: Int, val goal: String? = null)

/** One JSON file per session under [dir] (git-ignored `rag/sessions`). Ids are validated, writes are atomic. */
class SessionStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun create(): ChatSession {
        val id = "s-" + ByteArray(5).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return ChatSession(id, createdAt = now(), updatedAt = now()).also { save(it) }
    }

    fun load(id: String): ChatSession? {
        val f = file(id)
        return if (f.isFile) runCatching { json.decodeFromString(ChatSession.serializer(), f.readText()) }.getOrNull() else null
    }

    fun save(session: ChatSession) {
        dir.mkdirs()
        val f = file(session.id)
        val tmp = File(dir, "${session.id}.json.tmp")
        tmp.writeText(json.encodeToString(ChatSession.serializer(), session))
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun delete(id: String): Boolean = file(id).delete()

    fun list(): List<SessionSummary> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { json.decodeFromString(ChatSession.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
            .map { SessionSummary(it.id, it.title, it.updatedAt, it.turnCount, it.memory.goal) }

    private fun file(id: String): File {
        require(isValidId(id)) { "Invalid session id" }
        return File(dir, "$id.json")
    }

    companion object {
        private val ID_RE = Regex("[a-z0-9-]{1,40}")
        fun isValidId(id: String) = ID_RE.matches(id)
    }
}
