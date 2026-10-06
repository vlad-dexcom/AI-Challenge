package com.example.rag.chat

import com.example.rag.ChatChunk
import com.example.rag.ChatSource
import com.example.rag.ChatTrace
import com.example.rag.StructuredAnswer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.example.core.io.atomicMoveTo
import com.example.core.io.createDirectories
import com.example.core.io.delete
import com.example.core.io.div
import com.example.core.io.isFile
import com.example.core.io.listNames
import com.example.core.io.readText
import com.example.core.io.writeText
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.time.Clock

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
class SessionStore(private val dir: Path, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun create(): ChatSession {
        val id = "s-" + Random.nextBytes(5).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return ChatSession(id, createdAt = now(), updatedAt = now()).also { save(it) }
    }

    fun load(id: String): ChatSession? {
        val f = file(id)
        return if (f.isFile()) runCatching { json.decodeFromString(ChatSession.serializer(), f.readText()) }.getOrNull() else null
    }

    fun save(session: ChatSession) {
        dir.createDirectories()
        val f = file(session.id)
        val tmp = dir / "${session.id}.json.tmp"
        tmp.writeText(json.encodeToString(ChatSession.serializer(), session))
        tmp.atomicMoveTo(f)
    }

    fun delete(id: String): Boolean = file(id).let { it.isFile().also { had -> it.delete() } }

    fun list(): List<SessionSummary> =
        dir.listNames().filter { it.endsWith(".json") }
            .mapNotNull { n -> runCatching { json.decodeFromString(ChatSession.serializer(), (dir / n).readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
            .map { SessionSummary(it.id, it.title, it.updatedAt, it.turnCount, it.memory.goal) }

    private fun file(id: String): Path {
        require(isValidId(id)) { "Invalid session id" }
        return dir / "$id.json"
    }

    companion object {
        private val ID_RE = Regex("[a-z0-9-]{1,40}")
        fun isValidId(id: String) = ID_RE.matches(id)
    }
}
