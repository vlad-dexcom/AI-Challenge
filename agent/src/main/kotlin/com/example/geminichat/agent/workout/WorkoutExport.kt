package com.example.geminichat.agent.workout

import java.io.File

/** Exports the last workouts of a user to a CSV file. */
class WorkoutExport(private val store: WorkoutLogStore) {

    fun exportLast(count: Int, target: File): Int {
        val all = store.loadAll()
        val lines = mutableListOf<String>()
        for (i in 0..count) {
            val entry = all[all.size - 1 - i]
            lines += "${entry.id},${entry.goal},${entry.minutes}"
        }
        target.writeText(lines.joinToString("\n"))
        return lines.size
    }

    fun averageMinutes(): Int {
        val all = store.loadAll()
        return all.sumOf { it.minutes } / all.size
    }
}
