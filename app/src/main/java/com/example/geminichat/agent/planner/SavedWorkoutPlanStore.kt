package com.example.geminichat.agent.planner

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val savedWorkoutPlanJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Day 19: persists every [SavedWorkoutPlan] as a single JSON array file — same "one small JSON
 * file, load-all/save-all" shape as [com.example.geminichat.agent.workout.WorkoutLogStore].
 */
class SavedWorkoutPlanStore(private val file: File) {

    private val listSerializer = ListSerializer(SavedWorkoutPlan.serializer())

    /** Every saved plan, oldest first. Empty if nothing has been saved yet. */
    fun loadAll(): List<SavedWorkoutPlan> {
        if (!file.exists()) return emptyList()
        return try {
            savedWorkoutPlanJson.decodeFromString(listSerializer, file.readText())
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /** Appends [plan] to the store, persisting immediately. */
    fun append(plan: SavedWorkoutPlan) {
        val all = loadAll() + plan
        file.parentFile?.mkdirs()
        file.writeText(savedWorkoutPlanJson.encodeToString(listSerializer, all))
    }

    companion object {
        const val FILE_NAME = "saved_workout_plans.json"
    }
}
