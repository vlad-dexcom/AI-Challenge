package com.example.geminichat.agent.workout

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val workoutSummaryJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Day 18: persists the single latest [WorkoutSummary] (there is only ever one — each scheduled
 * run overwrites the previous summary, it doesn't append a history).
 */
class WorkoutSummaryStore(private val file: File) {

    /** The latest computed summary, or `null` if [WorkoutDigestWorker] hasn't run yet. */
    fun load(): WorkoutSummary? {
        if (!file.exists()) return null
        return try {
            workoutSummaryJson.decodeFromString(WorkoutSummary.serializer(), file.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun save(summary: WorkoutSummary) {
        file.parentFile?.mkdirs()
        file.writeText(workoutSummaryJson.encodeToString(WorkoutSummary.serializer(), summary))
    }

    companion object {
        const val FILE_NAME = "workout_summary.json"
    }
}
