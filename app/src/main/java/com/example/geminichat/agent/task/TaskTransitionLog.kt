package com.example.geminichat.agent.task

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val transitionLogJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Day 15: An entry in the task transition journal recording either an applied transition
 * or a rejected attempt.
 *
 * @property timestamp Epoch millis when the transition was attempted.
 * @property event The [TaskEvent] that was triggered.
 * @property fromStage Stage before the transition.
 * @property toStage Target stage if applied, or `null` if rejected.
 * @property applied Whether the transition succeeded or was rejected.
 * @property note Explanation, reason for rejection, or custom validation note.
 */
@Serializable
data class TaskTransitionRecord(
    val timestamp: Long,
    val event: TaskEvent,
    val fromStage: TaskStage,
    val toStage: TaskStage? = null,
    val applied: Boolean,
    val note: String = ""
)

/**
 * Day 15: Persists the transition journal per branch so that execution continuation,
 * pauses, and rejects can be verified deterministically across restarts.
 */
class TaskTransitionLogStore(private val file: File) {

    private val mapSerializer = MapSerializer(
        String.serializer(),
        ListSerializer(TaskTransitionRecord.serializer())
    )

    private fun loadAll(): Map<String, List<TaskTransitionRecord>> {
        if (!file.exists()) return emptyMap()
        return try {
            transitionLogJson.decodeFromString(mapSerializer, file.readText())
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    private fun saveAll(all: Map<String, List<TaskTransitionRecord>>) {
        file.parentFile?.mkdirs()
        file.writeText(transitionLogJson.encodeToString(mapSerializer, all))
    }

    /** Loads the transition journal for [branchId]. */
    fun load(branchId: String): List<TaskTransitionRecord> = loadAll()[branchId] ?: emptyList()

    /** Appends a new [record] to [branchId]'s transition journal. */
    fun append(branchId: String, record: TaskTransitionRecord) {
        val all = loadAll().toMutableMap()
        val current = (all[branchId] ?: emptyList()) + record
        all[branchId] = current
        saveAll(all)
    }

    /** Overwrites the entire transition journal for [branchId]. */
    fun save(branchId: String, records: List<TaskTransitionRecord>) {
        val all = loadAll().toMutableMap()
        all[branchId] = records
        saveAll(all)
    }

    /** Clears the transition journal for [branchId]. */
    fun remove(branchId: String) {
        val all = loadAll().toMutableMap()
        if (all.remove(branchId) != null) saveAll(all)
    }

    companion object {
        const val FILE_NAME = "task_transition_log.json"
    }
}
