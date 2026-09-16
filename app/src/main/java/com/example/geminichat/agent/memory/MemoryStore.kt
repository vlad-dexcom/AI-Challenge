package com.example.geminichat.agent.memory

import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Day 11: persists one memory layer's [MemorySnapshot] independently of the others. This is the
 * "разные типы памяти хранятся отдельно" requirement made concrete — [LongTermMemoryStore] and
 * [WorkingMemoryStore] write to their own files ([LONG_TERM_FILE_NAME] / [WORKING_MEMORY_FILE_NAME]),
 * separate from [com.example.geminichat.ChatHistoryStore]'s `chat_history.json` (which owns the
 * short-term layer: the raw transcript and Day 9's summary). Swapping how one layer is made
 * durable never touches the other layers' storage.
 */
private val memoryJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * [MemoryLayer.LONG_TERM] storage: a single global [MemorySnapshot], not scoped to any
 * conversation branch or agent — the user's profile, standing decisions, and durable knowledge
 * should be visible no matter which branch or persona is currently active (see
 * [com.example.geminichat.agent.memory.MemoryRouter]).
 */
class LongTermMemoryStore(private val file: File) {

    /** Reads the last saved long-term snapshot, or empty if none exists / it's unreadable. */
    fun load(): MemorySnapshot {
        if (!file.exists()) return MemorySnapshot.EMPTY
        return try {
            memoryJson.decodeFromString(MemorySnapshot.serializer(), file.readText())
        } catch (e: SerializationException) {
            MemorySnapshot.EMPTY
        } catch (e: IllegalArgumentException) {
            MemorySnapshot.EMPTY
        }
    }

    /** Overwrites the saved long-term snapshot. */
    fun save(snapshot: MemorySnapshot) {
        file.parentFile?.mkdirs()
        file.writeText(memoryJson.encodeToString(MemorySnapshot.serializer(), snapshot))
    }

    companion object {
        const val FILE_NAME = "long_term_memory.json"
    }
}

/**
 * [MemoryLayer.WORKING] storage: one [MemorySnapshot] *per conversation branch* (see
 * [com.example.geminichat.BranchSnapshot]), so switching branches — or ending a task on one
 * branch via "End task" — never affects another branch's working memory, and both are entirely
 * separate from [LongTermMemoryStore].
 *
 * Kept as a single JSON file mapping branch id to snapshot (mirrors the simplicity of
 * [com.example.geminichat.ChatHistoryStore] rather than one file per branch) since the number of
 * branches in this app is small.
 */
class WorkingMemoryStore(private val file: File) {

    private val mapSerializer = MapSerializer(String.serializer(), MemorySnapshot.serializer())

    private fun loadAll(): Map<String, MemorySnapshot> {
        if (!file.exists()) return emptyMap()
        return try {
            memoryJson.decodeFromString(mapSerializer, file.readText())
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    private fun saveAll(all: Map<String, MemorySnapshot>) {
        file.parentFile?.mkdirs()
        file.writeText(memoryJson.encodeToString(mapSerializer, all))
    }

    /** The working memory for [branchId], or empty if that branch has none saved yet. */
    fun load(branchId: String): MemorySnapshot = loadAll()[branchId] ?: MemorySnapshot.EMPTY

    /** Overwrites [branchId]'s working memory, leaving every other branch's untouched. */
    fun save(branchId: String, snapshot: MemorySnapshot) {
        val all = loadAll().toMutableMap()
        all[branchId] = snapshot
        saveAll(all)
    }

    /** Drops [branchId]'s working memory entirely (e.g. when a branch is deleted). */
    fun remove(branchId: String) {
        val all = loadAll().toMutableMap()
        if (all.remove(branchId) != null) saveAll(all)
    }

    companion object {
        const val FILE_NAME = "working_memory.json"
    }
}
