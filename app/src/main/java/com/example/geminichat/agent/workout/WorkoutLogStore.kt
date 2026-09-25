package com.example.geminichat.agent.workout

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val workoutLogJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Day 18: persists every [WorkoutLogEntry] as a single JSON array file, modeled on
 * [com.example.geminichat.agent.task.TaskStateStore]'s "one small JSON file, load-all/save-all"
 * shape — a full SQLite/Room setup would be overkill for what is, at most, a few hundred
 * append-only rows.
 */
class WorkoutLogStore(private val file: File) {

    private val listSerializer = ListSerializer(WorkoutLogEntry.serializer())

    /** Every logged workout, oldest first. Empty if nothing has been logged yet. */
    fun loadAll(): List<WorkoutLogEntry> {
        if (!file.exists()) return emptyList()
        return try {
            workoutLogJson.decodeFromString(listSerializer, file.readText())
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /** Appends [entry] to the log, persisting immediately. */
    fun append(entry: WorkoutLogEntry) {
        val all = loadAll() + entry
        file.parentFile?.mkdirs()
        file.writeText(workoutLogJson.encodeToString(listSerializer, all))
    }

    companion object {
        const val FILE_NAME = "workout_log.json"
    }
}
