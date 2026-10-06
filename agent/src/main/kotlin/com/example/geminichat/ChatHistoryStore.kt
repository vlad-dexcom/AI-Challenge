package com.example.geminichat

import com.example.core.io.exists
import com.example.core.io.readText
import com.example.core.io.writeText
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import com.example.core.llm.GeminiApiClient

/**
 * Day 10: everything needed to resume one conversation *branch* independently of the others —
 * its own transcript and its own token counters. [ChatController] keeps one of these per branch
 * (only the currently active branch lives "unpacked" in [ChatUiState]; the rest sit here,
 * ready to be swapped back in on [ChatController.onBranchSelected]).
 */
@Serializable
data class BranchSnapshot(
    val id: String,
    val name: String,
    val messages: List<ChatMessage> = emptyList(),
    val dialogTokenTotal: Int = 0,
    /** Day 11: tokens spent on [com.example.geminichat.agent.memory.MemoryRouter] calls for
     * this branch. The branch's working memory *contents* are stored separately, in
     * [com.example.geminichat.agent.memory.WorkingMemoryStore], keyed by this branch's [id]. */
    val memoryRoutingTokensTotal: Int = 0
)

/**
 * Everything needed to resume a chat exactly where it left off: the message transcript plus
 * which agent persona and model were active.
 */
@Serializable
data class ChatHistorySnapshot(
    val messages: List<ChatMessage> = emptyList(),
    val selectedAgentId: String = AgentDefaults.AGENT_ID,
    val selectedModel: String = GeminiApiClient.DEFAULT_MODEL,
    /** Running total of [ChatUiState.dialogTokenTotal] for the active branch. */
    val dialogTokenTotal: Int = 0,
    /**
     * Day 10 branching: every branch *other than* the currently active one (whose state is
     * unpacked into the top-level fields above). The active branch's own snapshot is
     * reconstructed on save from those top-level fields — see [ChatController].
     */
    val otherBranches: List<BranchSnapshot> = emptyList(),
    val currentBranchId: String = "main",
    val currentBranchName: String = "main",
    /** A saved checkpoint ready to be forked into one or more new branches, if any. */
    val checkpoint: BranchSnapshot? = null
)

/**
 * Small indirection so this file doesn't need to import [com.example.geminichat.agent.AgentCatalog]
 * just for its default id (keeps this a plain persistence concern).
 */
object AgentDefaults {
    const val AGENT_ID = "personal-trainer"
}


/**
 * Persists [ChatHistorySnapshot] to a JSON file on disk so conversation context survives an
 * app restart — this is the "sohranenie konteksta" (context persistence) piece: history is no
 * longer only kept in memory for the lifetime of the process (see [com.example.geminichat.agent.AgentRequest]).
 *
 * Kept deliberately simple (single JSON file, no DB) since chat history for a single-user demo
 * app is small; swapping this for SQLite later would only mean reimplementing [load]/[save].
 */
class ChatHistoryStore(private val file: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** Reads the last saved snapshot, or an empty default if none exists / it's unreadable. */
    fun load(): ChatHistorySnapshot {
        if (!file.exists()) return ChatHistorySnapshot()
        return try {
            json.decodeFromString(ChatHistorySnapshot.serializer(), file.readText())
        } catch (e: SerializationException) {
            ChatHistorySnapshot()
        } catch (e: IllegalArgumentException) {
            ChatHistorySnapshot()
        }
    }

    /** Overwrites the saved snapshot. Safe to call frequently (e.g. after every new message). */
    fun save(snapshot: ChatHistorySnapshot) {
        file.writeText(json.encodeToString(ChatHistorySnapshot.serializer(), snapshot))
    }

    companion object {
        const val FILE_NAME = "chat_history.json"
    }
}
