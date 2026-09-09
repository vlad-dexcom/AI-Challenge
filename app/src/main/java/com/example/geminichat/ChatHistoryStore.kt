package com.example.geminichat

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Everything needed to resume a chat exactly where it left off: the message transcript plus
 * which agent persona and model were active.
 */
@Serializable
data class ChatHistorySnapshot(
    val messages: List<ChatMessage> = emptyList(),
    val selectedAgentId: String = AgentDefaults.AGENT_ID,
    val selectedModel: String = GeminiApiClient.DEFAULT_MODEL
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
class ChatHistoryStore(private val file: File) {

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
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ChatHistorySnapshot.serializer(), snapshot))
    }

    companion object {
        const val FILE_NAME = "chat_history.json"
    }
}
