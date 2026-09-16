package com.example.geminichat.agent.task

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val taskStateJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Day 13: persists [TaskState] *per conversation branch* — modeled directly on
 * [com.example.geminichat.agent.memory.WorkingMemoryStore], since a task's position (this
 * store) and a task's facts (that store) are scoped identically: switching branches, or
 * forking one from a checkpoint (see [com.example.geminichat.ChatViewModel.onCreateBranchFromCheckpoint]),
 * must carry each branch's own progress along without leaking into another branch.
 *
 * Kept as a single JSON file mapping branch id to [TaskState] (rather than one file per
 * branch), matching [com.example.geminichat.agent.memory.WorkingMemoryStore]'s choice, since
 * the number of branches in this app is small.
 */
class TaskStateStore(private val file: File) {

    private val mapSerializer = MapSerializer(String.serializer(), TaskState.serializer())

    private fun loadAll(): Map<String, TaskState> {
        if (!file.exists()) return emptyMap()
        return try {
            taskStateJson.decodeFromString(mapSerializer, file.readText())
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    private fun saveAll(all: Map<String, TaskState>) {
        file.parentFile?.mkdirs()
        file.writeText(taskStateJson.encodeToString(mapSerializer, all))
    }

    /** The task state for [branchId], or [TaskState.NONE] if that branch has none saved yet. */
    fun load(branchId: String): TaskState = loadAll()[branchId] ?: TaskState.NONE

    /** Overwrites [branchId]'s task state, leaving every other branch's untouched. */
    fun save(branchId: String, state: TaskState) {
        val all = loadAll().toMutableMap()
        all[branchId] = state
        saveAll(all)
    }

    /** Drops [branchId]'s task state entirely (e.g. when a branch is deleted). */
    fun remove(branchId: String) {
        val all = loadAll().toMutableMap()
        if (all.remove(branchId) != null) saveAll(all)
    }

    companion object {
        const val FILE_NAME = "task_state.json"
    }
}
